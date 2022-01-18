# Databricks notebook source
# MAGIC 
# MAGIC %md
# MAGIC # Hyperparameter Tuning
# MAGIC 
# MAGIC Let's perform hyperparameter tuning on a random forest to find the best hyperparameters!

# COMMAND ----------

from pyspark.ml.feature import StringIndexer, VectorAssembler

filePath = "/databricks-datasets/learning-spark-v2/sf-airbnb/sf-airbnb-clean.parquet"
airbnbDF = spark.read.parquet(filePath)
(trainDF, testDF) = airbnbDF.randomSplit([.8, .2], seed=42)

categoricalCols = [field for (field, dataType) in trainDF.dtypes if dataType == "string"]
indexOutputCols = [x + "Index" for x in categoricalCols]

stringIndexer = StringIndexer(inputCols=categoricalCols, outputCols=indexOutputCols, handleInvalid="skip")

numericCols = [field for (field, dataType) in trainDF.dtypes if ((dataType == "double") & (field != "price"))]
assemblerInputs = indexOutputCols + numericCols
vecAssembler = VectorAssembler(inputCols=assemblerInputs, outputCol="features")

# COMMAND ----------

# MAGIC %md
# MAGIC ## Random Forest

# COMMAND ----------

from pyspark.ml.regression import RandomForestRegressor
from pyspark.ml import Pipeline

rf = RandomForestRegressor(labelCol="price", maxBins=40, seed=42)
pipeline = Pipeline(stages = [stringIndexer, vecAssembler, rf])

# COMMAND ----------

# MAGIC %md
# MAGIC ## Grid Search
# MAGIC 
# MAGIC There are a lot of hyperparameters we could tune, and it would take a long time to manually configure.
# MAGIC 
# MAGIC Let's use Spark's `ParamGridBuilder` to find the optimal hyperparameters in a more systematic approach [Python](https://spark.apache.org/docs/latest/api/python/pyspark.ml.html#pyspark.ml.tuning.ParamGridBuilder)/[Scala](https://spark.apache.org/docs/latest/api/scala/#org.apache.spark.ml.tuning.ParamGridBuilder).
# MAGIC 
# MAGIC Let's define a grid of hyperparameters to test:
# MAGIC   - maxDepth: max depth of the decision tree (Use the values `2, 4, 6`)
# MAGIC   - numTrees: number of decision trees (Use the values `10, 100`)

# COMMAND ----------

from pyspark.ml.tuning import ParamGridBuilder

paramGrid = (ParamGridBuilder()
            .addGrid(rf.maxDepth, [2, 4, 6])
            .addGrid(rf.numTrees, [10, 100])
            .build())

# COMMAND ----------

# MAGIC %md
# MAGIC ## Cross Validation
# MAGIC 
# MAGIC We are also going to use 3-fold cross validation to identify the optimal maxDepth.
# MAGIC 
# MAGIC ![crossValidation](https://files.training.databricks.com/images/301/CrossValidation.png)
# MAGIC 
# MAGIC With 3-fold cross-validation, we train on 2/3 of the data, and evaluate with the remaining (held-out) 1/3. We repeat this process 3 times, so each fold gets the chance to act as the validation set. We then average the results of the three rounds.

# COMMAND ----------

# MAGIC %md
# MAGIC We pass in the `estimator` (pipeline), `evaluator`, and `estimatorParamMaps` to `CrossValidator` so that it knows:
# MAGIC - Which model to use
# MAGIC - How to evaluate the model
# MAGIC - What hyperparameters to set for the model
# MAGIC 
# MAGIC We can also set the number of folds we want to split our data into (3), as well as setting a seed so we all have the same split in the data [Python](https://spark.apache.org/docs/latest/api/python/pyspark.ml.html#pyspark.ml.tuning.CrossValidator)/[Scala](https://spark.apache.org/docs/latest/api/scala/#org.apache.spark.ml.tuning.CrossValidator).

# COMMAND ----------

from pyspark.ml.evaluation import RegressionEvaluator
from pyspark.ml.tuning import CrossValidator

evaluator = RegressionEvaluator(labelCol="price", 
                                predictionCol="prediction", 
                                metricName="rmse")

cv = CrossValidator(estimator=pipeline, 
                    evaluator=evaluator, 
                    estimatorParamMaps=paramGrid, 
                    numFolds=3, 
                    seed=42)

# COMMAND ----------

# MAGIC %md
# MAGIC **Question**: How many models are we training right now?

# COMMAND ----------

cvModel = cv.fit(trainDF)

# COMMAND ----------

# MAGIC %md
# MAGIC ## Parallelism Parameter
# MAGIC 
# MAGIC Hmmm... that took a long time to run. That's because the models were being trained sequentially rather than in parallel!
# MAGIC 
# MAGIC Spark 2.3 introduced a [parallelism](https://spark.apache.org/docs/latest/api/python/pyspark.ml.html#pyspark.ml.tuning.CrossValidator.parallelism) parameter. From the docs: `the number of threads to use when running parallel algorithms (>= 1)`.
# MAGIC 
# MAGIC Let's set this value to 4 and see if we can train any faster.

# COMMAND ----------

cvModel = cv.setParallelism(4).fit(trainDF)

# COMMAND ----------

# MAGIC %md
# MAGIC **Question**: Hmmm... that still took a long time to run. Should we put the pipeline in the cross validator, or the cross validator in the pipeline?
# MAGIC 
# MAGIC It depends if there are estimators or transformers in the pipeline. If you have things like StringIndexer (an estimator) in the pipeline, then you have to refit it every time if you put the entire pipeline in the cross validator.

# COMMAND ----------

cv = CrossValidator(estimator=rf, 
                    evaluator=evaluator, 
                    estimatorParamMaps=paramGrid, 
                    numFolds=3, 
                    parallelism=4, 
                    seed=42)

pipeline = Pipeline(stages=[stringIndexer, vecAssembler, cv])

pipelineModel = pipeline.fit(trainDF)

# COMMAND ----------

# MAGIC %md
# MAGIC Let's take a look at the model with the best hyperparameter configuration

# COMMAND ----------

list(zip(cvModel.getEstimatorParamMaps(), cvModel.avgMetrics))

# COMMAND ----------

# MAGIC %md
# MAGIC 
# MAGIC Let's see how it does on the test dataset.

# COMMAND ----------

predDF = pipelineModel.transform(testDF)

regressionEvaluator = RegressionEvaluator(predictionCol="prediction", labelCol="price", metricName="rmse")

rmse = regressionEvaluator.evaluate(predDF)
r2 = regressionEvaluator.setMetricName("r2").evaluate(predDF)
print(f"RMSE is {rmse}")
print(f"R2 is {r2}")

