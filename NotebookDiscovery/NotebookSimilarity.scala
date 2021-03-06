// Databricks notebook source
// MAGIC %md ### NotebookSimilarity 
// MAGIC Compare the notebooks in the Notebook Discovery Index parquet file generated by NotebookIndex to produce a similarity score for every notebook. We first prepare the data by concatenating all the commands text for each notebook into a single string.  Since we have the command order, we sort the commands prior to the concatenation.  We also chose to ignore markdown cells as we wanted to focus on actual 'code' and not comments when  performing the comparisons.  Once we have the string of text for each notebook, we then [tokenize](https://spark.apache.org/docs/latest/ml-features#tokenizer) the text.  To provide a sense of order for the text, we then create [ngrams](https://spark.apache.org/docs/latest/ml-features.html#n-gram) from the tokenized text.  We then use [CountVectorizer](https://spark.apache.org/docs/latest/ml-features.html#countvectorizer) to create feature vector that we can use as input for [MinHash](https://spark.apache.org/docs/latest/ml-features.html#minhash-for-jaccard-distance).  Lastly, we do an approximate similarity join and write the Notebook Discovery Similarity parquet file. 
// MAGIC 
// MAGIC <br/>The resulting Discovery Notebook Similarity parquet file will have the following structure.  There will be a record for every notebook that contains other notebooks within the specified Jaccard distance.  If a notebook doesn't have any notebooks within the Jaccard distance, there will be no record for that notebook.  Note that the notebook identified by nbUrl doesn't mean it is the 'original' notebook and that all of the notebooks contained in similar.nbUrl were cloned from that notebook.  Instead, the notebook identified by nbUrl is similar to the notebooks identified by similar.nbUrl.
// MAGIC ```
// MAGIC |-- nbUrl: string 
// MAGIC |-- similar: array 
// MAGIC |    |-- element: struct 
// MAGIC |    |    |-- jaccard: double 
// MAGIC |    |    |-- nbUrl: string 
// MAGIC ```
// MAGIC with the following descriptions for each field:
// MAGIC - nbUrl: notebook url so it can be quickly followed
// MAGIC - similar: array containing the top similar notebooks for the notebook identified by nbUrl
// MAGIC - similar.jaccard: similarity score for the notebook comparison of the notebook identified by nbUrl and the notebook identified by similar.nbUrl
// MAGIC - similar.nbUrl: similar notebook url so it can be quickly followed

// COMMAND ----------

// MAGIC %md ##### imports

// COMMAND ----------

import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row
import org.apache.spark.ml.feature._
import org.apache.spark.ml.linalg._

import scala.collection.mutable.WrappedArray
import scala.collection.mutable.ListBuffer

// COMMAND ----------

// MAGIC %md ##### parameters

// COMMAND ----------

val indexFilename = dbutils.widgets.get("indexFilename")
val similarityFilename = dbutils.widgets.get("similarityFilename")
val overwrite = dbutils.widgets.get("overwrite")
val similarDistance = dbutils.widgets.get("similarDistance").toFloat
val vocabSize = dbutils.widgets.get("vocabSize").toInt
val ngramSize = dbutils.widgets.get("ngramSize").toInt
val minDocFreq = dbutils.widgets.get("minDocFreq").toInt


// COMMAND ----------

// MAGIC %md ##### fileExists
// MAGIC Does the indexFilename or similarityFilename already exist?

// COMMAND ----------

def fileExists(file: String): Boolean = {
  import java.io.FileNotFoundException
  var rc = true
  try {
    dbutils.fs.ls(file)
  } catch {
    case foo: FileNotFoundException => {rc = false}
  }
  rc
}

// verify the index file exists
if (!fileExists(indexFilename)) {
  dbutils.notebook.exit("FAILED.  Index file '%s' does not exist.".format(indexFilename))
}

// verify overwrite parameter
if (overwrite.toLowerCase != "false" && overwrite.toLowerCase != "true") {
  dbutils.notebook.exit("FAILED.  Overwrite parameter '%s' must be either 'True' or 'False'.".format(overwrite))
}

// very similairy file does not already exist if overwrite is false
if (overwrite.toLowerCase == "false") {
  if (fileExists(similarityFilename)) {
    dbutils.notebook.exit("FAILED.  Similarity file '%s' already exists.".format(similarityFilename))
  }
}

// COMMAND ----------

// MAGIC %md ##### read Notebook Discovery Index file
// MAGIC Read in the index file (generated by NotebookIndex) that contains the commands for all of the notebooks.

// COMMAND ----------

val notebookCommands = spark.read.parquet(indexFilename)

// COMMAND ----------

// MAGIC %md ##### create notebook records
// MAGIC All of the individual command records for each notebook need to be concatenated into a single string.  This includes the following processing:
// MAGIC - remove markdown cells
// MAGIC - remove empty cells
// MAGIC - sort the commands within each notebook
// MAGIC - combine the commands for each notebook into a single string

// COMMAND ----------

def notebookText(arr: WrappedArray[Row]): String = {
  var textList =  ListBuffer[(Double,String)]()
  var text = ""
  for (row <- arr) {
    textList.append((row.getAs[Double]("cPos"), row.getAs[String]("cText")))
  }
  for (rec <- textList) {
    text = text + rec._2 + " "
  }
  return text.trim()
}                   

val notebookTextUDF = udf[String,WrappedArray[Row]](notebookText)

val notebooks = notebookCommands.filter($"cLang" =!= "markdown")
                                .filter(!$"cText".isNull)
                                .select($"nbUrl", struct($"cPos", $"cText").as("commands"))
                                .groupBy($"nbUrl")
                                .agg(sort_array(collect_list($"commands")).as("commands"))
                                .withColumn("text", notebookTextUDF($"commands"))
                                .drop($"commands")

// COMMAND ----------

// MAGIC %md ##### tokenize the notebook text and create nGrams

// COMMAND ----------

val tokenizer = new Tokenizer().setInputCol("text").setOutputCol("words")
val words = tokenizer.transform(notebooks)
val ngram = new NGram().setN(ngramSize).setInputCol("words").setOutputCol("ngrams")
val ngrams = ngram.transform(words)

// COMMAND ----------

// MAGIC %md ##### create a feature vector for the ngrams count

// COMMAND ----------

spark.conf.set("spark.sql.legacy.allowUntypedScalaUDF",true) // need to investigate this
val cvModel: CountVectorizerModel = new CountVectorizer().setInputCol("ngrams").setOutputCol("features").setVocabSize(vocabSize).setMinDF(minDocFreq).fit(ngrams)
val isNonZeroVector = udf({v: Vector => v.numNonzeros > 0}, DataTypes.BooleanType)
val vectorized = cvModel.transform(ngrams).filter(isNonZeroVector(col("features"))).select(col("nbUrl"), col("features"))

// COMMAND ----------

// MAGIC %md ##### create a MinHashLSH using the vectors

// COMMAND ----------

val mh = new MinHashLSH().setNumHashTables(3).setInputCol("features").setOutputCol("hashValues")
val model = mh.fit(vectorized)

// COMMAND ----------

// MAGIC %md ##### find the similar notebooks
// MAGIC - apply the similar distance threshold (JaccardDistance must be <= the similarDistance)
// MAGIC - format the response
// MAGIC - persist to S3 (the similarityFilename)

// COMMAND ----------

val similar = model.approxSimilarityJoin(vectorized,vectorized,similarDistance,"JaccardDistance")
                          .filter($"datasetA.nbUrl" =!= $"datasetB.nbUrl")
                          .select($"datasetA.nbUrl".as("nbUrl"),struct($"JaccardDistance".cast("float").as("JaccardDistance"), $"datasetB.nbUrl".as("nbUrl")).as("similar"))
                          .groupBy($"nbUrl")
                          .agg(sort_array(collect_list($"similar")).as("similar"))
                          .sort(size($"similar").desc)

if (overwrite.toLowerCase == "true") {
  similar.write.mode("overwrite").parquet(similarityFilename)
} else {
  similar.write.parquet(similarityFilename)
}

dbutils.notebook.exit("OK")
