import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.LinkedList;
import java.util.Queue;
import java.util.HashMap;


public class Server extends UnicastRemoteObject implements ServerInterface {
	protected Server() throws RemoteException {
		super();
	}

	private static HashMap<Integer, ServerInterface.Type> vmDict = new HashMap<Integer, ServerInterface.Type>();;
	private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
	private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> dumpQueue  = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
	private static int frontNum = 0;
	private static int midNum = 0;
	private static int frontNeed = 0;
	private static int midNeed = 0;
	private static int qLength = 0;

	private static String serverIP;
	private static int port;
	private static int VMID;


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

	protected static void scaleOut(ServerLib SL) {
		LinkedList<Double> temp = new LinkedList<Double>();
		double average = 0;
		
		while(true){
			temp.add((double)qLength);
			average += (double)qLength;

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			if (temp.size() < 10) continue;
			else{
				// System.err.println("Master: recorded size: " + temp.size());
				average = average / temp.size();
				temp.clear();
				dumpQueue.clear();
				
				System.err.println("Master: average " + average);
			}
			
			
			

			if(vmDict.keySet().size() >= 12){
				average = 0;
				continue;
			}

			if (average < 2){
				midNeed = 0;
				average = 0;
			}
			else if (2 <= average && average < 4.5){
				if(midNum < 2 && (2 - midNum) + vmDict.keySet().size() <= 12) {midNeed = 2 - midNum;}
				average = 0;
			}
			else if (4.5 <= average && average < 6.5){
				if(midNum < 3 && (3 - midNum) + vmDict.keySet().size() <= 12) {midNeed = 3 - midNum;}
				average = 0;
			} 
			else if (6.5 <= average && average < 7){
				if(midNum < 6 && (6 - midNum) + vmDict.keySet().size() <= 12) {midNeed = 6 - midNum;}
				average = 0;
			} 
			else if(average >= 7){
				if(midNum < 10 && (10 - midNum) + vmDict.keySet().size() <= 12){midNeed = 10 - midNum;}
				average = 0;
			}
		}
	}

	private static void doMaster(ServerLib SL, int VMID) {
		SL.register_frontend();
		frontNum++;
		
		// long curr = System.currentTimeMillis();
        // Cloud.FrontEndOps.Request r1 = SL.getNextRequest();
        // long time1 = System.currentTimeMillis();
        // Cloud.FrontEndOps.Request r2 = SL.getNextRequest();
        // long time2 = System.currentTimeMillis();
		// long diff = time2 - time1;
		
		// requestQueue.add(r1);
		// requestQueue.add(r2);
		
		// midNeed += Math.min((int)(1000/diff * 1.25), 7 - midNum);

		while (true) {
			qLength = (int)(SL.getQueueLength()) + requestQueue.size() + dumpQueue.size();

			if ((int)(SL.getQueueLength()) - midNum > 0){
				SL.dropHead();
				continue;
			}

			// process requests
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			// System.err.println("fraction = " + (1.0*qLength)/(1.0*midNum));

			// case on whether we need to drop request
			// case 1: the queue length too long
			// case 2: the vm is still booting / not created yet
			if( (qLength)/(midNum) >= 2 ){
				SL.drop(r);
				dumpQueue.add(r);
				System.err.println("drop");
			}
			else if (SL.getStatusVM(VMID) == Cloud.CloudOps.VMStatus.Booting ||
			SL.getStatusVM(VMID) == Cloud.CloudOps.VMStatus.NonExistent){
				SL.drop(r);
				dumpQueue.add(r);
			}
			else{
				requestQueue.add(r);
				dumpQueue.clear();
			}

			// scaling front
			while (frontNeed > 0){
				frontNum++;
				int front1 = SL.startVM();
				vmDict.put(front1, ServerInterface.Type.FRONT);
				frontNeed --;
			}
			
			// scaling middle
			while (midNeed > 0){
				midNum++;
				int mid1 = SL.startVM();
				vmDict.put(mid1, ServerInterface.Type.MIDDLE);
				midNeed --;
			}

			// System.err.println("front: " + frontNum + " middle: " + midNum);
		}
	}

	// bound the machine
	private static void bound(String URL) {
		Server server = null;
		try {
			server = new Server();
			Naming.bind(URL, server);
		} 
		catch (RemoteException e) {
			e.printStackTrace();
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
		long last = System.currentTimeMillis();
		while (true) {
			long curr = System.currentTimeMillis();
			if (curr - last > 3300 && VMID > 3) break;
			Cloud.FrontEndOps.Request r = null;
			try {
				r = master.getRequest();
			} 
			catch (RemoteException e) {
				// e.printStackTrace();
			}

			if(r != null){
				SL.processRequest(r);
				last = curr;
			}
		}
		
	}

	private static void finish(ServerLib SL, int VMID, String URL, ServerInterface master){
		try{
			System.err.println("finishing this: " + URL);
			

			ServerInterface temp = (ServerInterface) Naming.lookup(URL);
			UnicastRemoteObject.unexportObject(temp, true);
			SL.shutDown();
			master.removeVM(VMID);
		}
		catch (Exception e){
			System.err.println("finishing error ");
			e.printStackTrace();
		}

		System.exit(0);
	}



  
	public static void main(String args[]) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VMID>");

		serverIP = args[0];
		port = Integer.parseInt(args[1]);
		VMID = Integer.parseInt(args[2]);
		ServerLib SL = new ServerLib(serverIP, port);

		// master method
		if (isMaster(serverIP, port)) {
			System.err.println("Master VMID: " + VMID);
			vmDict.put(VMID, ServerInterface.Type.MASTER);

			// start one middle tier
			midNum ++;
			int midID1 = SL.startVM();
			vmDict.put(midID1, ServerInterface.Type.MIDDLE);

			// start one front tier
			frontNum ++;
			int frontID = SL.startVM();
			vmDict.put(frontID, ServerInterface.Type.FRONT);

			// scale out
			Thread t = new Thread(){
				public void run(){
					scaleOut(SL);
				}
			};

			t.setDaemon(true);
			t.start();
			
			// process master
			doMaster(SL, VMID);
		}

		// get master instance
		String masterUrl = "//" + serverIP + ":" + port + "/master";
		ServerInterface master = (ServerInterface) Naming.lookup(masterUrl);
		ServerInterface.Job job = master.assign(VMID);

		// front
		if(job.type == ServerInterface.Type.FRONT){
			String URL = "//" + serverIP + ":" + port + "/front"+ VMID;
			bound(URL);
			doFront(SL, master);
		} 
		// middle
		else if (job.type == ServerInterface.Type.MIDDLE){
			String URL = "//" + serverIP + ":" + port + "/middle"+ VMID;
			bound(URL);
			
			requestQueue = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
			doMiddle(SL, master);
			finish(SL, VMID, URL, master);
		}
		else {
			System.exit(0);
		}
	}


  	@Override
	public Job assign(int VMID) throws RemoteException {
		Job job = new ServerInterface.Job();
		Type type = vmDict.get(VMID);
		job.VMID = VMID;
		job.type = type;
		return job;
	}

  	@Override
	public void addRequest(Cloud.FrontEndOps.Request r) throws RemoteException {
		requestQueue.add(r);
	}

	@Override
	public Cloud.FrontEndOps.Request getRequest() throws RemoteException {
		if(requestQueue.isEmpty()) return null;
		return requestQueue.poll();
	}

	@Override
	public void removeVM(int VMID) throws RemoteException{
		Type type = vmDict.get(VMID);
		if (type == ServerInterface.Type.FRONT) frontNum --;
		if (type == ServerInterface.Type.MIDDLE) midNum --;
		vmDict.put(VMID, ServerInterface.Type.NONE);
	}
}

