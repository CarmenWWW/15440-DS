import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Timer;
import java.nio.file.Files;
import java.util.TimerTask;


public class Collage {
    public String fileName;
    public byte[] content;
    private String[] sources;
    public HashSet<String> userSet = new HashSet<>();
    public HashSet<String> voteSet = new HashSet<>();
    public HashSet<String> ackSet = new HashSet<>();
    public HashMap<String, MessageBody> messageDict = new HashMap<String, MessageBody>();
    public static ProjectLib PL;
    private static String diskName = "Server_log";

    // vote gathering status
    public boolean decisionMade = false;
    // decision making status
    public boolean finalDecision = false;
    // the current status
    public String status = null;

    // timer for vote message timeout
    private Timer voteTimer = new Timer();
    // timer for ack message timeout
    private Timer ackTimer = new Timer();

    /**
     * create a collage
    */
    public Collage(String filename, byte[] img, String[] sources){
        this.fileName = filename;
        this.content = img;
        this.sources = sources;
        userSet = new HashSet<>();


        // extract user info and message
        for (String source : sources){
            String user = source.split(":")[0];
            String image = source.split(":")[1];

            userSet.add(user);

            if (!messageDict.containsKey(user)){
                MessageBody msg = new MessageBody (filename, img, user, image, 1);
                messageDict.put(user, msg);
            }
            else{
                messageDict.get(user).addImg(image);
            }
        }
    }

    /**
     * get vote
    */
    public void vote(){
        for (String user : userSet){
            // byte[] body = Helper.serialize(messageDict.get(user));
            // ProjectLib.Message msg = new ProjectLib.Message(user, body);
            PL.sendMessage(messageDict.get(user));
            messageDict.remove(user);
        }
    }


    /**
     * distribute the decision of the collage to every usernode
     * 
     * @param decision true if the collage is approved, false otherwise
     */
    public void distributeDecision(boolean decision){

        for(String user : userSet){
            if(ackSet.contains(user)){// the user already acked
                continue;
            }
            MessageBody message = new MessageBody(fileName, user, decision, 3);
            // byte[] body = Helper.serialize(message);
            // ProjectLib.Message msg = new ProjectLib.Message(user,body);
            PL.sendMessage(message);
        }
        decisionMade = true;

    }

    /**
     * count in the ack received from the usernode
     * 
     * @param user id of the usernode
     * @return true if it is acknowledged by all usernodes, false otherwise
     */
    public boolean countAck(String user){

        // wrong user
        if(userSet.contains(user) == false){
            return false;
        }
        // record the ack
        ackSet.add(user);
        
        if(userSet.size() == ackSet.size()){// all ack received            
            return true;
        }
        return false;
    }

    /**
     * count in the vote received from the usernode
     * 
     * @param user id of the usernode
     * @return true if it is approved by all usernodes, false otherwise
     */
    public boolean countVote(String user){

        // wrong user
        if(userSet.contains(user) == false){
            return false;
        }
        // record the vote
        voteSet.add(user);
    
        if(userSet.size() == voteSet.size()){// all votes received
            return true;
        }
        return false;
    }

    /**
     * commit the approved collage
     * 
     * @param filename the filename of the collage
     * @param img the image contents of the collage
     */
    private static void commitCollage(String filename, byte[] img) {
        try {
            // write the images to the working directory 
            Files.write(new File(filename).toPath(), img);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * receive and handle the message from usernode
     * 
     * @param message the message received
     * @return true if the collage is done, false otherwise
     */
    public boolean getMessage(MessageBody mBody){

        String user = mBody.addr;
        boolean vote = mBody.vote;

        // ack message from user
        if(mBody.type == 4){
            if(countAck(user)==true){// all ack received and collage committed
                // stop ack timer
                ackTimer.cancel();
                // write log for the committed step
                Helper.write(diskName, "Committed;" + fileName);
                return true;
            }
            return false;
        }

        // decision distributing
        
        if(decisionMade){
            return false;
        }
        System.out.println("User : " + user);
        // vote message from user
        if(vote == true){// vote is yes
            if(countVote(user) == true){ // all votes received
                // write the files to the working directory
                commitCollage(fileName, content);

                finalDecision = true;

                // stop vote timer
                voteTimer.cancel();
                // write log
                Helper.write(diskName, "Decision;" + fileName + ";true");
                System.out.println("commiting the image");
                // distribute the decision
                distributeDecision(true);
                // start ack timer
                countAcktime();
            }
        }
        else{// vote is no

            finalDecision = false;
            voteTimer.cancel();
            Helper.write(diskName, "Decision;" + fileName + ";false");
            distributeDecision(false);
            countAcktime();
        }
        return false;
    }
    
    /**
     * count time for lost message asking for vote
     */
    public void countVotetime(){
        voteTimer.schedule(new voteTimerTask(), 2000, 2000);
    }
    
    /**
     * count time for lost message asking for ack
     */
    public void countAcktime(){
        ackTimer.schedule(new ackTimerTask(), 2000, 2000);
    }

    /* voteTimerTask class for counting vote message time */
    private class voteTimerTask extends TimerTask{

        public voteTimerTask(){
        }
    
        @Override
        public void run(){
            voteTimer.cancel();
            if(decisionMade==false){
                // if vote timeout, abort the collage
                Helper.write(diskName, "Decision;" + fileName + ";false");
                finalDecision = false;
                distributeDecision(finalDecision);
                countAcktime();
            }
        }
    } 

    /* ackTimerTask class for counting ack message time */
    private class ackTimerTask extends TimerTask{
    
        public  ackTimerTask(){
        }
    
        @Override
        public void run(){
            // if ack timeout, redistribute the decision
            distributeDecision(finalDecision);
        }
    }
}
