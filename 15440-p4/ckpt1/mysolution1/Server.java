import java.util.*;

/* Skeleton code for Server */

public class Server implements ProjectLib.CommitServing {

	private static String diskName = "Server_log";
	private static ProjectLib PL;
	private static HashMap<String, Collage> collageDict = new HashMap<String, Collage>();

    private static HashMap<String, Collage> collageCommit = new HashMap<String, Collage>();
	
	public void startCommit( String filename, byte[] img, String[] sources ) {
		System.out.println( "Server: Got request to commit "+filename );

		// collage
		StringBuffer content = new StringBuffer();
		content.append("startCommit;");
		content.append(filename);
		for (String source : sources) content.append(";" + source);
		
		// flush to disk
		String newContent = content.toString();
		Helper.write(diskName, newContent);

		// make a collage
		Collage newCollage = new Collage(filename, img, sources);
		collageDict.put(filename, newCollage);
		collageCommit.put(filename, newCollage);

		// start two phase commit procedure
		Thread t = new Thread(){
			public void run(){
				newCollage.vote();
			}
		};
		t.start();
	}


    /**
     * recommit all the collages in the commit records
     */
    public static void recommitCollage(){
		System.out.println( "Server: recommit collages ");
		Collage currCollage = null;
		
        for(String filename : collageCommit.keySet()){

			currCollage = collageCommit.get(filename);
			
            // failure before a decision is made
            if(currCollage.status.equals("startCommit")){
                // abort the collage and distribute NO decision 
                currCollage.finalDecision = false;
                currCollage.distributeDecision(false);
                currCollage.countAcktime();
			}
			
            // failure after the decision is made
            else if(currCollage.status.equals("Decision")){
                // redistribute the decision
                currCollage.distributeDecision(currCollage.finalDecision);
                currCollage.countAcktime();
            }
        }
	}
	
	/**
     * recover from failure based on the log file
     * 
     * @param logs the records in the log file
     */
    public static void recoverFailure(List<String> logs){
        System.out.println( "Server: read log ");
        if(logs == null){// no log and no collage commit yet
            return;
        }

		// final should have something here
		
        // recommit all the collages
        recommitCollage();
    }
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		Server srv = new Server();
		PL = new ProjectLib( Integer.parseInt(args[0]), srv );
		Helper.PL = PL;
		Collage.PL = PL;

		List<String> logs = Helper.read("Server_log");
        recoverFailure(logs);

		// main loop
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			MessageBody message = (MessageBody) msg;
			System.out.println("Server got message in main : " + message.fileName);
            if(collageCommit.containsKey(message.fileName) == false){
                continue;
            }
            
            Collage currCollage = collageCommit.get(message.fileName);

            if (currCollage.getMessage(message) == true){// collage committed
                collageCommit.remove(message.fileName);
            }
		}
	}
}

