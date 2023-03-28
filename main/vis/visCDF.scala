package org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.exp.iida.vis

import com.intel.analytics.bigdl.utils.Engine
import org.apache.spark.SparkContext
import org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.exp.iida.vis.visCDFSettingGenerator
import org.miubiq.commons.io.MiubiqConf
import org.miubiq.commons.mwplot.Plotter
import java.io.{BufferedReader, File, FileReader}

import org.miubiq.analytics.indoorloc.ellipsoid.ubicomp2017_re.exp.util.DataLoadUtil
import org.miubiq.analytics.indoorloc.util.commons.visualize.CDFPlotter
import org.miubiq.commons.io.MiubiqConf
import org.miubiq.commons.mwplot.Plotter
import org.miubiq.commons.util.figure.FigureUtility

import scala.collection.mutable.ArrayBuffer

object visCDF {
  def main(args: Array[String]): Unit = {

    Plotter.setParser(org.miubiq.commons.adapter.BreezeMwInputParser)

    val settings = visCDFSettingGenerator.parseConfArgs(args(0))
    val conf = Engine.createSparkConf()
      .setAppName("bigdl-cnn")
      .setMaster("local[*]")
    val sc = new SparkContext(conf)
    Engine.init

    val devTrain = settings.trainDevice
    /*
    val trainErrors = settings.resultDataPath1.map { resultPath =>

      val archivePath = resultPath.split("/")
      val fullArchivePath = MiubiqConf.expArchivesPath(archivePath.head)
      val fullPath = fullArchivePath + File.separator + archivePath.tail.mkString(File.separator)
      DataLoadUtil.loadResultData(fullPath)
    }
    */
    val testFilePaths:Array[String] = Array(
      settings.resultDataPath1,
      settings.resultDataPath2,
      settings.resultDataPath3,
      settings.resultDataPath4,
      settings.resultDataPath5,
      settings.resultDataPath6
    )

    val lineSpecs = List(
      "c-",
      "c--",
      "g-",
      "g--",
      "m-",
      "m--",
      "b-",
      "b--",
      "r-",
      "r--"
    )



    val testErrors = testFilePaths.map { resultPath => {
      println(resultPath)
      val result = ArrayBuffer.empty[Double]
      val resultReader = new BufferedReader(new FileReader(new File(resultPath)))
      var line = resultReader.readLine()
      line = resultReader.readLine()
      while (line != null) {
        val spLine = line.split(",")
        result += spLine(2).toDouble
        line = resultReader.readLine()
      }
      result.toArray
    }
    }

    val legends = settings.testDevices.toArray


    CDFPlotter.plotCDF(
      testErrors,
      legends,
      MiubiqConf.expOutPath(s"$devTrain"),
      Some(lineSpecs),
      15
    )

    println("finish plot CDF")
    //val resultList = trainErrors ++ testErrors.flatten

    /*
    val resultList = testErrors.flatten
    val resultNumMin = resultList.min

    val flippedResultList = for (i <- 0 until resultNumMin) yield {
      resultList.map(result => result(i)).toArray
    }

    */
    var flippedResultList = testErrors
    var po = testErrors.zipWithIndex.map {
      case (item, index) => {
          item.map{data => legends(index)}
      }
    }

    po

    /*
    val trainLabels = Array(
      s"${lb(0)} $devTrain",
      s"${lb(1)} $devTrain",
      s"${lb(2)} $devTrain",
      s"${lb(3)} $devTrain",
      s"${lb(4)} $devTrain"
    )

    val testLabels = for (devId <- settings.testDevices.indices) yield {
      val devTest = settings.testDevices(devId)
      Array(
        s"${lb(0)} $devTest",
        s"${lb(1)} $devTest",
        s"${lb(2)} $devTest",
        s"${lb(3)} $devTest",
        s"${lb(4)} $devTest"
    }
    val labelLists = trainLabels ++ testLabels.flatten
    */



    val flip = flippedResultList.flatMap(x=>x)

    val p = po.flatMap(x=>x)

    println(flip.size,p.size)

    Plotter.boxplot(flip,
      p,
      "Whisker", Array(100),
      "LabelOrientation", "inline"
    )


    Plotter.xlabel("feature")
    Plotter.ylabel("error [m]")

    FigureUtility.saveMultiformatFigs(
      figId = 1,
      baseName = MiubiqConf.expOutPath(s"box-$devTrain"),
      isTransparentInAxes = true,
      isTransparentOutOfAxes = true,
      save2pdf = true
    )

    Plotter.close(1)

  }

}