import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Timer;
import java.nio.file.Files;
import java.util.TimerTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public class Collage {
    public String fileName;
    public byte[] content;
    public String[] sources;
    public HashSet<String> userSet = new HashSet<>(); // set of all users
    public HashSet<String> voteSet = new HashSet<>(); // set of votes, used for checking if all received
    public HashSet<String> ackSet = new HashSet<>();  // set of acks, used for checking if all received
    public HashMap<String, MessageBody> messageDict = new HashMap<String, MessageBody>(); // message dictionary
    public static ProjectLib PL;
    private static String diskName = "Server_log"; // name of server log

    public boolean decisionMade = false; // get vote
    public boolean finalDecision = false; // final quorum
    public String status = ""; // current status

    // timer for vote message timeout
    private Timer voteTimer = new Timer();
    // timer for ack message timeout
    private Timer ackTimer = new Timer();

    /**
     * constructor, create a collage
     * @param filename
     * @param img
     * @param sources
    */
    public Collage(String filename, byte[] img, String[] sources){
        this.fileName = filename;
        this.content = img;
        this.sources = sources;
        userSet = new HashSet<>();

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
     * get vote of each user
     */
    public void vote(){
        for (String user : userSet){
            PL.sendMessage(messageDict.get(user));
            messageDict.remove(user);
        }
    }


    /**
     * broadcast final decision
     * @param decision true if collage approved, false otherwise
     */
    public void distributeDecision(boolean decision){

        for(String user : userSet){
            if(ackSet.contains(user)){// the user already acked
                continue;
            }
            
            MessageBody message = new MessageBody(fileName, user, decision, 3);
            byte[] body = Helper.serialize(message);
            ProjectLib.Message msg = new ProjectLib.Message(user,body);
            PL.sendMessage(message);
        }
        decisionMade = true;

    }

    /**
     * count the ack received from the usernode
     * 
     * @param user user's id
     * @return true if it is acknowledged, false otherwise
     */
    public boolean countAck(String user){
        // wrong user
        if(userSet.contains(user) == false){
            return false;
        }
        // record the ack
        ackSet.add(user);
        
        if(userSet.size() == ackSet.size()){// all ack received  
            ackSet.clear();          
            return true;
        }
        return false;
    }

    /**
     * count the vote received from the usernode
     * 
     * @param user usernode's id
     * @return true if it is approved, false otherwise
     */
    public boolean countVote(String user){
        // wrong user
        if(userSet.contains(user) == false){
            return false;
        }
        // record the vote
        voteSet.add(user);
    
        if(userSet.size() == voteSet.size()){// all votes received
            voteSet.clear();
            return true;
        }
        return false;
    }

    /**
     * commit the approved collage
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
     * @param message the message received
     * @return true if the collage is done, false otherwise
     */
    public boolean getMessage(MessageBody mBody){
        String user = mBody.addr;
        boolean vote = mBody.vote;

        // ack
        if(mBody.type == 4){
            if(countAck(user)==true){
                // stop ack timer
                ackTimer.cancel();
                // write log for the committed step
                Helper.write(diskName, "Committed;" + fileName);
                return true;
            }
            return false;
        }

        // not ack, but decision already made
        if(decisionMade){
            return false;
        }
        // vote message from user
        // case 1. voted yes
        if(vote){
            if(countVote(user)){ 
                commitCollage(fileName, content);

                finalDecision = true;

                String sourceFileLine = ";";
                for (String image : sources){
                    sourceFileLine += image;
                    sourceFileLine += ";";
                }
                
                // stop vote timer
                voteTimer.cancel();
                // write log
                Helper.write(diskName, "Decision;" + fileName + ";true" + sourceFileLine);
                distributeDecision(true);
                // start ack timer
                countAcktime();
            }
        }
        // case 2. voted no
        else{
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
        voteTimer.schedule(new voteTimerTask(), 3000, 3000);
    }
    
    /**
     * count time for lost message asking for ack
     */
    public void countAcktime(){
        ackTimer.schedule(new ackTimerTask(), 3000, 3000);
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
