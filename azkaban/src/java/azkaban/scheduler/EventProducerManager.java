package azkaban.scheduler;

import java.io.File;
import org.apache.log4j.Logger;
import kafka.producer.ProducerConfig;
import java.util.Properties;

import kafka.javaapi.producer.*;

import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.Message;


/**
 * Check for events.
 * 
 * @author Sukrit Mohan
 * 
 */

public class EventProducerManager
{
	private static Logger logger = Logger.getLogger(ScheduleManager.class);
	private Properties producerProps;
	private ProducerConfig producerConfig;
	private Producer<String, String> producer;
	
	public EventProducerManager()
	{
		producerProps = new Properties();
		producerProps.put("zk.connect", EventManagerUtils.getConnPort());
		
		producerProps.put("serializer.class", EventManagerUtils.getSerializer());
		
		producerConfig = new ProducerConfig(producerProps);
		producer = new Producer<String, String> (producerConfig);
	}
	
	public void publish()
	{
		ProducerData<String, String> data = new ProducerData<String, String>("test", "SUKRIT");
		producer.send(data);	
	}
}