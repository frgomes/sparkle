package nest.sparkle.test

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import nest.sparkle.util.ConfigUtil
import nest.sparkle.util.ConfigureLogback

trait SparkleTestConfig {
  var loggingInitialized = false
  
  /** subclasses may override to modify the Config for particular tests */
  def configOverrides: List[(String, String)] = List()

  /** return the outermost Config object. Also triggers logging initialization */
  lazy val rootConfig: Config = {
    val root = ConfigFactory.load()
    val modifiedRoot = ConfigUtil.modifiedConfig(root, configOverrides: _*)
    initializeLogging(modifiedRoot)
    modifiedRoot
  }

  /** setup logging for sparkle. Triggered automatically when the caller accesses
   *  rootConfig. Idempotent.
    */
  def initializeLogging(root: Config) {
    if (!loggingInitialized) {
      val sparkleConfig = root.getConfig("sparkle-time-server")
      ConfigureLogback.configureLogging(sparkleConfig)
    }
  }

}