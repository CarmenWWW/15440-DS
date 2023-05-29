/* the type of items for storing in cache linked list
 * file: the file that is stored in linked list
 * path: the path of the file
 * isOpen: number of times the file is referenced; 0 if closed
 * version: version of the current file
 * option: open option of the file
*/

import java.io.*;

public class CacheFile {
    public File file;
    public String path;
    public Integer isOpen;
    public Integer version;
    public String option;

    // generate a CacheFile
    public CacheFile(File file, String path, Integer reference, Integer version, String option){
        this.file = file;
        this.path = path;
        this.isOpen = reference;
        this.version = version;
        this.option = option;
    }

    // get the file
    public File getFile(){
        return this.file;
    }

    // get the path
    public String getPath(){
        return this.path;
    }

    // get the number of reference
    public Integer getOpen(){
        return this.isOpen;
    }

    // get the open option
    public String getOption(){
        return this.option;
    }

    // check if the file is open or not
    // true if open, false if closed
    public Boolean isOpen(){
        return isOpen > 0;
    }

    // get the version
    public Integer getVersion(){
        return this.version;
    }

    // set version to passed in parameter
    public void setVersion(Integer version){
        this.version = version;
    }

    // decrement reference number by 1
    public void turnOff(){
        this.isOpen--;
    }

    // increment reference number by 1
    public void turnOn(){
        this.isOpen++;
    }
}
