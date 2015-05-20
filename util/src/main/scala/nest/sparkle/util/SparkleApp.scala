/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.util

import java.nio.file.{Files, Paths}

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem

import org.clapper.argot.{ArgotParser, ArgotUsageException}
import org.clapper.argot.ArgotConverters._

import nest.sparkle.measure.ConfiguredMeasurements
import nest.sparkle.metrics.MetricsSupport

/** a utility trait for making a main class that uses Argot command line parsing,
  * loads and modifies the configuration, configures logging, and starts
  * metrics reporting.
  */
trait SparkleApp 
  extends App
{
  /** subclasses may implement to include additional conf files */
  def extraConfFiles:Seq[String] = Seq()
  def extraConfResources:Seq[String] = Seq()

  /** Subclasses may implement to override settings in the .conf file */
  def overrides: Seq[(String, Any)] = Seq()

  def appName: String = "SomeSparkleApp"
  def appVersion: String = "0.1"
  
  /** Parser to define additional command line parameters */
  val parser = new ArgotParser(appName, preUsage = Some(appVersion))
  val help = parser.flag[Boolean](List("h", "help"), "show this help")
  val confFile = parser.option[String](List("conf"), "path", "path to a .conf file")

  lazy val rootConfig = {
    confFile.value.foreach(validateNormalFile)
    val baseConfig = ConfigUtil.configFromFilesAndResources(
      confFile.value.toSeq ++ extraConfFiles, extraConfResources)
    val config = ConfigUtil.modifiedConfig(baseConfig, overrides: _*)
    ConfigUtil.dumpConfigToFile(config)
    config
  }
  lazy val sparkleConfig = ConfigUtil.configForSparkle(rootConfig)
  implicit lazy val system = ActorSystem("sparkleApp", sparkleConfig)
  import system.dispatcher
  implicit lazy val measurements = new ConfiguredMeasurements(rootConfig)
  

  /** Initialize process global items.
    * 
    * This should be called once at the start of a process.
    * 
    * Load the configuration and apply any overrides.
    * Configure the specified logger. See note below.
    * Optionally start reporting to a Graphite server.
    * Optionally start an http endpoint for requesting metrics (in Graphite format)
    * 
    * @return root Config object w/overrides applied.
    */
  def initialize(): Unit = {
    parse()
  
    // Configure logging. Causes rootConfig to be instantiated.
    LogUtil.configureLogging(rootConfig)

    // Start optional metrics graphite reporting.
    val graphiteConfig = sparkleConfig.getConfig("metrics.graphite")
    if (graphiteConfig.getBoolean("reporter.enable")) {
      MetricsSupport.startGraphiteReporter(graphiteConfig)
    }
    
    // Start an http server to return metrics values in graphite format.
    if (graphiteConfig.getBoolean("http.enable")) {
      Await.result(MetricsSupport.startHttpServer(graphiteConfig), 20 seconds)
    }
  }

  /** validate that a file exists and is not a directory */
  def validateNormalFile(fileName:String): Unit = {
    val path = Paths.get(fileName)
    if (!Files.exists(path)) {
      Console.err.println(s"file does not exist: $fileName" )
      sys.exit(1)
    }
    if (Files.isDirectory(path)) {
      println(s"file is a directory, expected a file: $fileName" )
      sys.exit(1)
    }
  }

  /**
   * Parse the command line parameters.
   */
  private def parse() {
    try {
      parser.parse(args)

      help.value.foreach { v =>
        Console.println(parser.usageString())
        sys.exit(0)
      }
      
      confFile.value.foreach {fn => println(s"--conf $fn")}

    } catch {
      case e: ArgotUsageException =>
        println(e.message)
        sys.exit(1)
    }
  }

  def shutdown(): Unit = {
    measurements.close()
    system.shutdown()
  }
}
