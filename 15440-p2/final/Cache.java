/* handling caching
 * implemented as a linked list, 
 * with order from the most recently touched file to least recently touched file
 * elements stored as cache file, recording the file, 
 * the pathname, the number of reference, 
 * the version of the file, and the open option
*/

import java.io.*;
import java.util.LinkedList;
import java.util.RandomAccess;

public class Cache implements Serializable{
    public LinkedList<CacheFile> cacheLinkedList;
    public Integer capacity;
    public Long size;

    // construct a cache type
    // @param[in]: capacity - max number of bytes of the file that can be put into this cache
    public Cache(Integer capacity){
        this.cacheLinkedList = new LinkedList<CacheFile>();
        this.capacity = capacity;
        this.size = (long)0;
    }

    // get the cache's linked list
    public LinkedList<CacheFile> getLinkedList(){
        return this.cacheLinkedList;
    }

    // get the current size of the cache
    public long getSize(){
        return this.size;
    }
    
    // addCache: adding a new cache file into the linked list
    // first check if there already exists a cachefile with the same path name; if yes, increment that cachefile's refernce and exit
    // then check if we need eviction
    // finally insert at head
    // @param[in]: the cacheFile to add
    // @param[out]: 0 on success, error code on failure
    public int addCache(CacheFile cacheFile){
        try{
            // check if there already exists a cachefile with the same path name
            int index = 0;
            while (index < cacheLinkedList.size() && !cacheLinkedList.get(index).getPath().equals(cacheFile.getPath())){
                index++;
            }
            if (index < cacheLinkedList.size()){
                // there is a same index
                CacheFile temp = cacheLinkedList.get(index);
                temp.turnOn();
                cacheLinkedList.add(0, temp);
                return 0;
            }

            // get the current size of the cache linked list
            long sizeShouldBe = 0l;
            for (int i = 0; i < cacheLinkedList.size(); i++){
                sizeShouldBe += cacheLinkedList.get(i).getFile().length();
            }
            size = sizeShouldBe;
            long data = cacheFile.getFile().length();
            // check if we need eviction
            while (size + data > capacity){
                // find the least touched closed file
                index = cacheLinkedList.size() - 1;
                CacheFile temp = cacheLinkedList.get(index);
                while (temp.isOpen() && index >= 0){
                    index--;
                    temp = cacheLinkedList.get(index);
                }
                if (index < 0){return -1;} // did not find a possible file to evict
                CacheFile toRemove = cacheLinkedList.remove(index);
                size -= toRemove.getFile().length();
                File fileToDelete = toRemove.getFile();
                if (!fileToDelete.isDirectory()){
                    RandomAccessFile raf = new RandomAccessFile(fileToDelete, toRemove.getOption());
                    raf.close();
                }
                fileToDelete.delete();
            }
            cacheLinkedList.add(0, cacheFile);
            size += data;
            // create actual file on cache
            cacheFile.getFile().createNewFile();
            return 0;
        }
        catch (IndexOutOfBoundsException e){return -1;}
        catch (IOException e){return -1;}
    }

    // updateCache: update the LRU for an item already in cache
    // @param[in]: path, byteAdded - the difference in the original file's size and the modified file's size
    // @param[out]: 0 on success, -1 on failure
    public int updateCache(String path, Long byteAdded){
        // only for updating LRU
        int index = 0;
        while (index < cacheLinkedList.size() && !cacheLinkedList.get(index).getPath().equals(path)){
            index ++;
        }
        if (index >= cacheLinkedList.size()) return -1; // did not find the item
        // remove the original item from the linked list
        // update linked list's size
        CacheFile toUpdate = cacheLinkedList.remove(index);
        this.size -= toUpdate.getFile().length();
        this.size += (long)byteAdded;
        // add into the head of linked list, check possible evictions
        addCache(toUpdate);
        return 0;
    }

    // deleteCache: delete an item from linked list
    // @param[in]: path
    // @param[out]: 0 on success, -1 on failure
    public int deleteCache(String path){
        try{
            int index = 0;
            while (index < cacheLinkedList.size() && !cacheLinkedList.get(index).getPath().equals(path)){
                index ++;
            }
            // got the index of the file
            // update linked list and its size
            if (index >= cacheLinkedList.size()) return -1;
            CacheFile toRemove = cacheLinkedList.remove(index);
            size -= toRemove.getFile().length();
            File fileToDelete = toRemove.getFile();
            if (!fileToDelete.isDirectory()){
                RandomAccessFile raf = new RandomAccessFile(fileToDelete, toRemove.getOption());
                raf.close();
            }
            fileToDelete.delete();
            return 0;
        }
        catch (FileNotFoundException e){
            return -1;
        }
        catch (IOException e){
            return -1;
        }
    }

    // findCacheFile: find the item in linked list
    // @param[in]: path
    // @param[out]: the item on success, null on failure
    public CacheFile findCacheFile(String path){
        try{
            int index = 0;
            while (index < cacheLinkedList.size() && !cacheLinkedList.get(index).getPath().equals(path)){
                index++;
            }
            if (index >= cacheLinkedList.size()) return null;
            return cacheLinkedList.get(index);
        }
        catch (IndexOutOfBoundsException e){
            return null;
        }
    }
}
