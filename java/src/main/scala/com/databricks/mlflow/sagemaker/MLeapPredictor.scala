package com.databricks.mlflow.sagemaker;

import com.databricks.mlflow.mleap.LeapFrameSchema 
import com.databricks.mlflow.mleap.LeapFrameUtils 
import com.databricks.mlflow.utils.SerializationUtils 

import ml.combust.bundle.BundleFile
import ml.combust.mleap.runtime.MleapSupport._
import ml.combust.mleap.runtime.frame.Transformer

import resource._

import scala.collection.JavaConverters._

class MLeapPredictor(var modelPath : String, var inputSchemaPath : String) extends Predictor {
  val typedModelPath = "file:%s".format(modelPath)
  val bundle = (for(bundleFile <- managed(BundleFile(typedModelPath))) yield {
      bundleFile.loadMleapBundle().get
  }).opt.get
  val pipeline = bundle.root

  val inputSchema = LeapFrameSchema.fromFile(inputSchemaPath)

  def getPipeline() : Transformer = {
      this.pipeline
  }

  override def predict(inputFrame : DataFrame): DataFrame = {
      val frameJson = inputSchema.applyToPandasRecordJson(inputFrame.toJson())
      val leapFrame = LeapFrameUtils.getLeapFrameFromJson(frameJson)
      val predictions = (for(lf <- pipeline.transform(leapFrame);
                             lf2 <- lf.select("prediction")) yield {
          lf2.dataset.map(_.getRaw(0))
      }).get.toList
      DataFrame.fromJson(SerializationUtils.toJson(predictions.asJava))
  }

}
