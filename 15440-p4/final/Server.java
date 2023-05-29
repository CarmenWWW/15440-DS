import java.util.*;

/* Skeleton code for Server */

public class Server implements ProjectLib.CommitServing {

	private static String diskName = "Server_log"; // name of server lig
	private static ProjectLib PL;
	private static HashMap<String, Collage> collageDict = new HashMap<String, Collage>();  // dictionary of all collages
    private static HashMap<String, Collage> collageCommit = new HashMap<String, Collage>(); // dictionary of collages committed
    
    /**
     * first stage: start committing
     * @param filename
     * @param img
     * @param sources
     */
	public void startCommit( String filename, byte[] img, String[] sources ) {
		// write to log
		StringBuffer content = new StringBuffer();
		content.append("startCommit;");
		content.append(filename);
        for (String source : sources) content.append(";" + source);
        
        // make a collage
		Collage newCollage = new Collage(filename, img, sources);
		collageDict.put(filename, newCollage);
		collageCommit.put(filename, newCollage);
		
		// flush to disk
		String newContent = content.toString();
		Helper.write(diskName, newContent);

		// start two phase commit procedure
		Thread t = new Thread(){
			public void run(){
                newCollage.vote();
                newCollage.countVotetime();
			}
		};
		t.start();
	}


    /**
     * commit all collages in collage dictionary (have not committed yet, first or second stage)
     */
    public static void recommitCollage(){
		Collage currCollage = null;
        for(String filename : collageCommit.keySet()){
			currCollage = collageCommit.get(filename);
            if(currCollage.status.equals("startCommit")){ // the collage failed before reaching a quorum
                // abort
                currCollage.finalDecision = false;
                currCollage.distributeDecision(false);
                currCollage.countAcktime();
			}
			
            else if(currCollage.status.equals("Decision")){ // the collage is written in server but not finished on client
                currCollage.distributeDecision(currCollage.finalDecision);
                currCollage.countAcktime();
            }
        }
	}
	
    /**
     * deal with failures
     * @param logs previous failure log record
     */
    public static void doFailure(List<String> logs){
        if(logs == null){
            return;
        }

		for(String line:logs){
            String[] content = line.split(";");
            String status = content[0];
            String filename = content[1];
            Collage curr = null;

            // case 1. at the first stage
            if(status.equals("startCommit")){
                curr = new Collage(filename, null, Arrays.copyOfRange(content,2,content.length));
                curr.status = "startCommit";
                curr.decisionMade = true;
                curr.finalDecision = false;
                collageCommit.put(filename, curr);
            }
            // case 2. at the second stage
            else if(status.equals("Decision")){
                if(collageCommit.containsKey(filename)){
                    curr = collageCommit.get(filename);
                    curr.sources = Arrays.copyOfRange(content,3,content.length);
                    curr.status = "Decision";
                    curr.decisionMade = true;
                    curr.finalDecision = Boolean.parseBoolean(content[2]);
                }
                else{
                    // put the collage in dict for committing later
                    curr = new Collage(filename, null, Arrays.copyOfRange(content, 3, content.length));
                    curr.status = "Decision";
                    curr.decisionMade = true;
                    curr.finalDecision = Boolean.parseBoolean(content[2]);
                    collageCommit.put(filename, curr);
                }
            }
            // case 3. everything done
            else if(status.equals("Committed")){
                if(collageCommit.containsKey(filename)){
                    collageCommit.remove(filename);
                }
            }
        }
        recommitCollage();
    }
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		Server srv = new Server();
		PL = new ProjectLib( Integer.parseInt(args[0]), srv );
		Helper.PL = PL;
		Collage.PL = PL;

		List<String> logs = Helper.read("Server_log");
        doFailure(logs);

		// main loop
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			MessageBody message = (MessageBody) msg;
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

