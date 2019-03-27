
package dataview.workflowexecutors;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import dataview.models.GlobalSchedule;
import dataview.models.IncomingDataChannel;
import dataview.models.LocalSchedule;
import dataview.models.TaskSchedule;

/**
 * The Beta workflow executor is supporting multi-thread task submission to the task executor correspondingly.
 * Two scenarios is supported in the workflow executor: workflow input is read from the Dropbox folder "DATAVIEW-INPUT"; 
 * the workflow input is read from workflowlibdir which is the project folder path. 
 *
 */

public class WorkflowExecutor_Beta extends WorkflowExecutor {
	public static  String workflowTaskDir;
	public static String workflowlibdir;
	public LocalScheduleRun[] scheduleRunners;
	public ConcurrentHashMap<String, ConcurrentLinkedQueue<TaskRun>> relationMap = new ConcurrentHashMap<>();
	public static int taskNum = 0; 
	public static long starTime;
	public String token="";         
	public static String accessKey;
	public static String secretKey;
	
	/**
	 * The constructor is used to set the path of two folders and read the EC2 provisioning parameters from "config.properties"
	 * 
	 * @param workflowTaskDir
	 * @param workflowlibdir
	 * @param gsch
	 * @throws IOException 
	 */
	
	public WorkflowExecutor_Beta(String workflowTaskDir, String workflowLibDir, GlobalSchedule gsch) throws Exception {
		super(gsch);
		starTime = System.currentTimeMillis();
		for(int i = 0; i < gsch.length(); i++){
			for(int j = 0; j < gsch.getLocalSchedule(i).length(); j++){
				taskNum++;
			}
		}
		this.workflowTaskDir = workflowTaskDir;
		this.workflowlibdir =  workflowLibDir;
		VMProvisioner.parametersetting(workflowLibDir);
		init();
		System.out.println("the total number of tasks are "+ taskNum);
	}
	/**The constructor initialize the file download and upload parameter: dropbox token and VM provisioning paramters: access key and secrete key
	 * 
	 * @param workflowTaskDir
	 * @param workflowlibdir
	 * @param gsch
	 * @param token
	 * @param accessKey
	 * @param secretKey
	 * @throws Exception
	 */
	
	public WorkflowExecutor_Beta(String workflowTaskDir, String workflowLibDir, GlobalSchedule gsch,String token,String accessKey, String secretKey) throws Exception {
		super(gsch);
		starTime = System.currentTimeMillis();
		for(int i = 0; i < gsch.length(); i++){
			for(int j = 0; j < gsch.getLocalSchedule(i).length(); j++){
				taskNum++;
			}
		}
		this.workflowTaskDir = workflowTaskDir;
		this.workflowlibdir = workflowLibDir;
		VMProvisioner.initializeProvisioner(accessKey, secretKey,"dataview1","Dataview_key","ami-01d59acc94399a7c2");
		this.token = token;
		init();
	}
	/**
	 * The init() method calcualtes the VM instances number for each VM type firstly in VMnumbers. 
	 * Then each VM types is provisioned ahead to make every VM available to be used.
	 * @throws Exception
	 */
	public static void init() throws Exception{
		Map<String, Integer> VMnumbers = new HashMap<String, Integer>();
		for(int i = 0; i < gsch.length(); i++){
			LocalSchedule ls = gsch.getLocalSchedule(i);
			System.out.println(ls.getVmType());
			if(VMnumbers.containsKey(ls.getVmType())){
				Integer value = VMnumbers.get(ls.getVmType());
				VMnumbers.put(ls.getVmType(), value+1);
			}else{
				VMnumbers.put(ls.getVmType(), 1);
			}
		}		
		System.out.println(VMnumbers);
		ArrayList<String> ips = new ArrayList<String>();
		VMProvisioner m = new VMProvisioner();	
		for(String str : VMnumbers.keySet()){
			if(str.equals("VM1")){
				m.provisionVMs("t2.xlarge",VMnumbers.get(str), workflowlibdir);
				Thread.sleep(90000);
			}
			if(str.equals("VM2")){
				m.provisionVMs("t2.large",VMnumbers.get(str), workflowlibdir );
				Thread.sleep(90000);
			}
			if(str.equals("VM3")){
				m.provisionVMs("t2.micro",VMnumbers.get(str), workflowlibdir );
				Thread.sleep(90000);
			}
		}
		// collect the IP address for each VM type
		Map<String, LinkedList<String>> ipsAndType = m.getVMInstances();
		for(String str:ipsAndType.keySet()){
			ips.addAll(ipsAndType.get(str));
		}
		System.out.println(ipsAndType);
		// get the pem file generated from the VM provisioning process. 
		String pemFileLocation = workflowlibdir + VMProvisioner.keyName + ".pem";
		// configure each VM instance with confidential information instead of using pem 
		MakeMachinesReady.getMachineReady(pemFileLocation, ips);
		// move the pem file to each VM instance to send intermedidate output.
		MoveToCloud.getFileReady(pemFileLocation, ips);
		// prepare each VM isntance by stopping listening services and remove task files.   
		MoveToCloud.getCodeReady(ips);
		// assign ips to each local schedule.
		for (int i = 0; i < gsch.length(); i++) {
			LocalSchedule ls = gsch.getLocalSchedule(i);
			if(ls.getVmType().equals("VM1")){
				ls.setIP(ipsAndType.get("t2.xlarge").pop());
			}
			if(ls.getVmType().equals("VM2")){
				ls.setIP(ipsAndType.get("t2.large").pop());
			}
			if(ls.getVmType().equals("VM3")){
				ls.setIP(ipsAndType.get("t2.micro").pop());
			}
			
		}
		gsch.completeIPAssignment();
	}
	/**
	 * create threads based on the local schedule and start each thread.
	 */
	public void execute() throws InterruptedException {
		scheduleRunners =  new LocalScheduleRun[gsch.length()];
		for(int i = 0; i < scheduleRunners.length; i++){
			LocalSchedule localSchedule = gsch.getLocalSchedule(i);
			LocalScheduleRun scheduleRunner = new LocalScheduleRun(localSchedule);
			scheduleRunners[i] = scheduleRunner;
		}
		for(LocalScheduleRun run : scheduleRunners){
			run.start();
		}
		for(LocalScheduleRun run : scheduleRunners){
			run.join();
		}	
	}
	/**
	 * Each thread holds local schedule and submit those tasks in the local shcedule to VM instacne sequentially 
	 * If the parent task and child task are submitted in different threads, signal needs to be communicated between two threads
	 */
	public class LocalScheduleRun extends Thread {
		public LocalSchedule lsc;
		public Lock mLock;
		public Condition mReady;
		public TaskRun[] mTaskRunners;

		public LocalScheduleRun(LocalSchedule lsc) {
			mTaskRunners = new TaskRun[lsc.length()];
			for(int i = 0; i < mTaskRunners.length; i++){
				TaskSchedule taskschedule = lsc.getTaskSchedule(i);
				TaskRun taskRunner = new TaskRun(this, taskschedule);
				this.mTaskRunners[i] = taskRunner;
				for (String parentInstanceID : taskschedule.getParents()){
					ConcurrentLinkedQueue<TaskRun> runChildren = relationMap
							.get(parentInstanceID);
					if (runChildren == null) {
						runChildren = new ConcurrentLinkedQueue<>();
						relationMap.put(parentInstanceID, runChildren);
					}
					runChildren.add(taskRunner);
				}
			}
			this.lsc = lsc;
			mLock = new ReentrantLock();
			mReady = mLock.newCondition();
		}
		public void onParentTaskFinished() {
			mLock.lock();
			mReady.signal();
			mLock.unlock();
		}
		public void onFinished(String taskRunID) {
			ConcurrentLinkedQueue<TaskRun> children = relationMap.get(taskRunID);
			if (children != null) {
				for (TaskRun runner : children) {
					runner.onParentFinished();
				}
			}
		}
		/**
		 * wait for until the task is ready to submit to a VM instacne, then a class file or jar file is sent to
		 * the corresponding VM instacne. If the workflow is constructed from the webbench, the input data will be 
		 * downloaded from the Dropbox directly, otherwise, the input should be in the folder of "workflowTaskDir".
		 * once the task is finished. The processor of the children of the task should be updated and wake up the 
		 * waiting thread to execute the children task. 
		 * 
		 */
		@Override
		public void run() {
			for (TaskRun taskrun : mTaskRunners) {
				try {
					while (!taskrun.isReady()) {
						mLock.lock();
						mReady.await();
						mLock.unlock();
					}
					// move each task to VM instance
					String taskFileLocation =  workflowTaskDir + taskrun.taskschdule.getTaskName();
					if(new File(taskFileLocation + ".jar").exists()){
						MoveToCloud.getFileReadyOneIP(taskFileLocation+".jar", taskrun.taskschdule.getIP());
					}else if (new File(taskFileLocation + ".class").exists()){
						MoveToCloud.getFileReadyOneIP(taskFileLocation+".class", taskrun.taskschdule.getIP());
					}else{
						System.out.println("THE TASK FILE IS NOT AVAILABLE");
					}
					List<IncomingDataChannel> indcs = taskrun.taskschdule.getIncomingDataChannels();
					if(token.isEmpty()){
						for(int i = 0; i < indcs.size(); i++){
							if(indcs.get(i).srcFilename != null){
								String	dataFileLocation = workflowTaskDir + indcs.get(i).srcFilename;
								MoveToCloud.getFileReadyOneIP(dataFileLocation, taskrun.taskschdule.getIP());
							}
						}
					}
					taskrun.execute();
					synchronized(this){
						taskNum--;	
						System.out.println("The task number is " +  taskNum);
					}
					if(taskNum == 0){
						long endTime = System.currentTimeMillis();
						System.out.println("The workflow execution time is " + (endTime-starTime));
					}
					onFinished(taskrun.taskRunID);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	
	public class TaskRun {
		private String taskRunID;
		private int predecessors;
		private LocalScheduleRun mLocalRunner;
		private TaskSchedule taskschdule;
		
		public TaskRun(LocalScheduleRun localRunner, TaskSchedule taskschedule){
			predecessors = taskschedule.getParents().size();
			mLocalRunner = localRunner;
			this.taskschdule = taskschedule;
			taskRunID = taskschedule.getTaskInstanceID();
		}
		
		public void onParentFinished() {
			--predecessors;
			mLocalRunner.onParentTaskFinished();
		}

		public boolean isReady() {
			return predecessors == 0;
		}

		public void execute() throws Exception {
			Message m = new Message(token,taskschdule.getSpecification().toString());
			MSGClient client = new MSGClient(mLocalRunner.lsc.getIP(), m );
			client.run();
		}
	}

}
class Message implements Serializable {
    private static final long serialVersionUID = -5399605122490343339L;

    private String A;
    private String B;
   

    public Message(String A, String B ){
        this.A = A; 
        this.B = B;
    }

    public String getA() {
        return A;
    }

    public String getB() {
        return B;
    }
}

