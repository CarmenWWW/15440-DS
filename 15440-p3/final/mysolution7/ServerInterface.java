import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ServerInterface extends Remote{
    enum Type implements Serializable{
        MASTER, FRONT, MIDDLE, NONE
    }

    class Job implements Serializable{
        private static final long serialVersionUID = 1L;
        public Type type;
        public int VMID;
    }

    public Job assign(int VMID) throws RemoteException;
    public void addRequest(Cloud.FrontEndOps.Request r) throws RemoteException;
    public Cloud.FrontEndOps.Request getRequest() throws RemoteException;
    public void removeVM(int VMID) throws RemoteException;
}
