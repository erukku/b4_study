package org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.exp.iida

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}

import com.intel.analytics.bigdl.dataset.Sample
import com.intel.analytics.bigdl.nn.{Linear, Sequential}
import com.intel.analytics.bigdl.optim.{Adam, L2Regularizer, Optimizer, Trigger}
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.Engine
import org.apache.commons.io.FileUtils
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.miubiq.analytics.indoorloc.converter.UniNaiveConverter
import org.miubiq.analytics.indoorloc.data.Fingerprint
import org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.core.criteria.CsHingeMultiClassifierCriterion
import org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.core.network.{BinaryFeat, EllipsoidFeat, EllipsoidFeatWithThreshold}
import org.miubiq.analytics.indoorloc.util.commons.metric.{EuclideanBetweenLabelDistance, LatLongiDistance}
import org.miubiq.commons.io.MiubiqConf
import org.miubiq.commons.io.parser.rssi_indoorloc.v2016.{Localization201609Parser, MarkerParser}
import org.miubiq.commons.math.brzext.MatFuns
import org.miubiq.commons.mwplot.Plotter

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

object expMethods {
  def main(args: Array[String]): Unit = {

    Plotter.setParser(org.miubiq.commons.adapter.BreezeMwInputParser)

    val settings = expMethodsSettingGenerator.parseConfArgs(args(0))
    val conf = Engine.createSparkConf()
      .setAppName("bigdl-cnn")
      .setMaster("local[*]")
    val sc = new SparkContext(conf)
    Engine.init

    val ss = SparkSession.builder
      .getOrCreate()
    ss.sparkContext.setLogLevel("WARN")

    // load Wi-fi List
    val apFilePath = MiubiqConf.inPath(settings.apFilePath)
    val apSet = FileUtils.readLines(new File(apFilePath)).filter { beacon =>
      beacon != ""
    }.toSet

    val fpConverter = new UniNaiveConverter(
      apSet,
      Some(settings.emptyValue)
    )

    //load markers
    val markers = MarkerParser.loadMarkers(
      ss = ss,
      mapFilePath = MiubiqConf.inPath(settings.markerFilePath)
    ).collect()

    val dist = new LatLongiDistance
    val origin = markers.head

    val markerInfo = markers.zipWithIndex
      .map { markerWithId =>
        val marker = markerWithId._1
        val x = math.signum(marker.latitude - origin.latitude) *
          dist.value(
            (origin.latitude, origin.longitude),
            (marker.latitude, origin.longitude)
          )
        val y = math.signum(marker.longitude - origin.longitude) *
          dist.value(
            (origin.latitude, origin.longitude),
            (origin.latitude, marker.longitude)
          )
        (marker.markerId, markerWithId._2, (x, y))
      }

    // labeling the marker
    val markerIdToLabelMap = markerInfo.map {
      case (markerId, label, _) => (markerId, label)
    }.toMap

    //position of label
    val classPositions = markerInfo.sortBy { case (_, label, _) => label }
      .map { case (_, _, position) => position }

    //save relation between calctime, classid and markerid
    val markeridFile = new File(MiubiqConf.expOutPath("markerid.csv"))
    if (!markeridFile.exists()) markeridFile.createNewFile()

    val markeridPW = new PrintWriter(new BufferedWriter(new FileWriter(markeridFile)))
    markeridPW.println(s"markerid,classid")
    markerInfo.foreach { item =>
      markeridPW.println(s"${item._1},${item._2}")
    }
    markeridPW.close()


    //load dataset
    val dataFilePaths = settings.dataPath.map { filePath =>
      MiubiqConf.inPath(filePath)
    }

    val testFilePaths = settings.testPath.map { filePath =>
      MiubiqConf.inPath(filePath)
    }

    val testList = testFilePaths.map{file =>
      val datasetRaw = Localization201609Parser.loadRSSIFromFile(ss, file).rdd
        .groupBy(rssi => rssi.timestamp)
        .map { rowFingerprint =>
          val fp = for (rssi <- rowFingerprint._2) yield {
            rssi.ssid -> rssi.rssi
          }
          val label = markerIdToLabelMap(rowFingerprint._2.head.markerid)
          val labelTensor = Tensor(Array(label + 1.0), Array(1))
          val fingerprint = Fingerprint(fp.toMap)
          val fpVec = fpConverter.convertToVec(fingerprint).toDenseVector
          Sample(Tensor(fpVec), labelTensor)
        }
      datasetRaw.cache()
      datasetRaw
    }.zipWithIndex

    val fingerprintDatasetList = dataFilePaths.map { file =>
      val datasetRaw = Localization201609Parser.loadRSSIFromFile(ss, file).rdd
        .groupBy(rssi => rssi.timestamp)
        .map { rowFingerprint =>
          val fp = for (rssi <- rowFingerprint._2) yield {
            rssi.ssid -> rssi.rssi
          }
          val label = markerIdToLabelMap(rowFingerprint._2.head.markerid)
          val labelTensor = Tensor(Array(label + 1.0), Array(1))
          val fingerprint = Fingerprint(fp.toMap)
          val fpVec = fpConverter.convertToVec(fingerprint).toDenseVector
          Sample(Tensor(fpVec), labelTensor)
        }
      datasetRaw.cache()

      datasetRaw
    }.zipWithIndex

    // create featurization layer (If isThreshold is true, this code uses threshold-ellipsoid)
    val inputDim = fingerprintDatasetList.head
      ._1
      .take(1)
      .head
      .feature()
      .size(1)
    var featureDim = inputDim * (inputDim - 1) / 2
    if (settings.isThreshold == "true" ||settings.isThreshold == "false"){
      featureDim = featureDim  * settings.means.length
    }
    val ellipsoid = if (settings.isThreshold == "true") {
      println("feature: Threshold ellipsoid")
      new EllipsoidFeatWithThreshold[Double](
        settings.thresholdValue,
        settings.means.toArray,
        settings.sigma
      )
    }
    else if(settings.isThreshold == "false"){
      println("feature: Normal ellipsoid")
      new EllipsoidFeat[Double](
        settings.means.toArray,
        settings.sigma
      )
    }
    else{
      println("feature: Binary")
      new BinaryFeat[Double]()
    }

    // prepare criterion (loss function)
    val distMetric = new EuclideanBetweenLabelDistance(classPositions)
    val criterion = new CsHingeMultiClassifierCriterion[Double](distMetric)

    // prepare output dir
    val outDir = new File(MiubiqConf.expOutPath("results" + File.separator + "model"))
    if (!outDir.exists()) {
      outDir.mkdirs()
    }

    // regularization setting generation
    val lambdaListBuffer = ArrayBuffer.empty[Double]
    val regScale = math.pow(10, 1.0 / settings.regRes)
    var currentReg = settings.minReg
    while (currentReg <= settings.maxReg) {
      lambdaListBuffer += currentReg
      currentReg *= regScale
    }

    val lambdaList = lambdaListBuffer.toArray

    // main experimental code
    var alResults = for (i <- lambdaList.indices) yield {
      val lambda = lambdaList(i)
      println(s"optimize with lambda = ${lambdaList(i)}")
      val cvResults = for (dataId <- fingerprintDatasetList.indices) yield {
        // prepare cv data
        val testData = fingerprintDatasetList(dataId)._1
        val an_testData = testList.map(_._1).reduceLeft((lhs, rhs) => lhs.union(rhs))
        val trainData = fingerprintDatasetList.filterNot { case (_, index) => index == dataId }
          .map(_._1)
          .reduceLeft((lhs, rhs) => lhs.union(rhs))


        // prepare model
        val linearCell = new Linear[Double](
          inputSize = featureDim,
          outputSize = classPositions.length,
          withBias = false,
          wRegularizer = L2Regularizer(lambda),
          initWeight = Tensor(MatFuns.zeros(classPositions.length, featureDim))
        )

        val model = Sequential[Double]()
          .add(ellipsoid)
          .add(linearCell)

        val optimMethod = new Adam[Double](
          learningRate = settings.learningRate,
          learningRateDecay = settings.learningRateDecay
        )

        val optimizer = Optimizer(
          model,
          trainData,
          criterion,
          settings.batchSize
        )

        optimizer
          .setOptimMethod(optimMethod)
          .setEndWhen(Trigger.maxEpoch(settings.epochNum))

        println(s"learn cv $dataId")
        optimizer.optimize()
        println("done")

        // evaluate error for test data
        val errors = testData.map { data =>
          val feat = data.feature()
          val label = data.label().value().toInt

          val score = model.forward(feat)
            .toTensor[Double]
          val estLabel = score.max(1)._2.value().toInt

          val error = distMetric.value(label - 1, estLabel - 1)
          (label, estLabel, error)
        }.collect()

        val terrors = an_testData.map { data =>
          val feat = data.feature()
          val label = data.label().value().toInt

          val score = model.forward(feat)
            .toTensor[Double]
          val estLabel = score.max(1)._2.value().toInt

          val error = distMetric.value(label - 1, estLabel - 1)
          (label, estLabel, error)
        }.collect()

        val outputFileNameForModel = settings.description + "-" + settings.trainName + "results" + File.separator + "model" + File.separator + s"model-$lambda-cv$dataId"
        val outputFileForModel = MiubiqConf.expOutPath(outputFileNameForModel)

        model.saveModule(outputFileForModel, outputFileForModel + "-w")
        (errors,terrors)
      }

      // save test result in all cv
      val resultsAllCv = cvResults.map(_._1).reduce((lhs, rhs) => lhs.union(rhs))
      val tresultsAllCv = cvResults.map(_._2).reduce((lhs, rhs) => lhs.union(rhs))

      val outputFileNameForLambda = "results" + File.separator + s"res-$lambda.csv"
      val outputFileForLambda = new File(MiubiqConf.expOutPath(outputFileNameForLambda))
      if (!outputFileForLambda.exists()) outputFileForLambda.createNewFile()
      val pwInLambda = new PrintWriter(new BufferedWriter(new FileWriter(outputFileForLambda)))
      pwInLambda.println("label,estLabel,error")
      resultsAllCv.foreach { case (label, estLabel, error) =>
        pwInLambda.println(s"$label,$estLabel,$error")
      }
      pwInLambda.close()

      val averageError = resultsAllCv.map(_._3).sum / resultsAllCv.length
      println(s"lambda: $lambda, average error: $averageError")


      val toutputFileNameForLambda = "results" + File.separator + s"tres-$lambda.csv"
      val toutputFileForLambda = new File(MiubiqConf.expOutPath(toutputFileNameForLambda))
      if (!toutputFileForLambda.exists()) toutputFileForLambda.createNewFile()
      val tpwInLambda = new PrintWriter(new BufferedWriter(new FileWriter(toutputFileForLambda)))
      tpwInLambda.println("label,estLabel,error")
      tresultsAllCv.foreach { case (label, estLabel, error) =>
        tpwInLambda.println(s"$label,$estLabel,$error")
      }
      tpwInLambda.close()

      val taverageError = tresultsAllCv.map(_._3).sum / tresultsAllCv.length
      println(s"lambda: $lambda, average error: $taverageError")


      ((lambda, averageError),(lambda,taverageError))
    }


    val tallResults = alResults.map(_._2)
    val allResults = alResults.map(_._1)
    // save average error in each lambda
    val resultFile = new File(MiubiqConf.expOutPath("errors.csv"))
    if (!resultFile.exists()) resultFile.createNewFile()
    val pw = new PrintWriter(new BufferedWriter(new FileWriter(resultFile)))
    pw.println("lambda,average error")
    allResults.foreach { case (lambda, averageError) =>
      pw.println(s"$lambda,$averageError")
    }
    pw.close()

    val tresultFile = new File(MiubiqConf.expOutPath("terrors.csv"))
    if (!tresultFile.exists()) tresultFile.createNewFile()
    val tpw = new PrintWriter(new BufferedWriter(new FileWriter(tresultFile)))
    tpw.println("lambda,average error")
    tallResults.foreach { case (lambda, averageError) =>
      tpw.println(s"$lambda,$averageError")
    }

    tpw.close()

  }

}
