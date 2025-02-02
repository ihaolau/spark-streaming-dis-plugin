/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.dis

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import com.huaweicloud.dis.adapter.kafka.clients.consumer.{Consumer, ConsumerRecord, OffsetAndMetadata, OffsetCommitCallback}
import com.huaweicloud.dis.adapter.kafka.common.TopicPartition
import org.apache.spark.internal.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.scheduler.rate.RateEstimator
import org.apache.spark.streaming.scheduler.{RateController, StreamInputInfo}
import org.apache.spark.streaming.{StreamingContext, Time}

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
 *  A DStream where
 * each given Kafka topic/partition corresponds to an RDD partition.
 * The spark configuration spark.streaming.kafka.maxRatePerPartition gives the maximum number
 *  of messages
 * per second that each '''partition''' will accept.
 * @param locationStrategy In most cases, pass in [[LocationStrategies.PreferConsistent]],
 *   see [[LocationStrategy]] for more details.
 * @param consumerStrategy In most cases, pass in [[ConsumerStrategies.Subscribe]],
 *   see [[ConsumerStrategy]] for more details
 * @param ppc configuration of settings such as max rate on a per-partition basis.
 *   see [[PerPartitionConfig]] for more details.
 * @tparam K type of Kafka message key
 * @tparam V type of Kafka message value
 */
private[spark] class DirectDISInputDStream[K, V](
    _ssc: StreamingContext,
    locationStrategy: LocationStrategy,
    consumerStrategy: ConsumerStrategy[K, V],
    ppc: PerPartitionConfig
  ) extends InputDStream[ConsumerRecord[K, V]](_ssc) with Logging with CanCommitOffsets {

  private val initialRate = context.sparkContext.getConf.getLong(
    "spark.streaming.backpressure.initialRate", 0)

  val executorKafkaParams = {
    val ekp = new ju.HashMap[String, Object](consumerStrategy.executorKafkaParams)
    DISUtils.fixKafkaParams(ekp)
    ekp
  }

  protected var currentOffsets = Map[TopicPartition, Long]()

  @transient private var kc: Consumer[K, V] = null
  def consumer(): Consumer[K, V] = this.synchronized {
    if (null == kc) {
      kc = consumerStrategy.onStart(currentOffsets.mapValues(l => new java.lang.Long(l)).asJava)
    }
    kc
  }

  override def persist(newLevel: StorageLevel): DStream[ConsumerRecord[K, V]] = {
    logError("Kafka ConsumerRecord is not serializable. " +
      "Use .map to extract fields before calling .persist or .window")
    super.persist(newLevel)
  }

  protected def getBrokers = {
    val c = consumer
    val result = new ju.HashMap[TopicPartition, String]()
    val hosts = new ju.HashMap[TopicPartition, String]()
    val assignments = c.assignment().iterator()
    while (assignments.hasNext()) {
      val tp: TopicPartition = assignments.next()
      if (null == hosts.get(tp)) {
        val infos = c.partitionsFor(tp.topic).iterator()
        while (infos.hasNext()) {
          val i = infos.next()
          hosts.put(new TopicPartition(i.topic(), i.partition()), i.leader.host())
        }
      }
      result.put(tp, hosts.get(tp))
    }
    result
  }

  protected def getPreferredHosts: ju.Map[TopicPartition, String] = {
    //locationStrategy match {
    //  case PreferBrokers => getBrokers
    //  case PreferConsistent => ju.Collections.emptyMap[TopicPartition, String]()
    //  case PreferFixed(hostMap) => hostMap
    //}
    ju.Collections.emptyMap[TopicPartition, String]()
  }

  // Keep this consistent with how other streams are named (e.g. "Flume polling stream [2]")
  private[streaming] override def name: String = s"Kafka 0.10 direct stream [$id]"

  protected[streaming] override val checkpointData =
    new DirectDISInputDStreamCheckpointData


  /**
   * Asynchronously maintains & sends new rate limits to the receiver through the receiver tracker.
   */
  override protected[streaming] val rateController: Option[RateController] = {
    if (RateController.isBackPressureEnabled(ssc.conf)) {
      Some(new DirectKafkaRateController(id,
        RateEstimator.create(ssc.conf, context.graph.batchDuration)))
    } else {
      None
    }
  }

  protected[streaming] def maxMessagesPerPartition(
    offsets: Map[TopicPartition, Long]): Option[Map[TopicPartition, Long]] = {
    val estimatedRateLimit = rateController.map { x => {
      val lr = x.getLatestRate()
      if (lr > 0) lr else initialRate
    }}

    // calculate a per-partition rate limit based on current lag
    val effectiveRateLimitPerPartition = estimatedRateLimit.filter(_ > 0) match {
      case Some(rate) =>
        val lagPerPartition = offsets.map { case (tp, offset) =>
          tp -> Math.max(offset - currentOffsets(tp), 0)
        }
        val totalLag = lagPerPartition.values.sum

        lagPerPartition.map { case (tp, lag) =>
          val maxRateLimitPerPartition = ppc.maxRatePerPartition(tp)
          val backpressureRate = lag / totalLag.toDouble * rate
          tp -> (if (maxRateLimitPerPartition > 0) {
            Math.min(backpressureRate, maxRateLimitPerPartition)} else backpressureRate)
        }
      case None => offsets.map { case (tp, offset) => tp -> ppc.maxRatePerPartition(tp).toDouble }
    }

    if (effectiveRateLimitPerPartition.values.sum > 0) {
      val secsPerBatch = context.graph.batchDuration.milliseconds.toDouble / 1000
      Some(effectiveRateLimitPerPartition.map {
        case (tp, limit) => tp -> Math.max((secsPerBatch * limit).toLong, 1L)
      })
    } else {
      None
    }
  }

  /**
   * The concern here is that poll might consume messages despite being paused,
   * which would throw off consumer position.  Fix position if this happens.
   */
  private def paranoidPoll(c: Consumer[K, V]): Unit = {
    val msgs = c.poll(0)
    if (!msgs.isEmpty) {
      // position should be minimum offset per topicpartition
      msgs.asScala.foldLeft(Map[TopicPartition, Long]()) { (acc, m) =>
        val tp = new TopicPartition(m.topic, m.partition)
        val off = acc.get(tp).map(o => Math.min(o, m.offset)).getOrElse(m.offset)
        acc + (tp -> off)
      }.foreach { case (tp, off) =>
          logInfo(s"poll(0) returned messages, seeking $tp to $off to compensate")
          c.seek(tp, off)
      }
    }
  }

  /**
   * Returns the latest (highest) available offsets, taking new partitions into account.
   */
  protected def latestOffsets(): Map[TopicPartition, Long] = {
    val c = consumer
    paranoidPoll(c)
    val parts = c.assignment().asScala

    // make sure new partitions are reflected in currentOffsets
    val newPartitions = parts.diff(currentOffsets.keySet)
    if (newPartitions.nonEmpty) {
      logInfo(s"New partitions $newPartitions")
      // position for new partitions determined by auto.offset.reset if no commit
      currentOffsets = currentOffsets ++ newPartitions.map(tp => tp -> c.position(tp)).toMap
    }

    // Check if there's any partition been revoked because of consumer rebalance.
    val revokedPartitions = currentOffsets.keySet.diff(parts)
    if (revokedPartitions.nonEmpty) {
      //      throw new IllegalStateException(s"Previously tracked partitions " +
      //        s"${revokedPartitions.mkString("[", ",", "]")} been revoked by Kafka because of consumer " +
      //        s"rebalance. This is mostly due to another stream with same group id joined, " +
      //        s"please check if there're different streaming application misconfigure to use same " +
      //        s"group id. Fundamentally different stream should use different group id")
      logInfo(s"Revoke partitions $revokedPartitions")
      currentOffsets = currentOffsets -- revokedPartitions
    }

    // don't want to consume messages, so pause
    val newPausedPartitions = parts.diff(c.paused().asScala)
    if (newPausedPartitions.nonEmpty) {
      c.pause(newPausedPartitions.asJava)
    }
    
    // find latest available offsets
    // c.seekToEnd(currentOffsets.keySet.asJava)
    c.seekToEnd(parts.asJava)
    parts.map(tp => tp -> c.position(tp)).toMap
  }

  // limits the maximum number of messages per partition
  protected def clamp(
    offsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] = {

    maxMessagesPerPartition(offsets).map { mmp =>
      mmp.map { case (tp, messages) =>
          val uo = offsets(tp)
          tp -> Math.min(currentOffsets(tp) + messages, uo)
      }
    }.getOrElse(offsets)
  }

  override def compute(validTime: Time): Option[DISRDD[K, V]] = {
    val untilOffsets = this.synchronized{
      clamp(latestOffsets())
    }
    val offsetRanges = untilOffsets.map { case (tp, uo) =>
      val fo = currentOffsets(tp)
      OffsetRange(tp.topic, tp.partition, fo, uo)
    }
    val useConsumerCache = context.conf.getBoolean("spark.streaming.dis.consumer.cache.enabled",
      true)
    val rdd = new DISRDD[K, V](context.sparkContext, executorKafkaParams, offsetRanges.toArray,
      getPreferredHosts, useConsumerCache)

    // Report the record number and metadata of this batch interval to InputInfoTracker.
    val description = offsetRanges.filter { offsetRange =>
      // Don't display empty ranges.
      offsetRange.fromOffset != offsetRange.untilOffset
    }.map { offsetRange =>
      s"topic: ${offsetRange.topic}\tpartition: ${offsetRange.partition}\t" +
        s"offsets: ${offsetRange.fromOffset} to ${offsetRange.untilOffset}"
    }.mkString("\n")
    // Copy offsetRanges to immutable.List to prevent from being modified by the user
    val metadata = Map(
      "offsets" -> offsetRanges.toList,
      StreamInputInfo.METADATA_KEY_DESCRIPTION -> description)
    val inputInfo = StreamInputInfo(id, rdd.count, metadata)
    ssc.scheduler.inputInfoTracker.reportInfo(validTime, inputInfo)

    currentOffsets = untilOffsets
    commitAll()
    Some(rdd)
  }

  override def start(): Unit = {
    val c = consumer
    paranoidPoll(c)
    if (currentOffsets.isEmpty) {
      currentOffsets = c.assignment().asScala.map { tp =>
        tp -> c.position(tp)
      }.toMap
    }

    // don't actually want to consume any messages, so pause all partitions
    c.pause(currentOffsets.keySet.asJava)
  }

  override def stop(): Unit = this.synchronized {
    if (kc != null) {
      kc.close()
    }
  }

  protected val commitQueue = new ConcurrentLinkedQueue[OffsetRange]
  protected val commitCallback = new AtomicReference[OffsetCommitCallback]

  /**
   * Queue up offset ranges for commit to Kafka at a future time.  Threadsafe.
   * @param offsetRanges The maximum untilOffset for a given partition will be used at commit.
   */
  def commitNextDurationAsync(offsetRanges: Array[OffsetRange]): Unit = {
    commitNextDurationAsync(offsetRanges, null)
  }

  /**
   * Queue up offset ranges for commit to Kafka at a future time.  Threadsafe.
   * @param offsetRanges The maximum untilOffset for a given partition will be used at commit.
   * @param callback Only the most recently provided callback will be used at commit.
   */
  def commitNextDurationAsync(offsetRanges: Array[OffsetRange], callback: OffsetCommitCallback): Unit = {
    commitCallback.set(callback)
    commitQueue.addAll(ju.Arrays.asList(offsetRanges: _*))
  }

  protected def commitAll(): Unit = {
    val m = new ju.HashMap[TopicPartition, OffsetAndMetadata]()
    var osr = commitQueue.poll()
    while (null != osr) {
      if (osr.fromOffset != osr.untilOffset) {
        val tp = osr.topicPartition
        val x = m.get(tp)
        val offset = if (null == x) {
          osr.untilOffset
        } else {
          Math.max(x.offset, osr.untilOffset)
        }
        m.put(tp, new OffsetAndMetadata(offset))
      }
      osr = commitQueue.poll()
    }
    if (!m.isEmpty) {
      val off = m.asScala.map(item => item._1.toString + "=" + item._2.offset())
      consumer.commitAsync(m, commitCallback.get)
      logInfo("commitAll " + off)
    }
  }

  def commitNow(offsetRanges: Array[OffsetRange]): Unit = {
    commitAsync(offsetRanges, null)
  }

  def commitNow(offsetRanges: Array[OffsetRange], callback: OffsetCommitCallback): Unit = {
    commitAsync(offsetRanges, callback);
  }

  def commitAsync(offsetRanges: Array[OffsetRange]): Unit = {
    commitAsync(offsetRanges, null)
  }

  def commitAsync(offsetRanges: Array[OffsetRange], callback: OffsetCommitCallback): Unit = {
    val m = new ju.HashMap[TopicPartition, OffsetAndMetadata]()
    offsetRanges.map(osr => {
      if (osr.fromOffset != osr.untilOffset) {
        val tp = osr.topicPartition
        val x = m.get(tp)
        val offset = if (null == x) {
          osr.untilOffset
        } else {
          Math.max(x.offset, osr.untilOffset)
        }
        m.put(tp, new OffsetAndMetadata(offset))
      }
      osr
    })
    if (!m.isEmpty) {
      val off = m.asScala.map(item => item._1.toString + "=" + item._2.offset())
      val c = consumer
      this.synchronized {
        c.commitAsync(m, callback)
        logInfo("commitAsync " + off)
      }
    }
  }
  
  private[streaming]
  class DirectDISInputDStreamCheckpointData extends DStreamCheckpointData(this) {
    def batchForTime: mutable.HashMap[Time, Array[(String, Int, Long, Long)]] = {
      data.asInstanceOf[mutable.HashMap[Time, Array[OffsetRange.OffsetRangeTuple]]]
    }

    override def update(time: Time): Unit = {
      batchForTime.clear()
      generatedRDDs.foreach { kv =>
        val a = kv._2.asInstanceOf[DISRDD[K, V]].offsetRanges.map(_.toTuple).toArray
        batchForTime += kv._1 -> a
      }
    }

    override def cleanup(time: Time): Unit = { }

    override def restore(): Unit = {
      batchForTime.toSeq.sortBy(_._1)(Time.ordering).foreach { case (t, b) =>
         logInfo(s"Restoring DISRDD for time $t ${b.mkString("[", ", ", "]")}")
         generatedRDDs += t -> new DISRDD[K, V](
           context.sparkContext,
           executorKafkaParams,
           b.map(OffsetRange(_)),
           getPreferredHosts,
           // during restore, it's possible same partition will be consumed from multiple
           // threads, so do not use cache.
           false
         )
      }
    }
  }

  /**
   * A RateController to retrieve the rate from RateEstimator.
   */
  private[streaming] class DirectKafkaRateController(id: Int, estimator: RateEstimator)
    extends RateController(id, estimator) {
    override def publish(rate: Long): Unit = ()
  }
}
