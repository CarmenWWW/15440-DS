import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.HashMap;


public class Server extends UnicastRemoteObject implements ServerInterface {
	protected Server() throws RemoteException {
		super();
	}

	private static HashMap<Integer, ServerInterface.Type> vmDict;
	private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue;
	private static int frontNum = 0;
	private static int midNum = 0;
	private static int frontNeed = 0;
	private static int midNeed = 0;
	private static int qLength = 0;

	private static String serverIP;
	private static int port;
	private static int vMid;


	private static boolean isMaster(String serverIP, int port) {
		Server server = null;
		String masterUrl = "//" + serverIP + ":" + port + "/master";
		try {
			server = new Server();
			Naming.bind(masterUrl, server);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void doMaster(ServerLib SL, int VMid) {
		SL.register_frontend();
		frontNum++;
		long lastDropTime = System.currentTimeMillis();

		long curr = System.currentTimeMillis();
		Cloud.FrontEndOps.Request r1 = SL.getNextRequest();
		long time1 = System.currentTimeMillis();
		Cloud.FrontEndOps.Request r2 = SL.getNextRequest();
		long time2 = System.currentTimeMillis();

		long diff = time2 - time1;

		requestQueue.add(r1);
		requestQueue.add(r2);

		midNeed += Math.min((int)(1000/diff * 1.25), 7 - midNum);
		
		while (true) {
			qLength = (int)(SL.getQueueLength()) + requestQueue.size();

			// process requests
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			System.err.println("fraction = " + (1.0*qLength)/(1.0*midNum));

			long currTime = System.currentTimeMillis();
			// case on whether we need to add more middle tier
			if( (qLength)/(midNum) >= 3 ){
				SL.drop(r);
				System.err.println("ADD MIDDLE");
				lastDropTime = currTime;
			}
			else{
				requestQueue.add(r);
			}

			// scaling front
			while (frontNeed > 0){
				frontNum++;
				int _VMid = SL.startVM();
				vmDict.put(_VMid, ServerInterface.Type.FRONT);
				frontNeed --;
			}
			
			// scaling middle
			while (midNeed > 0){
				midNum++;
				int _VMid = SL.startVM();
				vmDict.put(_VMid, ServerInterface.Type.MIDDLE);
				midNeed --;
			}

		}
	}

	// bound the machine
	private static void bound(String URL) {
		Server server = null;
		try {
			server = new Server();
		} 
		catch (RemoteException e) {
			e.printStackTrace();
		}

		try {
			Naming.bind(URL, server);
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
	}


	private static void doFront(ServerLib SL, ServerInterface master) {
		SL.register_frontend();
		while (true) {
			try {
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				if(r != null) master.addRequest(r);
			} 
			catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}


	private static void doMiddle(ServerLib SL, ServerInterface master) {
		long stopTime = System.currentTimeMillis();
		long lastTime = System.currentTimeMillis();
		while (true) {
			Cloud.FrontEndOps.Request r = null;
			try {
				r = master.getRequest();
			} 
			catch (RemoteException e) {
				// e.printStackTrace();
			}

			if(r != null){
				SL.processRequest(r);
			}
		}
	}



  
	public static void main(String args[]) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VMid>");

		serverIP = args[0];
		port = Integer.parseInt(args[1]);

		vMid = Integer.parseInt(args[2]);
		ServerLib SL = new ServerLib(serverIP, port);

		// master method
		if (isMaster(serverIP, port)) {
			System.err.println("Master VMid: " + vMid);
			vmDict = new HashMap<Integer, ServerInterface.Type>();
			requestQueue = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
			vmDict.put(vMid, ServerInterface.Type.MASTER);

			// start one middle tier
			midNum ++;
			int midID1 = SL.startVM();
			vmDict.put(midID1, ServerInterface.Type.MIDDLE);

			// start one front tier
			// frontNum ++;
			// int frontID = SL.startVM();
			// vmDict.put(frontID, ServerInterface.Type.FRONT);

			
			// process master
			doMaster(SL, vMid);
		}

		// get master instance
		String masterUrl = "//" + serverIP + ":" + port + "/master";
		ServerInterface master = (ServerInterface) Naming.lookup(masterUrl);
		ServerInterface.Job job = master.assign(vMid);

		if(job.VMid != vMid){
			System.err.println("ID does not match -- VMid: "+vMid+" job.VMid: " + job.VMid);
		}

		// front
		if(job.type == ServerInterface.Type.FRONT){
			System.err.println("Front VMid: " + vMid);
			String URL = "//" + serverIP + ":" + port + "/front"+ vMid;
			bound(URL);
			doFront(SL, master);
		} 
		// middle
		else if (job.type == ServerInterface.Type.MIDDLE){
			System.err.println("Middle VMid: " + vMid);
			String URL = "//" + serverIP + ":" + port + "/middle"+ vMid;
			bound(URL);
			
			requestQueue = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
			doMiddle(SL, master);
		}
		else {
			System.exit(0);
		}
	}


  	@Override
	public Job assign(int VmID) throws RemoteException {
		Job job = new ServerInterface.Job();
		Type type = vmDict.get(VmID);
		job.VMid = VmID;
		job.type = type;
		return job;
	}

  	@Override
	public synchronized void addRequest(Cloud.FrontEndOps.Request r) throws RemoteException {
		requestQueue.add(r);
	}

	@Override
	public synchronized Cloud.FrontEndOps.Request getRequest() throws RemoteException {
		if(requestQueue.isEmpty()) return null;
		return requestQueue.poll();
	}
}

