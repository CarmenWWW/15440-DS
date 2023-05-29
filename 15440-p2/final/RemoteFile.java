/* protocol for transmitting between server and proxy
 * file: holding byte array for read / write
 * errorCode: hold any error or any extra parameter
*/

import java.io.*;

public class RemoteFile implements Serializable{
    public byte[] file;
    public String errorCode;

    // generate a remote file
    public RemoteFile (byte[] file, String errorCode){
        this.file = file;
        this.errorCode = errorCode;
    }
    // get the error code / extra information
    public String getErrorCode(){
        return this.errorCode;
    }
    // get the byte array
    public byte[] getFile(){
        return this.file;
    }
}
