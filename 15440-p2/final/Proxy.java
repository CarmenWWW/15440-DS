/* Sample skeleton for proxy */
/* Proxy: serving as the client side */


import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.OpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Dictionary;
import java.util.Scanner;
import java.util.concurrent.*;
import javax.print.attribute.standard.Fidelity;
import javax.sql.rowset.spi.SyncResolver;

import java.lang.Throwable;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.NotBoundException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.text.MessageFormat;
import java.nio.file.Files;
import java.rmi.Naming;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Arrays;

public class Proxy{
	private static int fd = 2000;
	private static final int EIO = -5;

	// fdDict: the hashmap mapping file descriptor to corresponding raf
	private static ConcurrentHashMap<Integer, RandomAccessFile> fdDict = 
	new ConcurrentHashMap<Integer, RandomAccessFile>();

	// fileDict: the hashmap mapping file descriptor to corresponding file
	private static ConcurrentHashMap<Integer, File> fileDict = 
	new ConcurrentHashMap<Integer, File>();

	// readOnlyRAFDict: a hashmap that records the fd of read only files
	private static ConcurrentHashMap<Integer, RandomAccessFile>readOnlyRAFDict=
	new ConcurrentHashMap<Integer, RandomAccessFile>();

	// readOnlyFileDict: a hashmap that records the path of read only files
	private static ConcurrentHashMap<String, File> readOnlyFileDict = 
	new ConcurrentHashMap<String, File>();

	// cacheFolder: the cache directory
	private static String cacheFolder;

	// capacity: the number of bytes the cache can hold
	private static Integer capacity;
	public static RMIInterface server;
	public static Cache cache;
	private static final ReadWriteLock readWriteLock = 
	new ReentrantReadWriteLock();
    private static final Lock writeLock = readWriteLock.writeLock();
	private static final Lock readLock = readWriteLock.readLock();

	// chunk size: 200000 bytes
	private static Integer chunk = 200000;

	private static class FileHandler implements FileHandling {

		// getRAF: helper function for generating the raf of given file
		// @param[in]: newFile, o -- the open option, curr -- fd
		// @param[out]: 0 for success, error code for failure
		public int getRAF(File newFile, OpenOption o, int curr){
			try{
				if (o == OpenOption.READ) {
					if (!newFile.isDirectory()){
						RandomAccessFile raf = new RandomAccessFile(newFile, "r");
						raf.seek(0);
						fdDict.put(curr, raf);
						readOnlyRAFDict.put(curr, raf);
						return 0;
					}	
				}
				else if (o == OpenOption.WRITE){
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					raf.seek(0);
					fdDict.put(curr, raf);
					return 0;
				}
				else if (o == OpenOption.CREATE){
					if (!newFile.exists()){
						newFile.createNewFile();
					}
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					raf.seek(0);
					fdDict.put(curr, raf);
					return 0;
				}
				else if (o == OpenOption.CREATE_NEW){
					newFile.createNewFile();
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					raf.seek(0);
					fdDict.put(curr, raf);
					return 0;
				}
				return Errors.EINVAL;
			}
			catch (FileNotFoundException fileNotFound){
				return Errors.ENOENT;
			}
			catch (IOException io){
				return EIO;
			}
		}

		// getLocal: helper function for making a local copy file for non-read only files
		// local path is generated by adding @fd at the end of the pathname
		// @param[in]: path - path of the file (without cache folder)
		// 			   curr - the fd of the file
		// 			   cachePath - the path of the corresponding cache file
		//	           o - open option
		// @param[out]: 0 for success, error code for failure
		public int getLocal(String path, int curr, String cachePath, OpenOption o){
			try{
				// step 1. make a temporary file on cache: cacheFolder / pathname to [path]+[fd]
				String tempPath = cacheFolder + path + "@" + curr;
				File tempFile = new File (tempPath);
				String mode;
				if (o == OpenOption.READ) mode = "r";
				else mode = "rw";

				// step 2. copy file content from cacheFile to temp file
				FileChannel src = null;
				src = new FileInputStream(cachePath).getChannel();
				FileChannel dest = null;
				dest = new FileOutputStream(tempPath).getChannel();
				dest.transferFrom(src, 0, src.size());

				// step 3. add the file to cache, fileDict
				writeLock.lock();
				synchronized(cache){
					CacheFile tempFileOnCache = new CacheFile (tempFile, tempPath, 1, 1, mode);
					if (cache.addCache(tempFileOnCache) < 0){ 
						writeLock.unlock();
						return Errors.ENOENT;
					}
					writeLock.unlock();
					fileDict.put(curr, tempFile);
				}

				// step 4. get raf of local file
				int result = getRAF(tempFile, o, curr);
				return result;
			}
			catch (FileNotFoundException fileNotFound){return Errors.ENOENT;}
			catch (IOException io){return EIO;}
		}

		// retrieve: helper function for fetching the server file
		// @param[in]: path, option - open option, newFileOnCache - the cache file
		// @param[out]: 0 for success, error code for failure
		public int retrieve(String path, String option, File newFileOnCache){
			try{
				// step 1. make cache file
				int from = 0;
				RemoteFile rf = server.open(option, path, 0);
				byte[] srcByteArray = rf.getFile();

				// step 2. write the server file to cache file
				// if is directory, do not write
				if (newFileOnCache.isDirectory()){return 0;}

				// not directory and successfully fetched from server
				if (rf.getFile() != null){
					int total = Integer.parseInt(rf.getErrorCode());
					RandomAccessFile rafTemp = new RandomAccessFile(newFileOnCache, "rw");
					rafTemp.seek(0);
					rafTemp.write(srcByteArray);
					rafTemp.seek((int)newFileOnCache.length());
					from += srcByteArray.length;
					rafTemp.close();

					while (from < total){
						rf = server.open(option, path, from);
						rafTemp = new RandomAccessFile(newFileOnCache, "rw");
						srcByteArray = rf.getFile();
						rafTemp.seek((int)newFileOnCache.length());
						rafTemp.write(srcByteArray);
						rafTemp.close();
						from += srcByteArray.length;
					}
				}
				// if there is an error on server
				else{
					String error = rf.getErrorCode();
					if (error == "EINVAL") return Errors.EINVAL;
					else if (error == "EEXIST") return Errors.EEXIST;
					else if (error == "ENOENT") return Errors.ENOENT;
					else if (error == "EISDIR") return Errors.EISDIR;
					else if (error == "EPERM") return Errors.EPERM;
					else return EIO;
				}
				return 0;
			}
				catch (RemoteException e){return Errors.EPERM;}
				catch (FileNotFoundException e){return Errors.ENOENT;}
				catch (IOException io){return EIO;}
		}


		// updateFromServer: helper function for updating the cache file 
		//					when the version of server and cache does not match
		// @param[in]: path, option - open option, newFileOnCache - the cache file
		// @param[out]: 0 for success, error code for failure
		public int updateFromServer(String path, String option, File newFileOnCache){
		try{
			// step 1. make cache file
			int from = 0;
			RemoteFile rf = server.update(path, option, 0);
			byte[] srcByteArray = rf.getFile();

			// step 2. write the server file to cache file
			// if is directory, do not write
			if (newFileOnCache.isDirectory()){return 0;}

			// not directory
			if (rf.getFile() != null){
				int total = Integer.parseInt(rf.getErrorCode());
				RandomAccessFile rafTemp = new RandomAccessFile(newFileOnCache, "rw");
				rafTemp.seek(0);
				rafTemp.write(srcByteArray);
				rafTemp.seek((int)newFileOnCache.length());
				from += srcByteArray.length;
				rafTemp.close();

				while (from < total){
					rf = server.update(option, path, from);
					rafTemp = new RandomAccessFile(newFileOnCache, "rw");
					srcByteArray = rf.getFile();
					rafTemp.seek((int)newFileOnCache.length());
					rafTemp.write(srcByteArray);
					rafTemp.close();
					from += srcByteArray.length;
				}
			}
			
			else{
				// if there is an error on server
				String error = rf.getErrorCode();
				if (error == "EINVAL") return Errors.EINVAL;
				else if (error == "EEXIST") return Errors.EEXIST;
				else if (error == "ENOENT") return Errors.ENOENT;
				else if (error == "EISDIR") return Errors.EISDIR;
				else if (error == "EPERM") return Errors.EPERM;
				else return EIO;
			}
			return 0;
			}
			catch (RemoteException e){return Errors.EPERM;}
			catch (FileNotFoundException e){return Errors.ENOENT;}
			catch (IOException io){return EIO;}
		}


		//addToCacheWhenFileNotOnCache: helper function for processing the case
		//								when the path is not found on cache
		// @param[in]: path, option - open option, cachePath - the cache file's path
		// @param[out]: 0 for success, error code for failure
		public int addToCacheWhenFileNotOnCache(String path, String option, String cachePath){
			try{
				// step 1. find on server
				File newFileOnCache = new File(cachePath);
				// call retrieve to fill the file with server's content
				int result = retrieve(path, option, newFileOnCache);
				if (result != 0) return result;
				
				// step 2. add the new file on cache and createNewFile
				String mode;
				if (option == "READ") mode = "r";
				else mode = "rw";
				CacheFile fileOnCache = new CacheFile(newFileOnCache, cachePath, 1, server.getVersion(path), mode);
				if (cache.addCache(fileOnCache) < 0){ 
					writeLock.unlock();
					return Errors.ENOENT;
				}
				return 0;
			}
			catch (RemoteException remote){return Errors.EPERM;}
		}

		//addToCacheWhenFileOnCache: helper function for processing the case
		//							when the path is found on cache
		// @param[in]: path, option - open option, cachePath - the cache file's path
		// @param[out]: 0 for success, error code for failure
		public int addToCacheWhenFileOnCache(String path, String option, String cachePath){
		try{
			// step 1. find on cache
			CacheFile oldCacheFile = cache.findCacheFile(cachePath);
			int version = oldCacheFile.getVersion();
			oldCacheFile.turnOn();
			
			int versionServer = server.getVersion(path);
			// case 1. if there is no version difference, we just need to update the LRU
			if (version == versionServer){
				cache.updateCache(path, (long)0);
				return 0;
			}

			// case 2. if there exists a version difference
			// step 2. we delete the cache file from cache
			if (cache.deleteCache(cachePath) < 0) return Errors.ENOENT;

			// step 3. we make a new cache file, fill with server's content, and insert in cache
			File newFile = new File (cachePath);
			// call helper function to fill cache file
			int result = updateFromServer(path, option, newFile);
			if (result != 0) return result;

			String mode;
			if (option == "READ") mode = "r";
			else mode = "rw";
			CacheFile newCacheFile = new CacheFile (newFile, cachePath, oldCacheFile.getOpen(), versionServer, mode);
			if (cache.addCache(newCacheFile) < 0){ 
				writeLock.unlock();
				return Errors.ENOENT;
			}			
			return 0;
			}
			catch (RemoteException remote){return Errors.EPERM;}		
		}
		
		// open: handling open request
		// if the file is a read only file and does not exist on cache,
		//		we will add one cache file into the cache
		// if the file is a read only file and exist on cache,
		//		we will increment the cache file reference by 1
		// if the file is not read only,
		//		we will add a local copy into cache, 
		//		increment the cache file reference by 1 if it exists on cache
		//		or add a cache file into cache if it does not exist on cache
		// @param[in]: path, o - open option
		// @param[out]: 0 for success, error code for failure
		public int open( String path, OpenOption o ) {
			// step 1. create a file
			int curr;
			String mode;
			String cachePath = cacheFolder + path; // cache path for cache file: cache folder / path
			String option; // process open option into string
			if (o == OpenOption.CREATE_NEW) option = "CREATE_NEW";
			else if (o == OpenOption.CREATE) option = "CREATE";
			else if (o == OpenOption.READ) option = "READ";
			else if (o == OpenOption.WRITE) option = "WRITE";
			else {return Errors.EINVAL;}
			File cacheFile = new File(cachePath);
			synchronized(this) {curr = fd++;}

			// if the file is read only, record into read only hashmap
			if (o == OpenOption.READ) readOnlyFileDict.put(cachePath, cacheFile);
			
			if (cache.findCacheFile(cachePath) != null){
				// case 1: if we have this file in cache
				synchronized(cache){
					int result = addToCacheWhenFileOnCache(path, option, cachePath);
					if (result != 0) return result;
				}	
			}
			else{
				// case 2: if we don't have the file in cache
				File file = new File(cachePath);
				// check if we can find directories that the file should be in
				File parent = file.getParentFile();
				while (parent != null && !parent.exists()){
					parent.mkdir();
					parent = parent.getParentFile();
				}
				synchronized(cache){
					int result = addToCacheWhenFileNotOnCache(path, option, cachePath);
					if (result != 0) return result;
				}
			}

			// step 3. make local temp file, local raf
			if (o != OpenOption.READ){
				int success = getLocal(path, curr, cachePath, o);
				if (success != 0) return success;	
			}
			else{
				// if read only, we do not need to make local copies
				// instead we just make a raf of the cache file
				fileDict.put(curr, cacheFile);
				int success = getRAF(cacheFile, o, curr);
				if (success != 0) return success;
			}
			return curr;
		}

		// removeIntegerAtBack: helper function for getting rid of the fd at local path
		// @param[in]: path - the path for local files
		// @param[out]: a path that does not have any folder, or fd
		public String removeIntegerAtBack (String path){
			StringBuilder sb = new StringBuilder(path);
			while (!((sb.charAt(sb.length() - 1)) == ('@'))){
				sb.setLength(sb.length() - 1);
			}
			if (sb.length() == 0) return path;
			sb.setLength(sb.length() - 1);
			return sb.toString();
		}
		
		// updateOnServer: helper function for updating the local file to server file
		// 				   using chunking
		// @param[in]: cachePath - the path for cache file
		//				raf - the random access file for the local file
		// @param[out]: 0 for success, error code for failure
		public int updateOnServer(String serverPath, RandomAccessFile raf){
		try{
			int rv = 0;				
			File serverFile = new File (serverPath);
			if (!(!serverFile.exists() && readOnlyRAFDict.containsKey(fd))){
				// if the file exists on server, do chunking
				// first pass: read from 0
				int len = (chunk <= (int) raf.length()) ? chunk : (int) raf.length();
				byte [] byteArray = new byte[len];
				raf.seek(0);
				raf.read(byteArray);
				RemoteFile rf = new RemoteFile(byteArray, Integer.toString(0));
				int respond = server.close(rf, serverPath);
				if (respond == -1) return EIO;
				rv = respond;
				while (rv < (int)raf.length()){
					// if more bytes need to be sent
					int from = rv;
					len = (from + chunk <= (int)raf.length()) ? chunk : (int)raf.length() - from;
					byteArray = new byte[len];
					raf.seek(from);
					raf.read(byteArray);
					rf = new RemoteFile(byteArray, Integer.toString(from));
					respond = server.close(rf, serverPath);
					if (respond == -1) return EIO;
					rv += respond;
				}
			}
			return 0;
		}
		catch (RemoteException remote){return Errors.EPERM;}
		catch(IOException io){return EIO;}
		}

		// updateOnCache: the helper function for updating the local file to cache file
		// 				   using chunking
		// @param[in]: cachePath - the path for cache file
		//				raf - the random access file for the local file
		// @param[out]: the cache file
		public File updateOnCache(String cachePath, RandomAccessFile raf){
		try{
			File newFile = new File (cachePath);
			if (!newFile.isDirectory()){
				// first pass: read from 0
				RandomAccessFile rafTemp = new RandomAccessFile(newFile, "rw");
				int rv = 0;
				int len = (chunk <= (int) raf.length()) ? chunk : (int) raf.length();
				byte[] byteArray = new byte[len];
				raf.seek(0);
				raf.read(byteArray);
				rafTemp.seek(0);
				rafTemp.write(byteArray);
				rafTemp.close();
				rv += byteArray.length;

				while (rv < (int)raf.length()){
					// if more bytes need to be written
					int from = rv;
					len = (from + chunk <= (int)raf.length()) ? chunk : (int)raf.length() - from;
					rafTemp = new RandomAccessFile(newFile, "rw");
					byteArray = new byte[len];
					raf.seek(from);
					raf.read(byteArray);
					rafTemp.seek(from);
					rafTemp.write(byteArray);
					rafTemp.close();
					rv += byteArray.length;
				}
			}
			return newFile;
		}
		catch(FileNotFoundException e){return null;}
		catch(IOException io){return null;}
		}

		// close: handling close request
		// @param[in]: fd - file descriptor of the file we want to close
		// @param[out]: 0 for success, error code for failure
		public int close( int fd ) {
			try{
				File file = fileDict.get(fd);
				// error handling
				if (!fileDict.containsKey(fd)){return Errors.EBADF;}
				if (!file.exists()){return Errors.ENOENT;}
				if (file.isDirectory()){fileDict.remove(fd);return 0;}
				RandomAccessFile raf = fdDict.get(fd);
				String localPath = file.getPath();
				// step 1. remove from dicts, delete local copy from cache, decrement the cache file's reference
				fdDict.remove(fd);
				fileDict.remove(fd);
				if (readOnlyRAFDict.containsKey(fd)){
					synchronized(cache){
						// if this is a read only file, we just need to decrement the cache file
						// no need to update cache or server
						CacheFile oldCacheFile = cache.findCacheFile(localPath);
						oldCacheFile.turnOff();
						raf.close();
						return 0;
					}
				}
				String cachePath = removeIntegerAtBack(localPath);
				synchronized(cache){if (cache.deleteCache(localPath) < 0){return Errors.ENOENT;}}

				// step 2. update on server
				String serverPath = cachePath.replace(cacheFolder, "");
				writeLock.lock();
				int result = updateOnServer(serverPath, raf);
				if (result < 0) {return EIO;}
				writeLock.unlock();

				// step 3. update on cache
				CacheFile oldCacheFile = cache.findCacheFile(cachePath);
				oldCacheFile.turnOff();
				// step 3.1 delete the old cache file
				synchronized(cache){if (cache.deleteCache(cachePath) < 0) return Errors.ENOENT;}
				// step 3.2. make a new cache file
				File newFile = updateOnCache(cachePath, raf);
				if (newFile == null){return EIO;}
				synchronized(cache){
					CacheFile newCacheFile = new CacheFile (newFile, oldCacheFile.getPath(), oldCacheFile.getOpen(), server.getVersion(serverPath), oldCacheFile.getOption());
					if (cache.addCache(newCacheFile)< 0){return Errors.ENOENT;}
				}				
				raf.close();
				return 0;
			}
			catch(NullPointerException NullPointer){return Errors.EBADF;}
			catch(IOException io){return EIO;}
		}

		// write: handling write requests
		// @param[in]: fd - file descriptor of the file, but - the byte we need to wrie
		// @param[out]: number of bytes written on the file on success, error code on failure
		public synchronized long write( int fd, byte[] buf ) {
			try{
				// step 1. get RandomAccessFile
				File file = fileDict.get(fd);
				long oldLength = file.length();
				// error handling
				if (!fileDict.containsKey(fd)){return Errors.EBADF;}
				if (!file.exists()){return Errors.ENOENT;}
				if (file.isDirectory()){return Errors.EISDIR;}
				if (readOnlyRAFDict.containsKey(fd)){return Errors.EPERM;}

				// step 2. write on the file
				RandomAccessFile raf = fdDict.get(fd);
				raf.write(buf);

				// step 3. update cache LRU for both the cache file and the local file
				long newLength = file.length();
				String localPath = file.getPath();
				String cachePath = removeIntegerAtBack(localPath);

				synchronized(cache){
					CacheFile oldCacheFile = cache.findCacheFile(cachePath);
					if (cache.updateCache(cachePath, (long)0) < 0){return Errors.ENOENT;}
					CacheFile oldLocalFile = cache.findCacheFile(localPath);
					if (cache.updateCache(localPath, newLength - oldLength) < 0){return Errors.ENOENT;}
				}
				return buf.length;
			}
			catch(NullPointerException NullPointer){return Errors.EBADF;}
			catch(IOException io){
				if (io.getMessage().contains("descriptor")){return Errors.EBADF;}
				if (io.getMessage().contains("Permission")){return Errors.EPERM;}
				if (io.getMessage().contains("directory")){return Errors.EISDIR;}
				return EIO;
			}
		}

		// read: handling read requests
		// @param[in]: fd - file descriptor of the file, but - the byte we need to wrie
		// @param[out]: number of bytes read on the file on success, error code on failure
		public synchronized long read( int fd, byte[] buf ) {
			try{
				// get RandomAccessFile
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){return Errors.EBADF;}
				if (!file.exists()){return Errors.ENOENT;}
				if (file.isDirectory()){return Errors.EISDIR;}

				// step 2. update cache LRU
				String localPath = file.getPath();

				// update cache file
				if (!readOnlyRAFDict.containsKey(fd)){
					// case 1. not a read only file, also update local copy file
					String cachePath = removeIntegerAtBack(localPath);
					synchronized(cache){
						if (cache.updateCache(cachePath, (long)0) < 0){return Errors.ENOENT;}
						if (cache.updateCache(localPath, (long)0) < 0){return Errors.ENOENT;}
					}
				}
				else{
					// case 2. read only, just update cache file
					synchronized(cache){
						if (cache.updateCache(localPath, (long)0) < 0){return Errors.ENOENT;}
					}
				}

				// step 3. read on file
				RandomAccessFile raf = fdDict.get(fd);
				// if the cursor is already on the EOF
				if (raf.getFilePointer() == raf.length()){return 0;}
				// else keep reading
				long result = (long)raf.read(buf);
				// if the cursor is on the EOF
				if (raf.getFilePointer() == raf.length()){return result;}
				// if the read result is negative, an error has occurred
				if (result < 0){return Errors.EPERM;}
				return result;
			}
			catch(NullPointerException NullPointer){ return Errors.EBADF;}
			catch(IOException io){
				if (io.getMessage().contains("descriptor")){return Errors.EBADF;}
				if (io.getMessage().contains("Permission")){return Errors.EPERM;}
				if (io.getMessage().contains("directory")){return Errors.EISDIR;}
				return Errors.EPERM;
			}
		}

		// lseek: handling lseek requests
		// @param[in]: fd, pos - position to change to, o - lseek option indicating start, end, or current of the file
		// @param[out]: the new position relative to start of file on success, error code on failure
		public synchronized long lseek( int fd, long pos, LseekOption o ) {
			// get RandomAccessFile
			try{
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){return Errors.EBADF;}
				if (!file.exists()){return Errors.ENOENT;}
				if (file.isDirectory()){return Errors.EISDIR;}
				if (pos < 0){return Errors.EINVAL;}
				
				// step 2. update cache LRU
				String localPath = file.getPath();

				// update cache file
				// if not a read only file, also update local copy file
				if (!readOnlyRAFDict.containsKey(fd)){
					String cachePath = removeIntegerAtBack(localPath);
					synchronized(cache){
						if (cache.updateCache(cachePath, (long)0) < 0){return Errors.ENOENT;}
						if (cache.updateCache(localPath, (long)0) < 0){return Errors.ENOENT;}
					}
				}
				else{
					synchronized(cache){
						if (cache.updateCache(localPath, (long)0) < 0){return Errors.ENOENT;}
					}
				}
				
				// assign the pointer with the lseek option
				RandomAccessFile raf = fdDict.get(fd);
				long pointer = raf.getFilePointer();
				if (o == LseekOption.FROM_END){pointer = raf.length() + pos;}
				else if (o == LseekOption.FROM_START){pointer = pos;}
				else if (o == LseekOption.FROM_CURRENT){pointer = pointer + pos;}
				if (pointer < 0){return Errors.EINVAL;}
				raf.seek(pointer);
				return pointer;
			}
			catch(NullPointerException NullPointer){return Errors.EBADF;}
			catch(IOException io){return EIO;}
		}

		// unlink: handling unlink requests
		// @param[in]: path
		// @param[out]: 0 on success, error code on failure
		public int unlink( String path ) {
			try{
				// delete on server side
				boolean success1 = server.unlink(path);

				// delete on cache side if the file is closed
				CacheFile cacheFile = cache.findCacheFile(cacheFolder + path);
				if (cacheFile != null && cacheFile.getOpen() < 1){
					cache.deleteCache(cacheFolder + path);
				}

				if (success1){return 0;}
				else {return Errors.ENOENT;}
				
			}
			catch(NullPointerException NullPointer){return Errors.EBADF;}
			catch(SecurityException security){return Errors.EPERM;}
			catch (RemoteException remote){return Errors.EPERM;}
		}

		// clientdone: end session for a particular client
		public void clientdone() {
			return;
		}

	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
        String hostIp = args[0];
        int port = Integer.parseInt(args[1]);
		RMIInterface stub;
		System.out.println("Hello World");

		cacheFolder = args[2] + "/";
		capacity = Integer.parseInt(args[3]);
 
		System.out.println("the cache folder is: "+ cacheFolder);
		System.out.println("//" + hostIp + ":" + port + "/Server");
		
        try{
			cache = new Cache(capacity);
			server = (RMIInterface)Naming.lookup("//" + hostIp + ":" + port + "/RMIInterface");

			(new RPCreceiver(new FileHandlingFactory())).run();

        } catch (RemoteException e){
            System.err.println("Unable to locate registry or unable to call RPC sayHello");
            e.printStackTrace();
            System.exit(1);
        } catch (NotBoundException e){
            System.err.println("MyInterface not found");
            e.printStackTrace();
            System.exit(1);
        }
    }

}

