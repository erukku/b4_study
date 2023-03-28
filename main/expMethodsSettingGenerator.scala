package org.miubiq.analytics.indoorloc.thresh_ellipsoid.fy2020proto1.exp.iida

import org.miubiq.commons.exp.config.parser.ArgConfParser

object expMethodsSettingGenerator {
  private var parser: ArgConfParser = _

  def parseConfArgs(path: String): IndoorlocSetting = {
    parser = new ArgConfParser(path)
    IndoorlocSetting()
  }

  case class IndoorlocSetting(description: String =
                              parser.getStringOrDefault(
                                "description.purpose",
                                "theshold ellipsoid related experiment"
                              ),
                              trainName: String =
                              parser.getStringOrDefault(
                                "dataset.trainName",
                                "UNKO"
                              ),
                              dataPath: List[String] =
                              parser.getStringListOrDefault(
                                "dataset.dataPath",
                                List.empty[String]
                              ),
                              testPath: List[String] =
                              parser.getStringListOrDefault(
                                "dataset.testPath",
                                List.empty[String]
                              ),
                              apFilePath: String =
                              parser.getStringOrDefault(
                                "dataset.apFilePath",
                                ""
                              ),
                              markerFilePath: String =
                              parser.getStringOrDefault(
                                "dataset.markerFilePath",
                                ""
                              ),
                              cvNum: Int = //currently cvnum is fixed as # of files
                              parser.getIntOrDefault(
                                "dataset.cvNum",
                                1
                              ),
                              means: List[Double] =
                              parser.getDoubleListOrDefault(
                                "feature.means",
                                List.empty[Double]
                              ),
                              sigma: Double =
                              parser.getDoubleOrDefault(
                                "feature.sigma",
                                2.0
                              ),
                              emptyValue: Int =
                              parser.getIntOrDefault(
                                "feature.emptyValue",
                                -200
                              ),
                              isThreshold: String =
                              parser.getStringOrDefault(
                                "feature.isThreshold",
                                "false"
                              ),
                              thresholdValue: Double =
                              parser.getDoubleOrDefault(
                                "feature.thresholdValue",
                                -70.0
                              ),
                              minReg: Double =
                              parser.getDoubleOrDefault(
                                "modelHyperParam.minReg",
                                0.001
                              ),
                              maxReg: Double =
                              parser.getDoubleOrDefault(
                                "modelHyperParam.maxReg",
                                0.01
                              ),
                              regRes: Int =
                              parser.getIntOrDefault(
                                "modelHyperParam.regRes",
                                1
                              ),
                              epochNum: Int =
                              parser.getIntOrDefault(
                                "optimParam.epochNum",
                                1000
                              ),
                              batchSize: Int =
                              parser.getIntOrDefault(
                                "optimParam.batchSize",
                                128
                              ),
                              learningRate: Double =
                              parser.getDoubleOrDefault(
                                "optimParam.learningRate",
                                0.001
                              ),
                              learningRateDecay: Double =
                              parser.getDoubleOrDefault(
                                "optimParam.learningRateDecay",
                                0.0001
                              )
                             )

}
