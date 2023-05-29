import java.io.*;
import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;



public class Server extends UnicastRemoteObject implements RMIInterface{
    public static String rootDir = "";
    public static File rootFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock writeLock = readWriteLock.writeLock();
    private final Lock readLock = readWriteLock.readLock();
    private static Integer chunk = 200000;
    // versionDict: a hashmap that maps the server file path to current version number
    private static ConcurrentHashMap<String, Integer> versionDict = new ConcurrentHashMap<String, Integer>();

    public Server(int port) throws RemoteException{
        super(port);
    }

    // sameRoot: check if the file is in the same root as server directory
    // @param[in]: file
    // @param[out]: true when same root, false when not same root
    public boolean sameRoot(File file){
        File tmp;
        try{
            tmp = file.getCanonicalFile();
            while (tmp != null){
                if (rootFile.equals(tmp)) return true;
                tmp = tmp.getParentFile();
            }
            return false;
        }
        catch (IOException e){
            return false;
        }
    }

    // errorChecking: helper function for checking if the open option aligns with file status
    // @param[in]: o - open option, file - the file on server
    // @param[out]: null on no error, null+errorcode on error
    public RemoteFile errorChecking(String o, File file){
    try{
        // check if the file is in the same root
        if (!sameRoot(file)) {
            RemoteFile rf = new RemoteFile(null, "EIO");
            readLock.unlock();
            return rf;
        }
        // check if the file is valid
        if (file.exists() && !file.isDirectory() && !file.isFile()){
            RemoteFile rf = new RemoteFile(null, "EINVAL");
            readLock.unlock();
            return rf;
        }
        // check if the file exists and called by create new
        if (file.exists() && (o == "CREATE_NEW")) {
            RemoteFile rf = new RemoteFile(null, "EEXIST");
            readLock.unlock();
            return rf;
        }
        // check if the file not exist and called by read/write
        if (!file.exists() && (o == "READ" || o == "WRITE")) {
            RemoteFile rf = new RemoteFile(null, "ENOENT");
            readLock.unlock();
            return rf;
        }
        // check if attempting to read write a directory
        if (file.isDirectory() && (o != "READ")) {
            RemoteFile rf = new RemoteFile(null, "EISDIR");
            readLock.unlock();
            return rf;
        }
        // if there is no file exists and called with create new, or create
        // create the new file on server
        if ((o == "CREATE_NEW" && !file.exists()) || o == "CREATE"){
            file.createNewFile();
        }
        return null;
    }
        catch (IOException io){
            if (io.getMessage().contains("No such file or directory"))
            {
                RemoteFile rf = new RemoteFile(null, "ENOENT");
                readLock.unlock();
                return rf;
            }
            RemoteFile rf = new RemoteFile(null, "EIO");
            readLock.unlock();
            return rf;
        }
    }

    // open: function for handling open
    // when there's no cache file on cache, proxy call this function
    // generate a file if a file does not exist and the option is create or create new, and send back the content of the file
    // @param[in]: o - open option, path - path without server directory, from - the position where read start
    // @param[out]: remotefile with bytearray of content read and the length of tile on success
    //                              null and error code on failure
    public RemoteFile open(String o, String path, Integer from){
        try{
            readLock.lock();
            path = rootDir + path;
            File file = new File(path);
            if (from == 0){// first pass, do error checking
                RemoteFile temp = errorChecking(o, file);
                if (temp != null) return temp;
            }
            byte[] bytes;
            if (!file.isDirectory()){
                // do chunking when sending back the read bytes; will be called in loop by proxy if not enough bytes are sent
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                int len = (from + chunk <= (int)file.length()) ? chunk : ((int)file.length() - from);
                bytes = new byte[len];
                raf.seek(from);
                raf.read(bytes);
                raf.close();
            }
            else{bytes = new byte[0];}
            RemoteFile rf = new RemoteFile(bytes, Integer.toString((int)file.length()));
            synchronized(versionDict){
                if (!versionDict.containsKey(path)){
                    versionDict.put(path, 1);
                }
            }
            readLock.unlock();
            return rf;
        }
        catch (FileNotFoundException fileNotFound){
            RemoteFile rf = new RemoteFile(null, "ENOENT");
            readLock.unlock();
            return rf;
        }
        catch(IllegalArgumentException illegal){ 
            RemoteFile rf = new RemoteFile(null, "EINVAL");
            readLock.unlock();
            return rf;
        }
        catch(SecurityException security){ 
            RemoteFile rf = new RemoteFile(null, "EPERM");
            readLock.unlock();
            return rf;
        }
        catch(IOException io){
            RemoteFile rf = new RemoteFile(null, "EIO");
            readLock.unlock();
            return rf;
        }
    }

    // getVersion: helper function for getting the version of server file
    // @param[in]: path for server file
    // @param[out]: version number
    public int getVersion(String path){
        synchronized(versionDict){
            if (!versionDict.containsKey(rootDir + path)){
                versionDict.put(rootDir + path, 1);
                return 1;
            }
        }
        return versionDict.get(rootDir + path);
        
    }


    // close: handling close
    // will be called by proxy for server file update on close
    // param[in]: rf - the byte array to be updated + the position to start writing, path
    // param[out]: 0 on success, -1 on failure
    public int close(RemoteFile rf, String path){
        try{
            writeLock.lock();
            byte[] srcByteArray = rf.getFile();
            int from = Integer.parseInt(rf.getErrorCode());
            File destFile = new File (rootDir + path);
            if (!destFile.exists()){return -1;}

            if (!destFile.isDirectory()){
                RandomAccessFile rafTemp = new RandomAccessFile(destFile, "rw");
                rafTemp.seek(from);
                rafTemp.write(srcByteArray);
                rafTemp.close();
            }
            synchronized(versionDict){
                // update version of server file
                versionDict.put(rootDir + path, (versionDict.get(rootDir + path) + 1));
            }
            writeLock.unlock();
            return srcByteArray.length;
        }
        catch (IOException e){
            e.printStackTrace();
            writeLock.unlock();
            return -1;
        }
    }


    // update: handling open when client version does not match with server
    // send the content in server file
    // @param[in]: path - pure path, no folder related, option - open option, from - the position of write begins
    // @param[out]: byteArray + the length of server file on success, null + error code on failure
    public RemoteFile update(String path, String option, Integer from){
        try{
            readLock.lock();
            File file = new File (rootDir + path);
            byte[] bytes;
            if (file.exists()){
                // if the file exists on server
                if (!file.isDirectory()){
                    RandomAccessFile raf = new RandomAccessFile(file, "rw");
                    int len = (from + chunk <= (int)file.length()) ? chunk : ((int)file.length() - from);
                    bytes = new byte[len];
                    raf.seek(from);
                    raf.read(bytes);
                    raf.close();
                }
                else{bytes = new byte[0];}
                RemoteFile rf = new RemoteFile(bytes, Integer.toString((int)file.length()));
                readLock.unlock();
                return rf;
            }
            else{
                if (option == "CREATE_NEW"){
                    // if the file does not exist on server, but called with create new
                    file.createNewFile();
                    RemoteFile rf = new RemoteFile(new byte[0], Integer.toString((int)file.length()));
                    readLock.unlock();
                    return rf;
                }
                RemoteFile rf = new RemoteFile(null, "ENOENT"); // otherwise return error
                readLock.unlock();
                return rf;
            }
        }
        catch (FileNotFoundException fileNotFound){
            RemoteFile rf = new RemoteFile(null, "ENOENT");
            readLock.unlock();
            return rf;
        }
        catch (IOException io){
            RemoteFile rf = new RemoteFile(null, "EIO");
            readLock.unlock();
            return rf;
        }
    }

    // unlink: handling unlink, delete the file on server
    // called by proxy on unlink
    // @param[in]: the path of the file to delete, without server directory
    // @param[out]: true on success, false on failure
    public boolean unlink(String path){
        try{
            writeLock.lock();
            File destFile = new File(rootDir + path);
            if (destFile.exists()){
                writeLock.unlock();
                boolean temp = destFile.delete();
                return temp;
            }
            else{
                writeLock.unlock();
                return false;
            }
        }
        catch(SecurityException security){
            writeLock.unlock();
            return false;
        }
    }

    public static void main(String args[]){
        System.err.println("Starting server ...");
        int port = Integer.parseInt(args[0]);
        String root = args[1];
        try{
            Server Server = new Server(port);
            LocateRegistry.createRegistry(port);

            Registry registry = LocateRegistry.getRegistry(port);
            registry.bind("RMIInterface", Server);
            Server.rootDir = root + "/";
            File rootF = new File(Server.rootDir).getCanonicalFile();
            Server.rootFile = rootF;

            System.err.println("Server ready");
        }
        catch (Exception e){
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}

