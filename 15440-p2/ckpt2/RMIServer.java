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
import java.rmi.server.UnicastRemoteObject;
import java.text.MessageFormat;
import java.nio.file.Files;


public class RMIServer extends UnicastRemoteObject implements RMIInterface{

    public RMIServer(int port) throws RemoteException{
        super(port);
    }

    public int dummy(){
        System.out.println("succefully connected to server!");
        return 0;
    }

    public RemoteFile open(String o, String path){
        System.out.println("entering open on server...");
        // check if the file exists here
        try{
            File file = new File(path);
            System.out.println("made the file on server...");

            if (file.exists() && !file.isDirectory() && !file.isFile()){
                System.out.println("server open error: invalid parameter");
                RemoteFile rf = new RemoteFile(null, "EINVAL".getBytes());
                return rf;
            }
            if (file.exists() && (o == "CREATE_NEW")) {
                System.out.println("server open error: file already exist");
                RemoteFile rf = new RemoteFile(null, "EEXIST".getBytes());
                return rf;
            }
            if (!file.exists() && (o == "READ" || o == "WRITE")) {
                System.out.println("server open error: file not exist");
                RemoteFile rf = new RemoteFile(null, "ENOENT".getBytes());
                return rf;
            }
            if (file.isDirectory() && (o != "READ")) {
                System.out.println("server open error: file is a directory");
                RemoteFile rf = new RemoteFile(null, "EISDIR".getBytes());
                return rf;
            }
            
            System.out.println("passing all error check on server...");
            boolean success = file.createNewFile();
            if (success){
                RemoteFile rf = new RemoteFile(Files.readAllBytes(file.toPath()), "".getBytes());
                System.out.println("throwing back the remote file from server...");
                return rf;
            }
            else{
                RemoteFile rf = new RemoteFile(null, "EPERM".getBytes());
                return rf;
            }
        }
        catch(IllegalArgumentException illegal){ 
            // if the mode argument is not equal to one of "r", "rw", "rws", or "rwd"
            System.out.println("open: illegal argument");
            RemoteFile rf = new RemoteFile(null, "EINVAL".getBytes());
            return rf;
        }
        catch(SecurityException security){ 
            // a security manager denies access to file
            System.out.println("open: security exception");
            RemoteFile rf = new RemoteFile(null, "EPERM".getBytes());
            return rf;
        }
        catch(IOException io){
            System.out.println("io");
            io.printStackTrace();
            RemoteFile rf = new RemoteFile(null, "EIO".getBytes());
            return rf;
        }
    }

    public int close(RemoteFile rf, String path){
        System.out.println("entering close...");
        try{
            byte[] srcByteArray = rf.getFile();

            
            System.out.println("got the file on proxy side...");
            File destFile = new File (path);
            System.out.println("got the file on server side...");
            if (destFile.exists()){
                // the file is currently on server
                // update the file accordingly
                OutputStream os = new FileOutputStream(destFile);
                // Starting writing the bytes in it
                os.write(srcByteArray);
                // Display message onconsole for successful
                // execution
                System.out.println("Successfully" + srcByteArray.length + " byte inserted");
                // Close the file connections
                os.close();
                return 0;
            }
            else{
                // the file is not on server side
                return -1;
            }
        }
        catch (IOException e){
            e.printStackTrace();
            return -1;
        }
    }

    public boolean unlink(String path){
        try{
            File destFile = new File(path);
            if (destFile.exists()){
                return destFile.delete();
            }
            else{
                return false;
            }
        }
        catch(SecurityException security){
            System.out.println("security exception");
            return false;
        }
        

    }

    public static void main(String args[]){
        System.err.println("Starting server ...");
        int port = Integer.parseInt(args[0]);
        try{
            RMIServer rmiServer = new RMIServer(port);

            Registry registry = LocateRegistry.createRegistry(port);
            registry.bind("RMIInterface", rmiServer);

            System.err.println("Server ready");
        }
        catch (Exception e){
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}

