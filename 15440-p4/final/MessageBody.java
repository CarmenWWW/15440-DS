import java.io.Serializable;
import java.util.LinkedList;


public class MessageBody extends ProjectLib.Message{
    private static final long serialVersionUID = 1L;
    public String fileName;
    public byte[] img;
    public LinkedList<String> imgs = new LinkedList<>();
    public boolean vote;

    /**
     * message type:
     * 1: server ask user to vote
     * 2: user vote
     * 3: server broadcast quorum
     * 4: user ack
     */
    public int type = 1;


    /**
     * constructor, server to user
     */
    public MessageBody(String fileName, byte[] img, String addr, String imgNew, int type){
        super(addr, new byte[1]);
        this.fileName = fileName;
        this.img = img;
        //this.addr = addr;
        this.imgs.add(imgNew);
        this.type = type;
    }

    /**
     * constructor, user to server
    */
    public MessageBody(String fileName, String addr, boolean vote, int type){
        super(addr, new byte[1]);
        this.fileName = fileName;
        //this.addr = addr;
        this.vote = vote;
        this.type = type;
    }


    /**
     * add the new image to current image set
     */
    public void addImg(String img){
        if (!imgs.contains(img)) this.imgs.add(img);
    }
}