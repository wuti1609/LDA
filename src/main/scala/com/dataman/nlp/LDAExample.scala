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

package com.dataman.nlp

import java.text.BreakIterator
import scala.collection.mutable
import scopt.OptionParser
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkContext, SparkConf}
import  org.apache.spark.graphx.Graph
import org.apache.spark.mllib.clustering.{EMLDAOptimizer, OnlineLDAOptimizer, DistributedLDAModel, LDA}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import java.sql.DriverManager



/**
 * An example Latent Dirichlet Allocation (LDA) app. Run with
 * {{{
 * ./bin/run-example mllib.LDAExample [options] <input>
 * }}}
 * If you use it as a template to create your own app, please use `spark-submit` to submit your app.
 */
object LDAExample {

  private case class Params(
      input: Seq[String] = Seq.empty,
      k: Int = 20,
      maxIterations: Int = 10,
      docConcentration: Double = -1,
      topicConcentration: Double = -1,
      vocabSize: Int = 10000,
      stopwordFile: String = "",
      algorithm: String = "em",
      checkpointDir: Option[String] = None,
      checkpointInterval: Int = 10) extends AbstractParams[Params]

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("LDAExample") {
      head("LDAExample: an example LDA app for plain text data.")
      opt[Int]("k")
        .text(s"number of topics. default: ${defaultParams.k}")
        .action((x, c) => c.copy(k = x))
      opt[Int]("maxIterations")
        .text(s"number of iterations of learning. default: ${defaultParams.maxIterations}")
        .action((x, c) => c.copy(maxIterations = x))
      opt[Double]("docConcentration")
        .text(s"amount of topic smoothing to use (> 1.0) (-1=auto)." +
        s"  default: ${defaultParams.docConcentration}")
        .action((x, c) => c.copy(docConcentration = x))
      opt[Double]("topicConcentration")
        .text(s"amount of term (word) smoothing to use (> 1.0) (-1=auto)." +
        s"  default: ${defaultParams.topicConcentration}")
        .action((x, c) => c.copy(topicConcentration = x))
      opt[Int]("vocabSize")
        .text(s"number of distinct word types to use, chosen by frequency. (-1=all)" +
          s"  default: ${defaultParams.vocabSize}")
        .action((x, c) => c.copy(vocabSize = x))
      opt[String]("stopwordFile")
        .text(s"filepath for a list of stopwords. Note: This must fit on a single machine." +
        s"  default: ${defaultParams.stopwordFile}")
        .action((x, c) => c.copy(stopwordFile = x))
      opt[String]("algorithm")
        .text(s"inference algorithm to use. em and online are supported." +
        s" default: ${defaultParams.algorithm}")
        .action((x, c) => c.copy(algorithm = x))
      opt[String]("checkpointDir")
        .text(s"Directory for checkpointing intermediate results." +
        s"  Checkpointing helps with recovery and eliminates temporary shuffle files on disk." +
        s"  default: ${defaultParams.checkpointDir}")
        .action((x, c) => c.copy(checkpointDir = Some(x)))
      opt[Int]("checkpointInterval")
        .text(s"Iterations between each checkpoint.  Only used if checkpointDir is set." +
        s" default: ${defaultParams.checkpointInterval}")
        .action((x, c) => c.copy(checkpointInterval = x))
      arg[String]("<input>...")
        .text("input paths (directories) to plain text corpora." +
        "  Each text file line should hold 1 document.")
        .unbounded()
        .required()
        .action((x, c) => c.copy(input = c.input :+ x))
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    }.getOrElse {
      parser.showUsageAsError
      sys.exit(1)
    }
  }

  private def run(params: Params) {
    val conf = new SparkConf().setAppName(s"LDAExample with $params")
    val sc = new SparkContext(conf)

    Logger.getRootLogger.setLevel(Level.WARN)

    // Load documents, and prepare them for LDA.
    val preprocessStart = System.nanoTime()
    val (corpus, vocabArray, actualNumTokens) =
      preprocess(sc, params.input, params.vocabSize, params.stopwordFile)
    corpus.cache()
    val actualCorpusSize = corpus.count()
    val actualVocabSize = vocabArray.size
    val preprocessElapsed = (System.nanoTime() - preprocessStart) / 1e9

    println()
    println(s"Corpus summary:")
    println(s"\t Training set size: $actualCorpusSize documents")
    println(s"\t Vocabulary size: $actualVocabSize terms")
    println(s"\t Training set size: $actualNumTokens tokens")
    println(s"\t Preprocessing time: $preprocessElapsed sec")
    println()


    // Run LDA.
    val lda = new LDA()

    //val distrilda:DistributedLDAModel = new DistributedLDAModel()

    val optimizer = params.algorithm.toLowerCase match {
      case "em" => new EMLDAOptimizer
      // add (1.0 / actualCorpusSize) to MiniBatchFraction be more robust on tiny datasets.
      case "online" => new OnlineLDAOptimizer().setMiniBatchFraction(0.05 + 1.0 / actualCorpusSize)
      //case "online" => new OnlineLDAOptimizer().setMiniBatchFraction(0.05)
      case _ => throw new IllegalArgumentException(
        s"Only em, online are supported but got ${params.algorithm}.")
    }

    lda.setOptimizer(optimizer)
      .setK(params.k)
      .setMaxIterations(params.maxIterations)
      .setDocConcentration(params.docConcentration)
      .setTopicConcentration(params.topicConcentration)
      .setCheckpointInterval(params.checkpointInterval)
    if (params.checkpointDir.nonEmpty) {
      sc.setCheckpointDir(params.checkpointDir.get)
    }
    val startTime = System.nanoTime()
    val ldaModel = lda.run(corpus)
    val elapsed = (System.nanoTime() - startTime) / 1e9

    println(s"Finished training LDA model.  Summary:")
    println(s"\t Training time: $elapsed sec")

    if (ldaModel.isInstanceOf[DistributedLDAModel]) {
      val distLDAModel = ldaModel.asInstanceOf[DistributedLDAModel]
      val avgLogLikelihood = distLDAModel.logLikelihood / actualCorpusSize.toDouble
      println(s"\t Training data average log likelihood: $avgLogLikelihood")
      println()
      /*
      val documentIndices=distLDAModel.topicDistributions
      println("save rdd")
      documentIndices.saveAsTextFile("hdfs://10.3.12.9:9000/test/docToTopic.txt")
      println("end")
      println("documentIndices:",documentIndices.count() )
      documentIndices.map(x=>x._2.toArray).foreach(print)
      println("documentIndices end")
      documentIndices.foreach{case (idNum ,topicDis) =>
        print(s"DocumentID :$idNum")
        topicDis.toArray.foreach(print)
      }
      */
      println("Add code sucess!")
    }



    // Print the topics, showing the top-weighted terms for each topic.
    val topicIndices = ldaModel.describeTopics(maxTermsPerTopic = 10)

    println("Test print topicIndices")
    topicIndices.foreach(print)
    println("Test end")
    val topics = topicIndices.map { case (terms, termWeights) =>

      terms.zip(termWeights).map { case (term, weight)  => (vocabArray(term.toInt), weight) }
    }

    //add document relate to topic


    //
    println(s"${params.k} topics:")

    var result = ""

    topics.zipWithIndex.foreach { case (topic, i) =>
      println(s"TOPIC $i")
      result += s"TOPIC $i\n"
      topic.foreach { case (term, weight) =>
        println(s"$term\t$weight")
          result += s"$term\t$weight\n"
      }
      println()
    }

    sc.parallelize(List(result), 1).saveAsTextFile("hdfs://10.3.12.9:9000/test/out1.txt")
    sc.stop()
  }

  /**
   * Load documents, tokenize them, create vocabulary, and prepare documents as term count vectors.
   * @return (corpus, vocabulary as array, total token count in corpus)
   */
  private def preprocess(
      sc: SparkContext,
      paths: Seq[String],
      vocabSize: Int,
      stopwordFile: String): (RDD[(Long, Vector)], Array[String], Long) = {

    // Get dataset of document texts
    // One document per line in each text file. If the input consists of many small files,
    // this can result in a large number of small partitions, which can degrade performance.
    // In this case, consider using coalesce() to create fewer, larger partitions.
    val textRDD: RDD[String] = sc.textFile(paths.mkString(","))

    val stopwords: Set[String] = sc.textFile(stopwordFile).map(_.trim).filter(_.size > 0).distinct.collect.toSet
    val broadcastsw = sc.broadcast(stopwords)

    // Split text into words
    val tokenized: RDD[(Long, IndexedSeq[String])] = textRDD.zipWithIndex().map { case (text, id) =>
      id -> text.split(" ").map(_.trim).filter(x => x.size > 1 && !broadcastsw.value.contains(x))
    }                                                     //分词，并为每篇文章生成ID
    tokenized.cache()                                     //缓存，用于迭代

    // Counts words: RDD[(word, wordCount)]
    val wordCounts: RDD[(String, Long)] = tokenized
      .flatMap { case (_, tokens) => tokens.map(_ -> 1L) }
      .reduceByKey(_ + _)
    wordCounts.cache()                                    //计数，wordcount
    val fullVocabSize = wordCounts.count()                //分词后单词总数
    // Select vocab
    //  (vocab: Map[word -> id], total tokens after selecting vocab)
    val (vocab: Map[String, Int], selectedTokenCount: Long) = {
      val tmpSortedWC: Array[(String, Long)] = if (vocabSize == -1 || fullVocabSize <= vocabSize) {
        // Use all terms
        wordCounts.collect().sortBy(-_._2)
      } else {
        // Sort terms to select vocab
        wordCounts.sortBy(_._2, ascending = false).take(vocabSize)
      }                                                                      // 取前vocabSize个单词
      (tmpSortedWC.map(_._1).zipWithIndex.toMap, tmpSortedWC.map(_._2).sum)  //（（单词，index），取出的单词总数）
    }

    val documents = tokenized.map { case (id, tokens) =>
      // Filter tokens by vocabulary, and create word count vector representation of document.
      val wc = new mutable.HashMap[Int, Int]()
      tokens.foreach { term =>
        if (vocab.contains(term)) {
          val termIndex = vocab(term)
          wc(termIndex) = wc.getOrElse(termIndex, 0) + 1
        }
      }
      val indices = wc.keys.toArray.sorted
      val values = indices.map(i => wc(i).toDouble)

      val sb = Vectors.sparse(vocab.size, indices, values)
      (id, sb)
    }                                                                      //将分词后的文章解析成为vector（id，vector）

    val vocabArray = new Array[String](vocab.size)
    vocab.foreach { case (term, i) => vocabArray(i) = term }               //单词数组对应关系（vocabArray(word) == index)

    (documents, vocabArray, selectedTokenCount)                            //单词个数之和
  }
}
