package azkaban.scheduler;

import java.io.File;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.LocalDateTime;
import org.json.simple.parser.JSONParser;

import kafka.api.FetchRequest;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaMessageStream;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
//import kafka.consumer.*;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import javax.mail.internet.ParseException;

import kafka.javaapi.consumer.*;

import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.Message;
import kafka.message.MessageAndOffset;

import azkaban.util.KeyFinder;


/**
 * Check for events.
 * 
 * @author Sukrit Mohan
 * 
 */

public class EventConsumerManager
{
	private static Logger logger = Logger.getLogger(ScheduleManager.class);
	private Properties props;
	private String topic;
	private HashMap<String, String> criteria;
	private HashMap<String, Integer> criteriaMap;
	public boolean[] criteriaCheck;
	private String group;
	private BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
	
	private int startHour;
	private int stopHour;

	private int threadCount;
	private ExecutorService executor;
	
	private ConsumerConfig consumerConfig;
	private ConsumerConnector consumerConnector;
	
	public EventConsumerManager(String jobName, String topic, HashMap<String, String> criteria, boolean[] criteriaCheck, HashMap<String, Integer> criteriaMap, final String group_dep, int startHour, int stopHour)
	{		
		this.topic = topic;
		this.criteria = criteria;
		this.criteriaMap = criteriaMap;
		this.criteriaCheck = criteriaCheck;
		this.group = topic + "_" + jobName + "_" + criteriaMap.toString();
		this.startHour = startHour;
		this.stopHour = stopHour;
		
		// set consumer properties
		props = new Properties();
		props.put("zk.connect", EventManagerUtils.getConnPort());
		props.put("zk.connectiontimeout.ms", EventManagerUtils.getConnTimeout());
		props.put("groupid", this.group);

		
		threadCount = 1;
				
		// Create the connection to the cluster
		this.consumerConfig = new ConsumerConfig(props);
		this.consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);
		
		HashMap<String, Integer> topicMap = new HashMap<String, Integer>();
		topicMap.put(topic, threadCount);
		List<KafkaMessageStream<Message>> streams = consumerConnector.createMessageStreams(topicMap).get(topic);
		
		executor = Executors.newFixedThreadPool(threadCount);
		
		for(final KafkaMessageStream<Message> stream : streams)
		{
			executor.submit(new Runnable() {
				public void run()
				{
					for(Message msg: stream)
					{
						try {
							queue.put(EventManagerUtils.byteBuffertoString(msg.payload()));
						} catch (InterruptedException e) {
							System.err.println("Error putting message in queue.");
							logger.error("Error putting message in queue.");
							e.printStackTrace();
						}
					}
				}
			});
		}
	}
	
	public void killConsumers()
	{
		this.executor.shutdown();
		this.consumerConnector.shutdown();
	}
	
	public void resetCriteriaCheck()
	{
		for(int i = 0; i<this.criteriaCheck.length; i++)
			this.criteriaCheck[i] = false;
	}
	
	private boolean checkCriteria(String jsonText)
	{
		//check time constraints.
		DateTime date = new LocalDateTime().toDateTime();
		int thisHour = date.getHourOfDay() % 24;

		if(!EventManagerUtils.checkTimeConstraints(thisHour, this.startHour, this.stopHour))
		{
			//put this back in queue. It isn't time yet.
			try {
				this.queue.put(jsonText);
			} catch (InterruptedException e) {
				System.err.println("Error putting message in queue.");
				logger.error("Error putting message in queue.");
				e.printStackTrace();
			}
		}
		else
		{
			// within time constraints - PROCESS!!
			
			JSONParser parser = new JSONParser();
			
			boolean found = false;
			
			

			for(String key : this.criteria.keySet())
			{
				if(found)
					break;	
				
				int index = this.criteriaMap.get(key);
				
				if(!this.criteriaCheck[this.criteriaMap.get(key)]) //if this hasn't already been found
				{
					String value = this.criteria.get(key);

					//If the criteria hasn't been satisfied, parse jsonText and check if this entry fits any of the criterias.
					try {
						Map json = (Map)parser.parse(jsonText);
						Iterator iter = json.entrySet().iterator();
					    while(iter.hasNext()){
					      Map.Entry entry = (Map.Entry)iter.next();
					      String entryKey = (String)entry.getKey();
					      String entryVal = (String)entry.getValue().toString();
					      
					      if(key.equals(entryKey) && value.equals(entryVal))
					      {
					    	  found = true;
					    	  this.criteriaCheck[index] = true;
					    	  break; // from iterator
					      }
					    }
					} catch (org.json.simple.parser.ParseException e) {
						logger.info("UNABLE TO PARSE CRITERIA BLOCK IN SCHEDULE BACKUP");
						e.printStackTrace();
					}
				}
			}
			
			if(allCriteriaSatisfied())
			{
				return true;
			}
		}
		
		return false;
	}
	
	private boolean allCriteriaSatisfied()
	{
		boolean ret = true;
		
		for(int i = 0; (ret) && (i<this.criteriaCheck.length); i++)
		{
			ret = this.criteriaCheck[i];
		}
		
		return ret;
	}
	
	public boolean consume()
	{
		boolean criteriaCheck = false;  //checks whether the user supplied criteria is true in this entry
		
		while(!queue.isEmpty() && !criteriaCheck)
		{
			try {
				String entry = queue.take().toString();
				logger.info(this.group + " CONSUMING : " + entry);
				
				if(this.checkCriteria(entry))
					criteriaCheck = true;
				
			} catch (InterruptedException e) {
				System.err.println("Error in consuming from queue.");
				e.printStackTrace();
			}
		}
				
		return criteriaCheck;
	}
	
}