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


class Proxy {
	private static int fd = 2000;
	private static final int SmallestFD = 2000;
	private static final int EIO = -5;
	private static ConcurrentHashMap<Integer, RandomAccessFile> fdDict = new ConcurrentHashMap<Integer, RandomAccessFile>();
	private static ConcurrentHashMap<Integer, File> fileDict = new ConcurrentHashMap<Integer, File>();
	
	private static class FileHandler implements FileHandling {
		public int open( String path, OpenOption o ) {
			System.out.println("opening");
			try{
				// create a file
				int curr;
				String mode;
				File file = new File(path);
				if (file.exists() && !file.isDirectory() && !file.isFile()){
					return Errors.EINVAL;
				}
				if (file.exists() && (o == OpenOption.CREATE_NEW)) {
					return Errors.EEXIST;
				}
				if (!file.exists() && (o == OpenOption.READ || o == OpenOption.WRITE)) {
					return Errors.ENOENT;
				}
				if (file.isDirectory() && (o != OpenOption.READ)) {
					return Errors.EISDIR;
				}
				
				synchronized(this) {
					curr = fd++;
				}

				RandomAccessFile raf;
				if (o == OpenOption.READ) {
					if (!file.isDirectory()){
						raf = new RandomAccessFile(file, "r");
						fdDict.put(curr, raf); 
						fileDict.put(curr, file);
					}	
					else{
						fileDict.put(curr, file);
					}
				}
				else if (o == OpenOption.WRITE){
					raf = new RandomAccessFile(file, "rw");
					fdDict.put(curr, raf); 
					fileDict.put(curr, file);
				}
				else if (o == OpenOption.CREATE){
					if (!file.exists()){
						file.createNewFile();
					}
					raf = new RandomAccessFile(file, "rw");
					fdDict.put(curr, raf); 
					fileDict.put(curr, file);
				}
				else if (o == OpenOption.CREATE_NEW){
					file.createNewFile();
					raf = new RandomAccessFile(file, "rw");
					fdDict.put(curr, raf); 
					fileDict.put(curr, file);
				}
				else{
					System.out.println("open: invalid parameter");
					return Errors.EINVAL;
				}

				
				return curr;
			}
			catch(IllegalArgumentException illegal){ 
				// if the mode argument is not equal to one of "r", "rw", "rws", or "rwd"
				System.out.println("open: illegal argument");
				return Errors.EINVAL;
			}
			catch(SecurityException security){ 
				// a security manager denies access to file
				System.out.println("open: security exception");
				return Errors.EPERM;
			}
			catch(IOException io){
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
				
				
				// remove from dicts
				fdDict.remove(fd);
				fileDict.remove(fd);

				// close the file
				raf.close();
				return 0;
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

				boolean success = file.delete();

				if (success){
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
				System.out.println("security exception");
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
		System.out.println("Hello World");
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

