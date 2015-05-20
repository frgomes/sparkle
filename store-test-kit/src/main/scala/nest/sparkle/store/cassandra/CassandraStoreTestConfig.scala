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

package nest.sparkle.store.cassandra
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.Success

import akka.actor._
import com.typesafe.config.Config
import nest.sparkle.loader.FilesLoader
import nest.sparkle.store._
import nest.sparkle.test.SparkleTestConfig
import nest.sparkle.util.FutureAwait.Implicits._
import nest.sparkle.util.RandomUtil.randomAlphaNum
import nest.sparkle.util.{ConfigUtil, Resources}

object CassandraStoreFixture {
  /** recreate the database and a test column */
  def withTestDb[T](sparkleConfig:Config, keySpace:String)(fn: CassandraReaderWriter => T): T = {
    val storeConfig = sparkleConfig.getConfig("sparkle-store-cassandra")

    val modifiedSparkleConfig = ConfigUtil.modifiedConfig(sparkleConfig,
      "sparkle-store-cassandra.key-space" -> keySpace
    )

    val testContactHosts = storeConfig.getStringList("contact-hosts").asScala.toSeq
    CassandraStore.dropKeySpace(testContactHosts, keySpace)
    val notification = new WriteNotification
    val store = CassandraStore.readerWriter(modifiedSparkleConfig, notification)

    try {
      fn(store)
    } finally {
      store.close()
    }
  }
}
/** a test jig for running tests using cassandra. */
trait CassandraStoreTestConfig extends SparkleTestConfig {
  /** override .conf file for store tests */
  override def testConfigFile: Option[String] = Some("sparkle-store-tests")

  /** the 'sparkle' level Config */
  lazy val sparkleConfig: Config = ConfigUtil.configForSparkle(rootConfig)

  /** subclasses should define their own keyspace so that tests don't interfere with each other  */
  def testKeySpace: String = getClass.getSimpleName

  /** recreate the database and a test column */
  def withTestDb[T](fn: CassandraReaderWriter => T): T = {
    CassandraStoreFixture.withTestDb(sparkleConfig, testKeySpace)(fn)
  }

  /** run a function within a test actor system */
  def withTestActors[T](fn: ActorSystem => T): T = {
    ActorSystemFixture.withTestActors("cassandra-store-test-config")(fn)
  }

  /** run a function after getting a writeable column from the store */
  def withTestColumn[T: CanSerialize, U: CanSerialize](store: CassandraStoreWriter) // format: OFF
      (fn: (WriteableColumn[T, U], String) => Unit): Boolean = { // format: ON
    val testColumn = s"latency.p99.${randomAlphaNum(3)}"
    val testId = "server1"
    val testColumnPath = s"$testId/$testColumn"
    val column = store.writeableColumn[T, U](testColumnPath).await
    try {
      fn(column, testColumnPath)
    }
    true  // so that it can be used in a property that throws to report errors
  }
  
  /** try loading a known file and check the expected column for results */
  def testLoadFile[T, U, V](resourcePath: String, columnPath: String)(fn: Seq[Event[U, V]] => T) {
    val filePath = Resources.filePathString(resourcePath)

    withTestDb { testDb =>
      withTestActors { implicit system =>
        import system.dispatcher
        val complete = onLoadComplete(testDb, resourcePath)
        new FilesLoader(sparkleConfig, filePath, resourcePath, testDb)
        complete.await(4.seconds)

        val column = testDb.column[U, V](columnPath).await
        val read = column.readRangeOld(None, None)
        val results = read.initial.toBlocking.toList
        fn(results)
      }
    }
  }


  /** run a test function after loading some data into cassandra */
  def withLoadedFile[T](resourcePath: String) // format: OFF
      (fn: (CassandraReaderWriter, ActorSystem) => T): T = { // format: ON    
    withLoadedFileInResource(resourcePath)(fn)
  }

  /** Run a test function after loading some data into cassandra.
    * @param fn - test function to call after the data has been loaded.
    * @param resourcePath - directory in the classpath resources to load (recursively)
    */
  def withLoadedFileInResource[T](resourcePath: String) // format: OFF
      (fn: (CassandraReaderWriter, ActorSystem) => T): T = { // format: ON

    withTestDb { testDb =>
      withTestActors { implicit actorSystem =>
        withLoadedResource(resourcePath, testDb) {
          fn(testDb, actorSystem)
        }
      }
    }
  }

  /** run a test function after loading some data into cassandra */
  def withLoadedResource[T](resourcePath: String, store:ReadWriteStore)
                           (fn: => T)
                           (implicit actorSystem: ActorSystem): T = {
    val complete = onLoadComplete(store, resourcePath)
    val loadPath = Resources.filePathString(resourcePath)
    val loader = new FilesLoader(sparkleConfig, loadPath, resourcePath, store)
    complete.await
    val result =
      try {
        fn
      } finally {
        loader.close()
      }
    result

  }

  /** return a future that completes when the loader reports that loading is complete */
  def onLoadComplete(store:Store, path: String): Future[Unit] = {
    val promise = Promise[Unit]()
    def complete(): Unit = if (!promise.isCompleted) promise.complete(Success(Unit))

    store.writeListener.listen(path).subscribe {writeEvent:WriteEvent =>
      writeEvent match {
        case FileLoaded(`path`)      => complete()
        case DirectoryLoaded(`path`) => complete()
        case ListenRegistered        => // ignore
        case DirectoryLoaded(_)      => // ignore
        case FileLoaded(_)           => // ignore
        case c:ColumnUpdate[_]       => // ignore
      }
    }

    promise.future
  }

}

