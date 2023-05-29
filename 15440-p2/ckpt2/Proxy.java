/* Sample skeleton for proxy */

import java.io.*;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.OpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Hashtable;
import java.util.Dictionary;
import java.util.Scanner;
import java.util.concurrent.*;
import javax.print.attribute.standard.Fidelity;
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
import java.io.FileOutputStream;
import java.io.OutputStream;



public class Proxy{
	private static int fd = 2000;
	private static final int EIO = -5;
	private static ConcurrentHashMap<Integer, RandomAccessFile> fdDict = new ConcurrentHashMap<Integer, RandomAccessFile>();
	private static ConcurrentHashMap<Integer, File> fileDict = new ConcurrentHashMap<Integer, File>();
	private static String cacheFolder;
	public static RMIInterface srv;

	private static class FileHandler implements FileHandling {
		
		public int open( String path, OpenOption o ) {
			try{
				System.out.println("opening");
				// create a file
				int curr;
				String mode;

				File file = new File(path);
				System.out.println("made new file");
				synchronized(this) {
					curr = fd++;
				}
				
				File newFile;
				if (file.exists()){
					System.out.println("entering exist on proxy case...");
					// case 1: if we have this file in cache
					// step 1. change pathname to [path]+[fd]
					String newPath = path + curr;
					System.out.println("getting new path..." + newPath);

					newFile = new File(newPath);
					System.out.println("made new file...");

					// step 2. copy file content
					System.out.println("entering copying...");
					Files.copy(file.toPath(), newFile.toPath());
					
					// step 3. add the file to proxy
					fileDict.put(curr, newFile);

					System.out.println("finished copying...");
				}
				else{
					System.out.println("entering not exist on proxy case...");
					// case 2: if we don't have the file in cache
					String option;
					if (o == OpenOption.CREATE_NEW) option = "CREATE_NEW";
					else if (o == OpenOption.CREATE) option = "CREATE";
					else if (o == OpenOption.READ) option = "READ";
					else if (o == OpenOption.WRITE) option = "WRITE";
					else {
						System.out.println("open: invalid parameter");
						return Errors.EINVAL;
					}
					
					// step 1. find on server
					RemoteFile rf = srv.open(option, path);
					System.out.println("getting the remote file from server...");

					String error = rf.getErrorCode();
					if (error != ""){
						System.out.println("entering error checking...");
						// case 2.1: if there is an error on server
						if (error == "EINVAL") return Errors.EINVAL;
						else if (error == "EEXIST") return Errors.EEXIST;
						else if (error == "ENOENT") return Errors.ENOENT;
						else if (error == "EISDIR") return Errors.EISDIR;
						else if (error == "EPERM") return Errors.EPERM;
						else return EIO;
					}
					else{
						System.out.println("no error...");
						// step 2.1. fetch the file from server
						String pathOnCache = cacheFolder + path;
						File newFileOnCache = new File(pathOnCache);
						byte[] srcByteArray = rf.getFile();

						// step 2.2. write the file to proxy's file on outputstream
						OutputStream os = new FileOutputStream(file);
						// Starting writing the bytes in it
						os.write(srcByteArray);
						// Display message onconsole for successful
						// execution
						System.out.println("Successfully" + srcByteArray.length + " byte inserted");
						// Close the file connections
						os.close();

						// step 3. create new file
						boolean success = newFileOnCache.createNewFile();
						if (!success){
							System.out.println("failed in making new file");
							return Errors.EPERM;
						}
						
						// step 4. make a temp file; copy the file to proxy
						String pathOnProxy = path + fd;
						newFile = new File(pathOnProxy);
						Files.copy(newFileOnCache.toPath(), newFile.toPath());
						fileDict.put(curr, newFile);
					}
					
				}
				

				System.out.println("setting up raf");
				// make raf
				// newFile: the file we keep on proxy
				if (o == OpenOption.READ) {
					if (!newFile.isDirectory()){
						RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
						fdDict.put(curr, raf);
					}	
				}
				else if (o == OpenOption.WRITE){
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					fdDict.put(curr, raf);
				}
				else if (o == OpenOption.CREATE){
					if (!newFile.exists()){
						newFile.createNewFile();
					}
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					fdDict.put(curr, raf);
				}
				else if (o == OpenOption.CREATE_NEW){
					newFile.createNewFile();
					RandomAccessFile raf = new RandomAccessFile(newFile, "rw");
					fdDict.put(curr, raf);
				}
				else{
					System.out.println("open: invalid parameter");
					return Errors.EINVAL;
				}
				
				System.out.println("finishing open. Bye!!!");
				return curr;
			}
			catch (FileNotFoundException fileNotFound){
				System.out.println("open: file not found");
				return Errors.ENOENT;
			}
			catch (IOException io){
				System.out.println("open: io exception");
				io.printStackTrace();
				return EIO;
			}
		}

		public int close( int fd ) {
			System.out.println("closing");
			// get RandomAccessFile
			try{
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){ // bad file descriptor
					System.out.println("close: bad file descriptor: " + fd);
					return Errors.EBADF;
				}
				if (!file.exists()){
					System.out.println("close: file not exist");
					return Errors.ENOENT;
				}
				if (file.isDirectory()){
					System.out.println("close: is directory");
					return Errors.EISDIR;
				}
				RandomAccessFile raf = fdDict.get(fd);
				System.out.println("close: got fd: " + fd);
				
				// update on server side
				RemoteFile rf = new RemoteFile(Files.readAllBytes(file.toPath()), "".getBytes());
				int result = srv.close(rf, file.getAbsolutePath());

				// remove from dicts
				fdDict.remove(fd);
				fileDict.remove(fd);

				// close the file
				raf.close();
				return result;
			}

			catch(NullPointerException NullPointer){ // bad file descriptor
				System.out.println("close: bad file descriptor");
				return Errors.EBADF;
			}
			catch(IOException io){
				System.out.println("close: io exception");
				io.printStackTrace();
				return EIO;
			}
		}

		public long write( int fd, byte[] buf ) {
			System.out.println("writing");
			// get RandomAccessFile
			try{
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){ // bad file descriptor
					System.out.println("write: bad file descriptor: " + fd);
					return Errors.EBADF;
				}
				if (!file.exists()){
					System.out.println("write: file not exist");
					return Errors.ENOENT;
				}
				if (file.isDirectory()){
					System.out.println("write: is directory");
					return Errors.EISDIR;
				}
				RandomAccessFile raf = fdDict.get(fd);

				// write on the file
				System.out.println("write: got file with fd: " + fd);
				raf.write(buf);
				System.out.println("finished writing");
				return buf.length;
			}
			catch(NullPointerException NullPointer){ // bad file descriptor
				System.out.println("write: null pointer");
				return Errors.EBADF;
			}
			catch(IOException io){
				System.out.println("write: io exception");
				if (io.getMessage().contains("descriptor")){return Errors.EBADF;}
				if (io.getMessage().contains("Permission")){return Errors.EPERM;}
				if (io.getMessage().contains("directory")){return Errors.EISDIR;}
				io.printStackTrace();
				return EIO;
			}
		}

		public long read( int fd, byte[] buf ) {
			System.out.println("reading");
			try{
				// get RandomAccessFile
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){ // bad file descriptor
					System.out.println("read: bad file descriptor: " + fd);
					return Errors.EBADF;
				}
				if (!file.exists()){
					System.out.println("read: file does not exist");
					return Errors.ENOENT;
				}
				if (file.isDirectory()){
					System.out.println("read: file is a directory");
					return Errors.EISDIR;
				}
				RandomAccessFile raf = fdDict.get(fd);

				// read on the file
				// if the cursor is already on the EOF
				if (raf.getFilePointer() == raf.length()){
					System.out.println("read: already at EOF ");
					return 0;
				}
				// else keep reading
				long result = (long)raf.read(buf);

				// if the cursor is on the EOF
				if (raf.getFilePointer() == raf.length()){
					System.out.println("read: after read, cursor at EOF ");
					return result;
				}
				
				// if the read result is negative, an error has occurred
				if (result < 0){
					System.out.println("read: result is negative ");
					return Errors.EPERM;
				}

				return result;
			}
			catch(NullPointerException NullPointer){ // bad file descriptor
				System.out.println("read null pointer");
				return Errors.EBADF;
			}
			catch(IOException io){
				System.out.println("read io");
				if (io.getMessage().contains("descriptor")){return Errors.EBADF;}
				if (io.getMessage().contains("Permission")){return Errors.EPERM;}
				if (io.getMessage().contains("directory")){return Errors.EISDIR;}
				io.printStackTrace();
				return Errors.EPERM;
			}
		}

		public long lseek( int fd, long pos, LseekOption o ) {
			System.out.println("seeking");
			// get RandomAccessFile
			try{
				File file = fileDict.get(fd);
				if (!fileDict.containsKey(fd)){ // bad file descriptor
					System.out.println("seek: bad file descriptor: " + fd);
					return Errors.EBADF;
				}
				if (!file.exists()){
					System.out.println("seek: file does not exist");
					return Errors.ENOENT;
				}
				if (file.isDirectory()){
					System.out.println("seek: file is a directory");
					return Errors.EISDIR;
				}
				if (pos < 0){
					System.out.println("seek: invalid parameter");
					return Errors.EINVAL;
				}
				RandomAccessFile raf = fdDict.get(fd);

				// assign the pointer with the lseek option
				long pointer = raf.getFilePointer();
				if (o == LseekOption.FROM_END){pointer = raf.length() + pos;}
				else if (o == LseekOption.FROM_START){pointer = pos;}
				else if (o == LseekOption.FROM_CURRENT){pointer = pointer + pos;}
				if (pointer < 0){
					return Errors.EINVAL;
				}
				raf.seek(pointer);
				return pointer;
			}
			catch(NullPointerException NullPointer){ // bad file descriptor
				System.out.println("seek: null pointer");
				return Errors.EBADF;
			}
			catch(IOException io){
				System.out.println("seek: io exception");
				io.printStackTrace();
				return EIO;
			}
		}

		public int unlink( String path ) {
			System.out.println("unlinking");
			try{
				File file = new File(path);
				if (!file.exists()){
					System.out.println("unlink: file does not exist");
					return Errors.ENOENT;
				}
				if (file.isDirectory()){
					System.out.println("unlink: file is not a file");
					return Errors.EISDIR;
				}
				if (!file.isDirectory() && !file.isFile()){
					System.out.println("unlink: neither file nor directory");
					return Errors.EINVAL;
				}

				// delete on server side
				boolean success1 = srv.unlink(path);

				// delete on proxy side
				boolean success = file.delete();

				if (success && success1){
					return 0;
				}
				else {
					System.out.println("unlink: failed in deleting file");
					return Errors.EPERM;
				}
				
			}
			catch(NullPointerException NullPointer){ // bad file descriptor
				System.out.println("unlink: null pointer");
				return Errors.EBADF;
			}
			catch(SecurityException security){
				System.out.println("unlink: security exception");
				return Errors.EPERM;
			}
			catch (RemoteException remote){
				System.out.println("unlink: remote exception");
				return Errors.EPERM;
			}
		}

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

		cacheFolder = args[2];
		System.out.println("the cache folder is: "+ cacheFolder);
		System.out.println("//" + hostIp + ":" + port + "/RMIServer");
		
        try{
			srv = (RMIInterface)Naming.lookup("//" + hostIp + ":" + port + "/RMIInterface");
            Registry registry = LocateRegistry.getRegistry(hostIp, port);
			stub = (RMIInterface) registry.lookup("RMIInterface");
			
			srv.dummy();

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

