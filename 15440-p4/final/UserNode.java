import java.io.*;
import java.util.*;

/* Skeleton code for UserNode */

public class UserNode implements ProjectLib.MessageHandling {
	public static String myId; // id of usernode
	private static String myLog; // name of log file
	private static ProjectLib PL;
	public static HashMap<String, Set<String>> duplicateDict = new HashMap<>(); // duplicate images, do not touch before deciding
	public static HashSet<String> noSet = new HashSet<>(); // deleted image set

	/**
	 * constructor, bind the id
	 * @param id
	 */
	public UserNode( String id ) {
		myId = id;
	}


	/**
	 * get vote of this user
	 * @param mBody
	 * @return true for approval, false for denial
	 */
	public boolean voteResult(MessageBody mBody){
		String name = mBody.fileName;

		// if the vote is duplicated with another collage
		if (duplicateDict.containsKey(name)){
			return false;
		}

		byte[] img = mBody.img;
		LinkedList<String> imgs = mBody.imgs;
		String[] array = imgs.toArray(new String[imgs.size()]);

		// ask user
		boolean vote = PL.askUser(img, array);
		// denial
		if (vote == false){return false;}

		// approval
		// check if the image is already used
		// 1. check noSet -- if the image in collage is used before
		// 2. check duplicateDict -- if the image is locked
		for (String temp : imgs){
			if (noSet.contains(temp)){
				return false;
			}
			for (String tempName : duplicateDict.keySet()){
				if (duplicateDict.get(tempName).contains(temp)){
					return false;
				}
			}
		}

		// all check passed!
		return true;
	}

	/**
	 * send message to server
	 * @param type
	 * @param filename
	 * @param result
	 */
	public static void sendMessage(int type, String filename, boolean result){
        MessageBody mBody = new MessageBody(filename, "Server", result, type);
        byte[] body = Helper.serialize(mBody);
        ProjectLib.Message msg = new ProjectLib.Message("Server", body);
        PL.sendMessage(mBody);
    }


	/**
	 * check the collage
	 * @param mBody
	 */
	public void askVote(MessageBody mBody){
		String name = mBody.fileName;
		LinkedList<String> imgs = mBody.imgs;

		StringBuffer content = new StringBuffer();
		content.append(name);
		for (String img : imgs) content.append(";" + img);

		if (voteResult(mBody) == false){
			Helper.write(myLog, "Vote;no;" + content.toString());
			sendMessage(2, name, false);
		}
		else{
			Helper.write(myLog, "Vote;yes;" + content.toString());
			HashSet<String> duplicate = new HashSet<String>();
			for (String img : imgs){
				duplicate.add(img);
			}

			duplicateDict.put(name, duplicate);
			sendMessage(2, name, true);
		}
	}

	/**
     * process after a decision from server is received
     * @param mBody
     */
	public void decide(MessageBody mBody){
		String name = mBody.fileName;
		boolean vote = mBody.vote;

		if (duplicateDict.containsKey(name)){
			Helper.write(myLog, "Decision;" + vote + ";" + name);
			// approval
			if (vote){
				// remove images from working directory
				for (String img : duplicateDict.get(name)){
					File file = new File(img);
					noSet.add(img);
					file.delete();
					PL.fsync();
				}
			}
			duplicateDict.remove(name);
		}
		// send ack request
		sendMessage(4, name, vote);
	}

	/**
	 * process when receiving message from server
	 * @param msg
	 * @return
	 */
	public boolean deliverMessage(ProjectLib.Message msg ) {
	try{
		MessageBody mBody = (MessageBody)msg;
		if (mBody.type == 1){
			askVote(mBody);
		}
		else if (mBody.type == 3){
			decide(mBody);
		}
	} catch(Exception e) {
		e.printStackTrace();
	}
		return true;
	}

	/**
	 * deal with failure based on log file
	 * @param logs
	 */
	public static void doFailure(List<String> logs){
        if(logs == null){// no log and no collage commit yet
            return;
        }

		for (String log : logs){
			String[] content = log.split(";");
			String status = content[0];
			String vote = content[1];
			String filename = content[2];

			// case 1. already voted YES
			if (status.equals("Vote") && vote.equals("yes")){
				// lock included images
				Set<String> imgs = new HashSet<String>();
				for (int i = 3; i < content.length; i++){
					imgs.add(content[i]);
				}
				duplicateDict.put(filename, imgs);
			}
			// case 2. decision already made
			else if (status.equals("Decision")){
				// if voted NO and imgs still in the duplicate dict
				if (vote.equals("no") && duplicateDict.containsKey(filename)){
					duplicateDict.remove(filename);
				}
				// if voted YES
				else if (vote.equals("yes") && duplicateDict.containsKey(filename)){
					// remove all images
					for (String img : duplicateDict.get(filename)){
						File file = new File(img);
						if (file.exists()) file.delete();
						noSet.add(img);
					}
					PL.fsync();
					duplicateDict.remove(filename);
				}
			}
		}
	}
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		UserNode UN = new UserNode(args[1]);
		PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );
		Helper.PL = PL;
		Collage.PL = PL;

		myLog = myId + "_log";
		List<String> logs = Helper.read(myLog);
		doFailure(logs);
	}
}

