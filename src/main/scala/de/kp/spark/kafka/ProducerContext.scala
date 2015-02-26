package de.kp.spark.kafka
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
* 
* This file is part of the Elastic-Streaming project
* (https://github.com/skrusche63/elastic-streaming).
* 
* Elastic-Streaming is free software: you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation, either version 3 of the License, or (at your option) any later
* version.
* 
* Elastic-Streaming is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE. See the GNU General Public License for more details.
* You should have received a copy of the GNU General Public License along with
* Elastic-Streaming. 
* 
* If not, see <http://www.gnu.org/licenses/>.
*/

import kafka.producer.{KeyedMessage,Producer,ProducerConfig}
import kafka.message.DefaultCompressionCodec

import java.util.{Properties,UUID}

import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.{PooledObject,BasePooledObjectFactory}

class ProducerContext(settings:Map[String,String]) {

  /*
   * This is for bootstrapping and the producer will only use it for getting metadata 
   * (topics, partitions and replicas). The socket connections for sending the actual 
   * data will be established based on the broker information returned in the metadata. 
   * 
   * The format is host1:port1,host2:port2, and the list can be a subset of brokers or 
   * a VIP pointing to a subset of brokers.
   */      
  private val brokers = settings.getOrElse("kafka.brokers","127.0.0.1:9092")
  /*
   * This parameter allows you to specify the compression codec for all data generated by 
   * this producer. When set to true gzip is used. To override and use snappy you need to 
   * implement that as the default codec for compression using SnappyCompressionCodec.codec 
   * instead of DefaultCompressionCodec.codec below.
   */
  private val codec = DefaultCompressionCodec.codec
  /*
   * This parameter specifies whether the messages are sent asynchronously in a background 
   * thread. Valid values are false for asynchronous send and true for synchronous send.
   *  
   * By setting the producer to async we allow batching together of requests (which is great 
   * for throughput) but open the possibility of a failure of the client machine dropping 
   * unsent data.
   */
  private val synchronously = true 
  /*
   * The client id is a user-specified string sent in each request to help trace calls. 
   * It should logically identify the application making the request.
   */    
  private val clientId = UUID.randomUUID().toString
  /*
   * The number of messages to send in one batch when using async mode. 
   * The producer will wait until either this number of messages are ready 
   * to send or queue.buffer.max.ms is reached.
   */
  private val batchSize = 200
  /*
   * This property will cause the producer to automatically retry a failed send request. 
   * This property specifies the number of retries when such failures occur. Note that 
   * setting a non-zero value here can lead to duplicates in the case of network errors 
   * that cause a message to be sent but the acknowledgement to be lost.
   */
  private val messageSendMaxRetries = 3
  /* 
   *  0: which means that the producer never waits for an acknowledgement from the broker. 
   *     This option provides the lowest latency but the weakest durability guarantees, as
   *     some data will be lost when a server fails.
   *  1: which means that the producer gets an acknowledgement after the leader replica has 
   *     received the data. This option provides better durability as the client waits until 
   *     the server acknowledges the request as successful (only messages that were written 
   *     to the now-dead leader but not yet replicated will be lost).
   * -1: which means that the producer gets an acknowledgement after all in-sync replicas have 
   *     received the data. This option provides the best durability, we guarantee that no messages 
   *     will be lost as long as at least one in sync replica remains.
   */
  private val requestRequiredAcks = -1

  /* Build properties to configure Kafka Poducer */
  private val props = new Properties()
    
  props.put("compression.codec", codec.toString)  
  props.put("producer.type", "sync")
  
  props.put("metadata.broker.list", brokers)
  props.put("batch.num.messages", batchSize.toString)
  
  props.put("message.send.max.retries", messageSendMaxRetries.toString)
  props.put("require.requred.acks",requestRequiredAcks.toString)
  
  props.put("client.id",clientId.toString)

  private val producer = new Producer[Array[Byte], Array[Byte]](new ProducerConfig(props))

  type Key = Array[Byte]
  type Val = Array[Byte]

  private def toMessage(value:Val,key:Option[Key]=None,topic:String):KeyedMessage[Key,Val] = {

    key match {
      case Some(key) => new KeyedMessage(topic, key, value)
      case _ => new KeyedMessage(topic, value)
    }
  }

  def send(key:Key,value:Val,topic:String) {
    producer.send(toMessage(value, Option(key), topic))
  }

  def send(value:Val,topic:String) {
    send(null, value, topic)
  }

  def shutdown(): Unit = producer.close()
  
}

abstract class ProducerContextFactory(settings:Map[String,String]) extends Serializable {
  def newInstance(): ProducerContext  
}

class BaseProducerContextFactory(settings:Map[String,String]) extends ProducerContextFactory(settings) {
  override def newInstance() = new ProducerContext(settings)
}

/**
 * An object factory for ProducerContexts, which is used to create a pool of such contexts 
 * (think: DB connection pool).
 *
 * We use this class in Spark Streaming when writing data to Kafka. A pool is typically 
 * the preferred pattern to minimize TCP connection overhead when talking to Kafka from 
 * a Spark cluster. 
 * 
 * Another reason is to reduce the number of TCP connections being established with the 
 * cluster in order not to strain the cluster.
 *
 * See the Spark Streaming Programming Guide, section "Design Patterns for using foreachRDD" in
 * [[http://spark.apache.org/docs/1.1.0/streaming-programming-guide.html#output-operations-on-dstreams Output Operations on DStreams]]
 */

// TODO: Time out / shutdown producers if they haven't been used in a while.

class PooledProducerContextFactory(val factory:ProducerContextFactory) extends BasePooledObjectFactory[ProducerContext] with Serializable {

  override def create(): ProducerContext = factory.newInstance()

  override def wrap(obj: ProducerContext): PooledObject[ProducerContext] = new DefaultPooledObject(obj)

  /** 
   * From the Commons Pool docs: "Invoked on every instance when it is being "dropped" from the pool.  
   * There is no guarantee that the instance being destroyed will be considered active, passive or 
   * in a generally consistent state."
   * 
   */
  override def destroyObject(pobj:PooledObject[ProducerContext]): Unit = {
    
    pobj.getObject.shutdown()
    super.destroyObject(pobj)
    
  }

}
