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

package azkaban.scheduler;

import java.io.File;
import java.util.HashMap;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.LocalDateTime;
import org.joda.time.ReadablePeriod;

import azkaban.common.utils.Utils;


/**
 * Schedule for a job instance. This is decoupled from the execution.
 * 
 * @author Richard Park
 * 
 */
public class ScheduledJob {

	private static Logger logger = Logger.getLogger(ScheduleManager.class);
    private final String jobName;
    private final ReadablePeriod period;
    private DateTime nextScheduledExecution;
    private final boolean ignoreDependency;
    private final boolean eventTriggeredJob;
    private final String topic;
    private boolean[] criteriaCheck;
    private HashMap<String, String> criteria;
    private HashMap<String, Integer> criteriaMap;
    private final String group;
    private final int startHour;
    private final int stopHour;
    public final EventConsumerManager ecm;

    /**
     * Constructor 
     * 
     * @param jobName Unique job name
     * @param nextExecution The next execution time
     * @param ignoreDependency 
     */
    public ScheduledJob(String jobName,
                        DateTime nextExecution,
                        boolean ignoreDependency) {
        this(jobName, nextExecution, null, ignoreDependency);
    }

    /**
     * Constructor
     * 
     * @param jobId
     * @param nextExecution
     * @param period
     * @param ignoreDependency
     */
    public ScheduledJob(String jobId,
                        DateTime nextExecution,
                        ReadablePeriod period,
                        boolean ignoreDependency) {
    	this(jobId, nextExecution, period, ignoreDependency, false, null, null, null, 0, 23);
    }
    
    /**
     * Constructor
     * 
     * @param jobId
     * @param nextExecution
     * @param period
     * @param ignoreDependency
     * @param triggeredJob
     */
    public ScheduledJob(String jobId,
            DateTime nextExecution,
            ReadablePeriod period,
            boolean ignoreDependency,
            boolean eventTriggeredJob,
            String topic,
            HashMap<String, String> criteria,
            String group,
    		int startHour,
    		int stopHour) {
		super();
		this.ignoreDependency = ignoreDependency;
		this.jobName = Utils.nonNull(jobId);
		this.period = period;
		this.nextScheduledExecution = Utils.nonNull(nextExecution);
		this.eventTriggeredJob = eventTriggeredJob;
		this.topic = topic;

		this.group = group;
		this.startHour = startHour;
		this.stopHour = stopHour;
		
		this.criteria = criteria;
		this.criteriaMap = new HashMap<String, Integer>();
		if(criteria != null)
		{
			this.criteriaCheck = new boolean[criteria.size()];
			int i = 0;
			for(String key : criteria.keySet())
			{
				this.criteriaMap.put(key, i);
				i+=1;
			}
		}
		else
		{
			this.criteriaCheck = new boolean[1];
		}
		for(int i = 0; i<criteriaCheck.length; i++)
			this.criteriaCheck[i] = false;
		
		
		
		if(eventTriggeredJob) this.ecm = new EventConsumerManager(jobId, topic, criteria, criteriaCheck, criteriaMap, group, startHour, stopHour);
		else this.ecm = null;
	}
    
    /**
     * Updates the time to a future time after 'now' that matches the period description.
     * 
     * @return
     */
    public boolean updateTime() {
    	if (nextScheduledExecution.isAfterNow()) {
    		return true;
    	}
    	
        if (period != null) {
        	DateTime other = getNextRuntime(nextScheduledExecution, period);
    		
    		this.nextScheduledExecution = other;
    		return true;
    	}

        return false;
    }
    
    /**
     * Calculates the next runtime by adding the period.
     * 
     * @param scheduledDate
     * @param period
     * @return
     */
    private DateTime getNextRuntime(DateTime scheduledDate, ReadablePeriod period)
    {
        DateTime now = new DateTime();
        
        if(this.isEventTriggered() && 
        		!EventManagerUtils.checkTimeConstraints(now.getHourOfDay(), this.startHour, this.stopHour))
        {
        	//if its not within the time constraints, then just push the next 
        	// runtime to the start of the constraint.        	
        	
        	DateTime date = new LocalDateTime().toDateTime().withHourOfDay(this.startHour).withMinuteOfHour(0);
        	
        	if(date.isBefore(now))
        		date = date.plusDays(1);
        	        	
        	return date;
        }
        
        DateTime date = new DateTime(scheduledDate);

        int count = 0;
        while (!now.isBefore(date)) {
            if (count > 100000) {
                throw new IllegalStateException("100000 increments of period did not get to present time.");
            }

            if (period == null) {
                break;
            }
            else {
                date = date.plus(period);
            }

            count += 1;
        }

        return date;
    }
    
    public void closeConsumers()
    {
    	if(this.isEventTriggered())
    		this.ecm.killConsumers();
    }
    
    /**
     * Check if the trigger event has occurred.
     * @return
     */
    public boolean eventCheckPassed()
    {
    	return ecm.consume();
    }
    
    /**
     * Returns the unique id of the job to be run.
     * @return
     */
    public String getId() {
        return this.jobName;
    }
    
    /**
     * Returns the topic to listen into.
     * @return
     */
    public String getEventTopic() {
        return this.topic;
    }
    
    /**
     * Returns the criteria key to filter the topic
     * @return
     */
    public HashMap<String, String> getCriteria() {
        return this.criteria;
    }
    
    /**
     * Returns the criteria value to filter the topic
     * @return
     */
    public boolean[] getEntireCriteriaCheck() {
        return this.criteriaCheck;
    }
    
    public boolean getCriteriaCheck(int i) {
    	if(i>0 && i<this.criteriaCheck.length)
    		return this.criteriaCheck[i];
    	else
    		return true;
    }
    
    /**
     * Returns the criteria value to filter the topic
     * @return
     */
    public void setCriteriaToTrue(int i) {
        if(i>0 && i<this.criteriaCheck.length)
        {
        	this.criteriaCheck[i] = true;
        }
    }
    
    /**
     * Returns the criteria value to filter the topic
     * @return
     */
    public HashMap<String, Integer> getCriteriaMap() {
        return this.criteriaMap;
    }
    
    /**
     * Returns the group for the Kafka consumer
     * @return
     */
    public String getGroup() {
        return this.group;
    }
    
    /**
     * Returns the start time limit for executing the event-trggered job
     * @return
     */
    public int getStartHour() {
        return this.startHour;
    }
    
    /**
     * Returns the stop time limit for executing the event-triggered job
     * @return
     */
    public int getStopHour() {
        return this.stopHour;
    }

    /**
     * Returns true if the job recurrs in the future
     * @return
     */
    public boolean isRecurring() {
        return this.period != null;
    }
    
    /**
     * Returns true is the job is a event triggered.
     * @return
     */
    public boolean isEventTriggered() {
    	return this.eventTriggeredJob;
    }

    /**
     * Returns the recurrence period. Or null if not applicable
     * @return
     */
    public ReadablePeriod getPeriod() {
        return period;
    }

    /**
     * Returns the next scheduled execution
     * @return
     */
    public DateTime getScheduledExecution() {
        return nextScheduledExecution;
    }

    /**
     * Returns true if the dependency is ignored.
     * @return
     */
    public boolean isDependencyIgnored() {
        return ignoreDependency;
    }

    @Override
    public String toString()
    {
    	String criteriaString = "";
    	if(criteria != null)
    		criteriaString = criteria.toString();
    	else
    		criteriaString = "";
        return "ScheduledJob{" +
               "ignoreDependency=" + ignoreDependency +
               ", nextScheduledExecution=" + nextScheduledExecution +
               ", period=" + period +
               ", eventTriggered=" + eventTriggeredJob +
               ", kafka_topic=" + topic +
               ", criteria" + criteriaString + 
               ", kafka_group=" + group +
               ", criteria_startHour=" + startHour +
               ", criteria_stopHour=" + stopHour +
               ", jobName='" + jobName + '\'' +
               '}';
    }
}
