/*
 * Copyright 2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.flow;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.json.simple.JSONObject;

import azkaban.app.JobDescriptor;
import azkaban.app.JobManager;
import azkaban.app.Mailman;
import azkaban.common.jobs.DelegatingJob;
import azkaban.common.jobs.Job;
import azkaban.common.utils.Props;
import azkaban.common.utils.Utils;
import azkaban.jobs.JobExecution;
import azkaban.jobs.Status;
import azkaban.scheduler.EventManagerUtils;

/**
 * An implemention of the ExecutableFlow interface that just
 * wraps a single Job.
 */
public class IndividualJobExecutableFlow implements ExecutableFlow
{
    private static final Logger logger = Logger.getLogger(IndividualJobExecutableFlow.class);

    private static final AtomicLong threadCounter = new AtomicLong(0);

    private final Object sync = new Object();
    private final String id;
    private final String name;
    private final JobManager jobManager;

    private volatile Status jobState;
    private volatile List<FlowCallback> callbacksToCall;
    private volatile DateTime startTime;
    private volatile DateTime endTime;
    private volatile Job job;
    private volatile Map<String, Throwable> exceptions = new HashMap<String, Throwable>();

    private volatile Props parentProps;
    private volatile Props returnProps;

    public IndividualJobExecutableFlow(String id, String name, JobManager jobManager)
    {
        this.id = id;
        this.name = name;
        this.jobManager = jobManager;

        resetState();
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Props getParentProps() {
		return parentProps;
	}

    @Override
    public Props getReturnProps() {
        return returnProps;
    }

 
    @Override
    public void execute(Props parentProps, FlowCallback callback)
    {
        if (parentProps == null) {
            parentProps = new Props();
        }
        
        synchronized (sync) {
            if (this.parentProps == null) {
                this.parentProps = parentProps;
            }
            else if (jobState != Status.COMPLETED && ! this.parentProps.equalsProps(parentProps)) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s.execute() called with multiple differing parentProps objects. " +
                                "Call reset() before executing again with a different Props object. this.parentProps[%s], parentProps[%s]",
                                getClass().getSimpleName(),
                                this.parentProps,
                                parentProps
                        )
                );
            }

            switch (jobState) {
                case READY:
                    jobState = Status.RUNNING;
                    startTime = new DateTime();
                    callbacksToCall.add(callback);
                    break;
                case RUNNING:
                    callbacksToCall.add(callback);
                    return;
                case IGNORED:
                	jobState = Status.COMPLETED;
                case COMPLETED:
                case SUCCEEDED:
                    callback.completed(Status.SUCCEEDED);
                    return;
                case FAILED:
                    callback.completed(Status.FAILED);
                    return;     	
            }
        }

        try {
            // Only one thread should ever be able to get to this point because of management of jobState
            // Thus, this should only ever get called once before the job finishes (at which point it could be reset)
            job = jobManager.loadJob(getName(), parentProps, true);
        }
        catch (Exception e) {
            logger.warn(
                    String.format("Exception thrown while creating job[%s]", getName()),
                    e
            );
            job = null;
        }

        if (job == null) {
            logger.warn(
                    String.format("Job[%s] doesn't exist, but was supposed to run. Perhaps someone changed the flow?", getName())
            );

            final List<FlowCallback> callbackList;

            synchronized (sync) {
                jobState = Status.FAILED;
                callbackList = callbacksToCall; // Get the reference before leaving the synchronized
            }
            callCallbacks(callbackList, jobState);
            return;
        }

        Thread theThread = new Thread(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        final List<FlowCallback> callbackList;

                        try {
                            job.run();
                        }
                        catch (Exception e) {
                            synchronized (sync) {
                                jobState = Status.FAILED;
                                returnProps = new Props();
                                exceptions.put(getName(), e);
                                callbackList = callbacksToCall; // Get the reference before leaving the synchronized
                                
                                //the job failed, send failure email here.
                                
                                String toAddress = jobManager.loadJobDescriptors().get(job.getId()).getFailureEmail();
                                String jobId = jobManager.loadJobDescriptors().get(job.getId()).getId();
                                int lastLogLineNum = 60;
                                
                                StringBuffer body = new StringBuffer("The job '"
                                        + job.getId()
                                        + " has failed. \r\n");
                                
                                /* append log file link */
                                JobExecution jobExec = jobManager.loadMostRecentJobExecution(jobId);
                                if(jobExec == null) {
                                    body.append("Job execution object is null for jobId:" + jobId + "\n\n");
                                }

                                String logPath = jobExec != null ? jobExec.getLog() : null;
                                if(logPath == null) {
                                    body.append("Log path is null. \n\n");
                                } else {
                                    body.append("See log in " + logPath + "\n\n" + "The last "
                                                + lastLogLineNum + " lines in the log are:\n");

                                    /* append last N lines of the log file */
                                    String logFilePath = jobManager.getLogDir() + File.separator
                                                         + logPath;
                                    Vector<String> lastNLines = Utils.tail(logFilePath, 60);

                                    if(lastNLines != null) {
                                        for(String line: lastNLines) {
                                            body.append(line + "\n");
                                        }
                                    }
                                }
                                
                                List<String> toEmail = new LinkedList<String>();
                                toEmail.add(toAddress);
                                
                                Mailman mailer = new Mailman("localhost", "", "");
                                String senderAddress = jobManager.loadJobDescriptors().get(job.getId()).getSenderEmail();
                                mailer.sendEmailIfPossible(senderAddress, toEmail, jobId + " failed", body.toString());
                                
                            }
                            callCallbacks(callbackList, jobState);

                            throw new RuntimeException(e);
                        }
 
                        synchronized (sync) {
                            jobState = Status.SUCCEEDED;
                            returnProps = job.getJobGeneratedProperties();
                            callbackList = callbacksToCall; // Get the reference before leaving the synchronized
                            
                            //since the job succeeded, send kafka message if possible.
                          
                            //EventManagerUtils.initializeProducer();
                            
                            String topic = jobManager.loadJobDescriptors().get(job.getId()).getKafkaTopic();
                            JSONObject jobDetails = new JSONObject();
                            jobDetails.put("JobID", job.getId());
                            //jobDetails.put("message", body);
                            jobDetails.put("timestamp", Long.toString(System.currentTimeMillis()));
                            jobDetails.put("vertical", jobManager.loadJobDescriptors().get(job.getId()).getVertical());
                            try {
                            	String[] splitByDot = topic.split("\\.");
                            	if(splitByDot != null)
                            	{
                    	        	String env = splitByDot[0];
                    	        	logger.info("ENV : " + env);
                    	        	if(env.equals("dev") || env.equals("prod") || env.equals("stage"))
                    	        	{
                    	        		String rootEnv = (!env.equals("stage")) ? env : "dev";
                    					BufferedReader in = new BufferedReader(new FileReader("/home/"+rootEnv+"/qfAzkaban/azkaban-jobs-"+env+"/"+env + "/tsDir.sh"));
                    					String tsDir = in.readLine();
                    					jobDetails.put("tsDir", tsDir);
                    	        	}
                    	        	else if (env.equals("curation"))
                    	        	{
                    	        		System.out.println("IS IN CURATION!!");
                    	        		jobDetails.put("tsDir", "/data/exports3/curation_dump/");
                    	        	}
                            	}
                    			
                    		} catch (FileNotFoundException e) {
                    			logger.info("SUKRIT : FILE NOT FOUND!!! PROBLEM....");
                    			e.printStackTrace();
                    		} catch (IOException e) {
                    			// TODO Auto-generated catch block
                    			e.printStackTrace();
                    		}
                            String kafkaMsg = jobDetails.toJSONString();
                            logger.info("Publishing to kafka topic : " + topic);
                            logger.info("Message : " + kafkaMsg);
                            System.out.println("Publishing to kafka topic : " + topic);
                            System.out.println("Message : " + kafkaMsg);
                            if(topic != null)
                            	EventManagerUtils.publish(topic, kafkaMsg);
                            
                            //EventManagerUtils.closeProducer();
                            
                            String toAddress = jobManager.loadJobDescriptors().get(job.getId()).getSuccessEmail();
                            String jobId = jobManager.loadJobDescriptors().get(job.getId()).getId();
                            
                            List<String> toEmail = new LinkedList<String>();
                            toEmail.add(toAddress);
                            
                            StringBuffer body = new StringBuffer("The job '"
                                    + job.getId()
                                    + " has completed successfully.");
                            
                            Mailman mailer = new Mailman("localhost", "", "");
                            String senderAddress = jobManager.loadJobDescriptors().get(job.getId()).getSenderEmail();
                            mailer.sendEmailIfPossible(senderAddress, toEmail, jobId + " completed", body.toString());
                        }
                        
                        returnProps.logProperties(String.format("Return props for job[%s]", getName()));

                        callCallbacks(callbackList, jobState);
                    }
                },
                String.format("%s thread-%s", job.getId(), threadCounter.getAndIncrement())
        );

        Job currJob = job;
        while (true) {
            if (currJob instanceof DelegatingJob) {
                currJob = ((DelegatingJob) currJob).getInnerJob();
            }
            else {
                break;
            }
        }

        theThread.start();
    }



    @Override
    public boolean cancel() {
        final List<FlowCallback> callbacks;
        synchronized(sync) {
            switch(jobState) {
                case COMPLETED:
                case SUCCEEDED:
                case FAILED:
                case IGNORED:
                    return true;
                default:
                    jobState = Status.FAILED;
                    callbacks = callbacksToCall;
                    callbacksToCall = new ArrayList<FlowCallback>();
                    returnProps = new Props();
            }
        }

        for(FlowCallback callback: callbacks) {
            callback.completed(Status.FAILED);
        }

        try {
            if(job != null) {
                job.cancel();
            }

            return true;
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Status getStatus() {
        return jobState;
    }

    @Override
    public boolean reset() {
        synchronized(sync) {
            switch(jobState) {
                case RUNNING:
                    return false;
                default:
                    resetState();
            }
        }

        return true;
    }

    @Override
    public boolean markCompleted() {
        synchronized(sync) {
            switch(jobState) {
                case RUNNING:
                    return false;
                default:
                    jobState = Status.COMPLETED;
                    parentProps = new Props();
                    returnProps = new Props();
            }
        }
        return true;
    }

    @Override
    public boolean hasChildren() {
        return false;
    }

    @Override
    public List<ExecutableFlow> getChildren() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "IndividualJobExecutableFlow{" + "job=" + job + ", jobState=" + jobState + '}';
    }

    @Override
    public DateTime getStartTime() {
        return startTime;
    }

    @Override
    public DateTime getEndTime() {
        return endTime;
    }

    @Override
    public Map<String, Throwable> getExceptions() {
        return exceptions;
    }

    /**
     * This use to be package protected, but I've made it public to handle
     * a more robust case of disabled jobs. This will be replaced with the
     * new flow code soon.
     * 
     * @param newStatus
     * @return
     */
    public IndividualJobExecutableFlow setStatus(Status newStatus) {
        synchronized(sync) {
            switch(jobState) {
                case READY:
                    jobState = newStatus;
                    break;
                default:
                    throw new IllegalStateException("Can only set status when job is in the READY state.");
            }
        }

        return this;
    }

    IndividualJobExecutableFlow setStartTime(DateTime startTime) {
        this.startTime = startTime;

        return this;
    }

    IndividualJobExecutableFlow setEndTime(DateTime endTime) {
        this.endTime = endTime;

        return this;
    }

    private void callCallbacks(final List<FlowCallback> callbackList, final Status status)
    {
        if (endTime == null) {
            endTime = new DateTime();
        }

        Thread callbackThread = new Thread(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        for (FlowCallback callback : callbackList) {
                            try {
                                callback.completed(status);
                            }
                            catch (RuntimeException t) {
                                logger.error(
                                        String.format("Exception thrown while calling callback. job[%s]", getName()),
                                        t
                                );
                            }
                        }
                    }
                },
                String.format("%s-callback", Thread.currentThread().getName())
        );

        callbackThread.start();
    }

    IndividualJobExecutableFlow setParentProperties(Props parentProps)
    {
        synchronized (sync) {
            if (this.parentProps == null) {
                this.parentProps = parentProps;
            }
            else {
                throw new IllegalStateException("Attempt to override parent properties.  " +
                                                "This method should only really be called from deserialization code");
            }
        }

        return this;
    }

    private void resetState() {
        jobState = Status.READY;
        callbacksToCall = new ArrayList<FlowCallback>();
        parentProps = null;
        returnProps = null;
        startTime = null;
        endTime = null;
        exceptions.clear();
    }

    IndividualJobExecutableFlow setReturnProperties(Props returnProps)
    {
        synchronized (sync) {
            if (this.returnProps == null) {
                this.returnProps = returnProps;
            }
            else {
                throw new IllegalStateException("Attempt to override return properties.  " +
                                                "This method should only really be called from deserialization code");
            }
        }

        return this;
    }
}
