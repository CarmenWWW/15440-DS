import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RMIInterface extends Remote{
    int dummy() throws RemoteException;
    RemoteFile open(String option, String path) throws RemoteException;
    int close(RemoteFile rf, String path) throws RemoteException;
    boolean unlink(String path) throws RemoteException;
}
