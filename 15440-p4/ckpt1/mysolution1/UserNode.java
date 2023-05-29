import java.io.*;
import java.util.*;

/* Skeleton code for UserNode */

public class UserNode implements ProjectLib.MessageHandling {
	public static String myId;
	private static String myName;
	private static ProjectLib PL;
	private static HashMap<String, Set<String>> duplicateDict = new HashMap<>();
	private static HashSet<String> noSet = new HashSet<>();



	public UserNode( String id ) {
		myId = id;
	}


	public boolean voteResult(MessageBody mBody){
		String name = mBody.fileName;
		String userNode = mBody.addr;
		//System.out.println("User Node user: " + userNode);
		// if (!userNode.equals(myId)){
		// 	return false;
		// }
		if (duplicateDict.containsKey(name)){
			return false;
		}

		byte[] img = mBody.img;
		LinkedList<String> imgs = mBody.imgs;
		String[] array = imgs.toArray(new String[imgs.size()]);

		// ask user
		boolean vote = PL.askUser(img, array);
		// vote is negative
		if (vote == false){return false;}

		// vote is positive
		// check if there is another vote with the same image but said no
		// 1. check noSet -- if the image in collage is disapproved before
		// 2. check duplicateDict -- if the image is seen before
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

	public void sendMessage(int type, String filename, boolean result){
        MessageBody mBody = new MessageBody(filename, "Server", result, type);
        byte[] body = Helper.serialize(mBody);
        ProjectLib.Message msg = new ProjectLib.Message("Server", body);
        PL.sendMessage(mBody);
    }


	public void askVote(MessageBody mBody){
		System.out.println("UserNode: " + myId + ": asking vote ");
		String name = mBody.fileName;
		LinkedList<String> imgs = mBody.imgs;

		StringBuffer content = new StringBuffer();
		content.append(name);
		for (String img : imgs) content.append(";" + img);

		System.out.println("UserNode: " + myId + ": finished appending img" + content.toString());
		if (voteResult(mBody) == false){
			System.out.println("UserNode: " + myId + ": voted no");
			// record result
			Helper.write(myName, "Vote;no;" + content.toString());
			// send vote to server
			sendMessage(2, name, false);
		}
		else{
			// record result
			System.out.println("UserNode: " + myId + ": voted yes");
			Helper.write(myName, "Vote;yes;" + content.toString());
			// lock images
			HashSet<String> duplicate = new HashSet<String>();
			for (String img : imgs){
				duplicate.add(img);
			}
			duplicateDict.put(name, duplicate);

			// send vote to server
			sendMessage(2, name, true);
		}
	}

	/**
     * process after a decision from server is received
     * 
     */
	public void decide(MessageBody mBody){
		System.out.println("UserNode: " + myId + ": deciding " + mBody.vote);
		String name = mBody.fileName;
		boolean vote = mBody.vote;

		// the images are not locked
		if (duplicateDict.containsKey(name)){
			Helper.write(myName, "Decision;" + vote + ";" + name);

			if (vote){
				// remove images from working directory
				for (String img : duplicateDict.get(name)){
					File file = new File(img);
					noSet.add(img);
					file.delete();
				}
			}

			// unlock images
			duplicateDict.remove(name);
		}
		sendMessage(4, name, vote);
	}

	public boolean deliverMessage(ProjectLib.Message msg ) {
	try{
		System.out.println("UserNode: " +  myId + ": Got message from " + msg.addr );
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
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		UserNode UN = new UserNode(args[1]);
		PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );
		Helper.PL = PL;
		Collage.PL = PL;

		myName = myId + "_log";
		
		// ProjectLib.Message msg = new ProjectLib.Message( "Server", "hello".getBytes() );
		// System.out.println("UserNode: " +  args[1] + ": Sending message to " + msg.addr );
		// PL.sendMessage( msg );
	}
}

