/* the interface for server */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.nio.file.*;
import java.io.*;

public interface RMIInterface extends Remote{
    boolean sameRoot(File file) throws RemoteException;
    RemoteFile open(String option, String path, Integer from) throws RemoteException;
    RemoteFile update(String path, String option, Integer from) throws RemoteException;
    int close(RemoteFile rf, String path) throws RemoteException;
    int getVersion(String path) throws RemoteException;
    boolean unlink(String path) throws RemoteException;
}
