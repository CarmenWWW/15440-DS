import java.io.*;

public class RemoteFile implements Serializable{
    public byte[] file;
    public byte[] errorCode;
    public RemoteFile (byte[] file, byte[] errorCode){
        file = this.file;
        errorCode = this.errorCode;
    }
    public String getErrorCode(){
        String error = new String(errorCode);
        return error;
    }
    public byte[] getFile(){
        return file;
    }
}
