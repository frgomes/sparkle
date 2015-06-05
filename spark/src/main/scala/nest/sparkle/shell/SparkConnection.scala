package nest.sparkle.shell

import scala.collection.JavaConverters._
import scala.reflect.runtime.universe._
import scala.reflect.ClassTag
import scala.util.control.Exception._
import scala.language.existentials
import java.util.concurrent.atomic.AtomicBoolean
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.spark.{SparkConf, SparkContext}
import com.datastax.spark.connector._
import org.apache.spark.rdd.RDD
import com.datastax.spark.connector.types.TypeConverter
import spray.json.JsValue
import nest.sparkle.util.{ReflectionUtil, Log}
import nest.sparkle.util.ConfigUtil.{modifiedConfig, configForSparkle, sparkleConfigName}
import nest.sparkle.store.cassandra.ColumnTypes
import nest.sparkle.store.cassandra.RecoverCanSerialize
import nest.sparkle.datastream.DataArray


/** All the data from a column packed into an array */
case class ColumnData[K, V](
  columnPath: String, 
  data: DataArray[K, V], 
  valueTypeTag:String  // stringified typeTag. (to enable spark serialization)
)

/**
 * a connection to the spark service, encapsulating the SparkContext and
 *  providing access routines to get RDDs from sparkle columns.
 */
case class SparkConnection(rootConfig: Config, applicationName: String = "Sparkle") extends Log {
  SparkConnection.initializeConverters()

  val sparkleConfig = configForSparkle(rootConfig)

  /** open a connection to cassandra and the spark master */
  lazy val sparkContext: SparkContext = {
    val cassandraHost = {
      // fix after spark-cassandra driver bug #250 (should take multiple hosts)
      val hosts = sparkleConfig.getStringList("sparkle-store-cassandra.contact-hosts")
      hosts.asScala.head
    }

    val sparkConf = new SparkConf(true)
      .set("spark.cassandra.connection.host", cassandraHost)
      .set("spark.local.dir", sparkleConfig.getString("spark.local-dir"))
      .set("spark.executor.memory", sparkleConfig.getString("spark.executor-memory"))
      .set("spark.logConf", "true")
      .set("spark.shuffle.consolidateFiles", "true")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer") //TODO: registerKryoClasses

    val sparkClusterUrl = sparkleConfig.getString("spark.master-url")

    new SparkContext(sparkClusterUrl, applicationName, sparkConf)
  }

  /** return an RDD of all the data in the store 
    *  (note: does a tablescan of all the data tables) */
  def allData:RDD[ColumnData[Long, Any]] = allDataWithKeyType[Long]
  
  /** return an RDD of all the data in the store 
    *  (note: does a tablescan of all the data tables) */
  def allDataWithKeyType[K: TypeTag]: RDD[ColumnData[K, Any]] = {
    val valueTypes = 
      for {
        serialInfo <- ColumnTypes.supportedColumnTypes
        if (serialInfo.directToNative)
      } yield serialInfo.valueSerializer.typedTag

    val rdds: Seq[RDD[ColumnData[K, Any]]] = valueTypes.map { valueType =>
      val rdd = columnsRDD(typeTag[K], valueType)
      rdd.asInstanceOf[RDD[ColumnData[K,Any]]]
    }

    val combined = rdds.reduce((a, b) => a ++ b)
    combined
  }

  /**
   * Return all the events in a given key,value typed column.
   *
   *  Note that this doesn't work for 'high-level' types that map to
   *  primitive columns like String or Bytes. e.g. JsValue column types can't
   *  be safely fetched at present because the underlying cassandra table will
   *  mix all string values in the same table, including some that are not
   *  actually JsValues.
   */
  def columnsRDD[K: TypeTag, V: TypeTag]: RDD[ColumnData[K, V]] = {
    // the name of the table we're fetching from, e.g. bigint0double
    val tableNameAndValueTypeTry =
      for {
        keySerialize <- RecoverCanSerialize.tryCanSerialize[K](implicitly[TypeTag[K]])
        valueSerialize <- RecoverCanSerialize.tryCanSerialize[V](implicitly[TypeTag[V]])
      } yield {
        ( ColumnTypes.serializationInfo[K, V]()(keySerialize, valueSerialize).tableName,
          valueSerialize.nativeType
        )
      }

    val keyspace = sparkleConfig.getString("sparkle-store-cassandra.key-space")
    val sc = sparkContext // spark can't serialize sparkContext directly
    implicit val keyClassTag: ClassTag[K] = ReflectionUtil.classTag
    implicit val valueClassTag: ClassTag[V] = ReflectionUtil.classTag

    val rddTry =
      for {
        nameAndValue <- tableNameAndValueTypeTry
        (tableName, valueType) = nameAndValue
        items <- nonFatalCatch withTry { sc.cassandraTable[RawItem[K, V]](keyspace, tableName) }
      } yield {
        val grouped = items.groupBy(_.columnPath)
        grouped.map {
          case (columnPath, items) =>
            val keys = items.map(_.key).toArray
            val values = items.map(_.value).toArray
            ColumnData(columnPath, DataArray(keys, values), valueType)
        }
      }

    rddTry.recover {
      case err =>
        log.error("unable to fetch cassandra rdd", err)
        sc.emptyRDD[ColumnData[K,V]]
    }.get

  }

  /** shutdown the spark server */
  def close() {
    sparkContext.stop()
  }
}


object SparkConnection {
  private val initialized = new AtomicBoolean
  
  /** Handy way to get a spark connection from the scala repl console */
  // TODO do we need to use the spark repl somehow?
  def console(cassandra:String = "localhost", spark:String = "local") // format: OFF
      : SparkConnection = { // format: ON
    val baseConfig = ConfigFactory.load()
    val config = modifiedConfig(baseConfig, 
        s"$sparkleConfigName.spark.master-url" -> spark,
        s"$sparkleConfigName.sparkle-store-cassandra.contact-hosts" -> Seq(cassandra)
      )
    new SparkConnection(config)
  }
  

  /** install type converters for the cassandra-spark-connector for our custom types */
  def initializeConverters() {
    if (initialized.compareAndSet(false, true)) {
      TypeConverter.registerConverter(JsValueConverter)
    }
  }

  /** spark-cassandra typeconverter to read JsValues */
  object JsValueConverter extends TypeConverter[JsValue] {
    import spray.json._
    def targetTypeTag = implicitly[TypeTag[JsValue]]
    def convertPF = {
      case x: String => println(s"parsing: $x"); x.parseJson
    }
  }

}

/** A single key,value pair as its read from a column */
private case class RawItem[K, V](columnpath: String, key: K, value: V) {
  // note: the casing of "columnpath" is per spark cassandra connector requirements
  def columnPath = columnpath
}
