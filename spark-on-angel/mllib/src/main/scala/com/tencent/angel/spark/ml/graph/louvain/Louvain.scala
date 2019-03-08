package com.tencent.angel.spark.ml.graph.louvain

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import com.tencent.angel.spark.context.PSContext
import com.tencent.angel.spark.util.VectorUtils

object Louvain {

  def apply(edges: RDD[(Int, Int)], storageLevel: StorageLevel = StorageLevel.MEMORY_ONLY): Louvain = {
    val partNum = edges.getNumPartitions
    val graph = edges.flatMap { case (src, dst) =>
      Iterator((src, (dst, 1.0f)), (dst, (src, 1.0f)))
    }.groupByKey(partNum).mapPartitions { iter =>
      val keys = new ArrayBuffer[Int]()
      val neighbors = new ArrayBuffer[Array[Int]]()
      val weights = new ArrayBuffer[Array[Float]]()
      iter.foreach { case (key, group) =>
        keys += key
        val (e, w) = group.unzip
        neighbors += e.toArray
        weights += w.toArray
      }
      Iterator.single((keys.toArray, neighbors.toArray, weights.toArray))
    }.map { case (keys, neighbors, weights) =>
      new LouvainGraphPartition(keys, neighbors, weights)
    }.persist(storageLevel)

    new Louvain(graph)
  }

  def main(args: Array[String]): Unit = {

    val mode = "local"
    val input = "F:\\email-Eu-core.txt"
    val partitionNum = 4
    val storageLevel = StorageLevel.MEMORY_ONLY

    start(mode)
    val edges = SparkContext.getOrCreate().textFile(input, partitionNum).map { line =>
      val arr = line.split("[\\s+,]")
      val src = arr(0).toInt
      val dst = arr(1).toInt
      (src, dst)
    }
    val louvain = Louvain(edges, storageLevel)
    louvain.process(100)
    louvain.save("louvain")
    stop()
  }

  def start(mode: String = "local"): Unit = {
    val conf = new SparkConf()
    conf.setMaster(mode)
    conf.setAppName("louvain")
    val sc = new SparkContext(conf)
    sc.setLogLevel("WARN")
    PSContext.getOrCreate(sc)
  }

  def stop(): Unit = {
    PSContext.stop()
    SparkContext.getOrCreate().stop()
  }
}

class Louvain(@transient val graph: RDD[LouvainGraphPartition]) extends Serializable {

  private val louvainPSModel = LouvainPSModel.apply(maxId)

  private def initializePS(louvainPSModel: LouvainPSModel): Unit = {
    graph.foreach(_.initializePS(louvainPSModel))
  }

  private lazy val maxId: Int = {
    graph.map(_.max).aggregate(Int.MinValue)(math.max, math.max) + 1
  }

  private lazy val totalWeights: Double = {
    graph.map(_.partitionWeights).sum()
  }

  private def Q2(): Double = {
    VectorUtils.dot(louvainPSModel.community2weightPSVector,
      louvainPSModel.community2weightPSVector
    ) / math.pow(totalWeights, 2)
  }

  private def modularityOptimize(): Unit = {
    graph.foreach(_.modularityOptimize(louvainPSModel, totalWeights))
  }

  private def Q1(): Double = {
    graph.map(_.sumOfWeightsInsideCommunity(louvainPSModel)).sum() / totalWeights
  }

  private def numOfCommunity: Long = {
    graph.flatMap(_.node2community(louvainPSModel)).values.distinct().count()
  }

  private def hist: (Array[Double], Array[Long]) = {
    graph.flatMap(_.node2community(louvainPSModel)).values.histogram(25)
  }

  private def save(path: String): Unit = {
    graph.flatMap(_.node2community(louvainPSModel))
      .map { case (id, comm) => s"$id,$comm" }
      .saveAsTextFile(path)
  }

  def process(maxIter: Int): Unit = {
    initializePS(louvainPSModel)
    val q2 = Q2()
    println(s"Q2 = $q2")
    for (_ <- 0 until maxIter) {
      modularityOptimize()
      val q1 = Q1()
      val q2 = Q2()
      println(s"Q1=$q1, Q2=$q2, Q = ${q1 - q2}")
      println(s"numCommunity = $numOfCommunity")
    }
    val (v, k) = hist
    println(s"key = ${k.mkString(",")}, value = ${v.mkString(",")}")
  }
}
