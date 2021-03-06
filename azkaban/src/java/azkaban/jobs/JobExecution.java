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

package azkaban.jobs;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import azkaban.scheduler.ScheduleManager;

/**
 * Represents information about the execution of a job
 * 
 * @author jkreps
 * 
 */
public class JobExecution {

	private static Logger logger = Logger.getLogger(ScheduleManager.class);
    private final String id;
    private final boolean isEventTriggered;
    private final String topic;
    private DateTime startTime;
    private DateTime endTime;
    private boolean succeeded;
    private String log;
    private final boolean ignoreDependencies;

    public JobExecution(String id, DateTime start, DateTime end, boolean succeeded, boolean ignoreDependecies, String log, boolean eventTriggered, String group) {
        super();
        this.id = id;
        this.startTime = start;
        this.endTime = end;
        this.succeeded = succeeded;
        this.log = log;
        this.ignoreDependencies = ignoreDependecies;
        this.isEventTriggered = eventTriggered;
        this.topic = group;
        
    }
    
    public JobExecution(String jobName, DateTime start, boolean ignoreDependencies, boolean isEventTriggered, String topic)
    {
    	this(jobName, start, null, false, ignoreDependencies, null, isEventTriggered, topic);
    }

    public JobExecution(String jobName, DateTime start, boolean ignoreDependecies) {
        this(jobName, start, ignoreDependecies, false, null);
    }
    
    public JobExecution(String jobName, boolean ignoreDependecies) {
        this(jobName, null, ignoreDependecies);
    }
    
    public String getId() {
        return id;
    }

    public DateTime getStarted() {
        return startTime;
    }
    
    public boolean isEventTriggered()
    {
    	return this.isEventTriggered;
    }
    
    public String getTopic()
    {
    	return this.topic;
    }
    
    public void setStartTime(DateTime start) {
        this.startTime = start;
    }

    public DateTime getEnded() {
        return endTime;
    }
    
    public void setEndTime(DateTime end) {
        this.endTime = end;
    }
    
    public Duration getExecutionDuration() {
        if(startTime == null || endTime == null)
            throw new IllegalStateException("Job has not completed yet.");
        return new Duration(startTime, endTime);
    }
    
    public boolean hasEnded() {
        return endTime != null;
    }

    public void setSucceeded(boolean succeeded) {
    	this.succeeded = succeeded;
    }
    
    public boolean isSucceeded() {
        return succeeded;
    }

    public String getLog() {
        return log;
    }

	public boolean isDependencyIgnored() {
		return ignoreDependencies;
	}
}
