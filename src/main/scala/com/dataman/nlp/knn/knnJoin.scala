package com.dataman.nlp.knn

import scala.math._
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import scala.collection.immutable.Vector
import scala.util.Random

object knnJoin {

  /**
   * Computes the nearest neighbors in the data-set for the data-point against which KNN
   * has to be applied for A SINGLE ITERATION
   *
   * @param rdd : RDD of Vectors of Int, which is the data-set in which knnJoin has to be undertaken
   * @param dataPoint : Vector of Int, which is the data-point with which knnJoin is done with the data-set
   * @param randPoint : Vector of Int, it's the random vector generated in each iteration
   * @param len : the number of data-points from the data-set on which knnJoin is to be done
   * @param zScore : RDD of (Long,Long), which is the ( <line_no> , <zscore> ) for each entry of the dataset
   * @param dataScore : Long value of z-score of the data-point
   * @return an RDD of the nearest 2*len entries from the data-point on which KNN needs to be undertaken for that iteration
   */
  def knnJoin_perIteration(rdd : RDD[(Vector[Int],Long)],
                           dataPoint : Vector[Int],
                           randPoint : Vector[Int],
                           len : Int,
                           zScore : RDD[(Long,String)],
                           dataScore : String,
                           sc : SparkContext) : RDD[(Vector[Int],Long)] = {


    // rdd with score greater than the z-score of the data-point
    val greaterRDD = zScore.filter(word  => word._2.compare(dataScore) > 0).
      map(word => word._2 -> word._1).
      sortByKey(true).
      map(word => word._2).
      zipWithIndex()
    // rdd with score lesser than the z-score of the data-point
    val lesserRDD = zScore.filter(word => word._2.compare(dataScore) < 0)
      .map(word => word._2 -> word._1)
      .sortByKey(false)
      .map(word => word._2)
      .zipWithIndex()


    /**
     * Need 2*len entries, hence the IF-ELSE construct to guarantee these many no.of entries in
     * the returned RDD
     * if the no.of entries in the greaterRDD and lesserRDD is greater than <len>
     * extract <len> no.of entries from each RDD
     */

    if((greaterRDD.count >= len)&&(lesserRDD.count >= len)) {
      val trim = greaterRDD.filter(word => word._2 < len).map(word => word._1).
        union(lesserRDD.filter(word => word._2 < len).map(word => word._1))

      val join = rdd.map(word => word._2 -> word._1)
        .join(trim.map(word => word -> 0))
        .map(word => word._2._1 -> word._1)
      join
    }
    /*
    if the no.of entries in the greaterRDD less than <len>  extract all entries from greaterRDD and
    <len> + (<len> - greaterRDD.count) no.of entries from lesserRDD
    */
    else if(greaterRDD.count < len) {

      val lenMod = len + (len - greaterRDD.count)
      val trim = greaterRDD.map(word => word._1)
        .union(lesserRDD.filter(word => word._2 < lenMod)
        .map(word => word._1))

      val join = rdd.map(word => word._2 -> word._1)
        .join(trim.map(word => word -> 0))
        .map(word => word._2._1 -> word._1)
      join
    }

    //if the no.of entries in the lesserRDD less than <len>
    //extract all entries from lesserRDD and
    //<len> + (<len> - lesserRDD.count) no.of entries from greaterRDD
    else {

      val lenMod = len + (len - lesserRDD.count)
      val trim = greaterRDD.filter(word => word._2 < lenMod).map(word => word._1)
        .union(lesserRDD.map(word => word._1))

      val join = rdd.map(word => word._2 -> word._1)
        .join(trim.map(word => word -> 0))
        .map(word => word._2._1 -> word._1)
      join
    }
  }

  /**
   * Computes the nearest neighbors in the data-set for the data-point against which KNN
   * has to be applied
   *
   * @param dataSet : RDD of Vectors of Int
   * @param dataPoint : Vector of Int
   * @param len : Number of data-points of the dataSet on which knnJoin is to be done
   * @param randomSize : the number of iterations which has to be carried out
   *
   * @return an RDD of Vectors of Int on which simple KNN needs to be applied with respect to the data-point
   */
  def knnJoin(dataSet1 : RDD[(Vector[Double], Long)],
              dataPoint1 : Vector[Double],
              len : Int,
              randomSize : Int,
              sc : SparkContext): RDD[(Vector[Double], Long)] = {

    val dataSet:RDD[(Vector[Int], Long)] = dataSet1.map(vector => {(vector._1.map(i => (i*100000).toInt), vector._2)})
    val dataPoint:Vector[Int] = dataPoint1.map(i => (i*100000).toInt)

    println("dataPoint: " + dataPoint1.foreach(println) + "\n end\n")
    zKNN(dataSet, dataPoint, len).map(vector => {vector._1.map(i => (i.toDouble)/100000)} -> vector._2)
  }

  /**
   * It removes redundant Vectors from the dataset
   * @param DataSet : RDD of Vector[Int] and the vectors corresponding line_no in the data-set
   * @return : RDD of non-repetitive Vectors on Int
   */
  def removeRedundantEntries(DataSet : RDD[(Vector[Int],Long)]) : RDD[(Vector[Int], Long)] = {
    DataSet.map(word => word._2 -> word._1).
      groupByKey().
      map(word => word._2.last -> word._1)

  }

  /**
   * Computes euclidean distance between two vectors
   *
   * @param point1 : Vector of Int
   * @param point2 : Vector of Int
   * @return : euclidean distance between the two vectors
   */
  def euclideanDist(point1 : Vector[Int], point2 : Vector[Int]) : Double = {
    var sum = 0.0
    for(i <- 0 to point1.length-1) {
      sum = sum + pow(point1(i) - point2(i),2)
    }
    sqrt(sum)
  }

  /**
   * Performs kNN over the modified data-set and returns the k-nearest neighbors for the data-point
   *
   * @param reducedData : RDD of Vector of Int, which is the reduced data-set after kNNJoin function applied to the data-set
   * @param dataPoint : Vector of Int, is the data-point for which kNN needs to be undertaken
   * @param k : the no.of neighbors to be computed
   * @return : RDD of Vector of Int
   */
  def zKNN(reducedData : RDD[(Vector[Int], Long)], dataPoint : Vector[Int], k : Int) : RDD[(Vector[Int],Long)] = {
    val distData = reducedData.map(word => euclideanDist(dataPoint, word._1) -> word)
      .sortByKey(true)
      .zipWithIndex()
      .filter(word => word._2 < k).map(word => word._1._2)
    distData

  }

}
