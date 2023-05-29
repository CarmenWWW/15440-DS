import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.io.File;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;


public class Helper implements Serializable{
    public static ProjectLib PL;

    /**
     * serialize message to bytes
    */
    public static byte[] serialize(Object object){
        try{
            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            ObjectOutputStream objectOutput = new ObjectOutputStream(byteOutput);

            objectOutput.writeObject(object);
            return byteOutput.toByteArray();
        }
        catch (IOException e){
            return null;
        }
    }


    /**
     * deserialize bytes to message
    */
    public static Object deserialize(byte[] bytes){
        try{
            ByteArrayInputStream byteInput = new ByteArrayInputStream(bytes);
            ObjectInputStream objectInput = new ObjectInputStream(byteInput);

            return objectInput.readObject();
        }
        catch (ClassNotFoundException e){
            e.printStackTrace();
            return null;
        }
        catch (IOException e){
            e.printStackTrace();
            return null;
        }
    }


    /**
     * flush content to disk
    */
    public static void write(String dest, String content){
        try{
            FileOutputStream fileOutput = new FileOutputStream(dest);
            fileOutput.write((content+"\n").getBytes());
            fileOutput.flush();
            fileOutput.close();
        }
        catch (IOException e){
            e.printStackTrace();
        }
        PL.fsync();
    }


    /**
     * read string contents from file
    */
    public static List<String> read(String dest){
        List<String> result = new ArrayList<String>();
        File file = new File(dest);
        if (file.exists()){
            try{
                Scanner scanner = new Scanner(file);
                while (scanner.hasNext()){
                    result.add(scanner.next());
                }
                scanner.close();
            }
            catch (FileNotFoundException e){
                e.printStackTrace();
            }
        }
        return result;
    }
}
