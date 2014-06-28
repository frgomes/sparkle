package nest.sparkle.store.cassandra
import scala.reflect.runtime.universe._
import nest.sparkle.store.cassandra.serializers._
import spray.json.JsValue

/** Dynamically get a CanSerialize instance from a TypeTag.  */
object RecoverCanSerialize {
  /** mapping from typeTag to CanSerialize for standard types */
  val canSerializers: Map[TypeTag[_], CanSerialize[_]] = Map(
    typeToCanSerialize[Double],
    typeToCanSerialize[Long],
    typeToCanSerialize[Int],
    typeToCanSerialize[Short],
    typeToCanSerialize[String],
    typeToCanSerialize[JsValue],
    typeToCanSerialize[Boolean]
  )

  /** return a mapping from a typetag to an Ordering */
  private def typeToCanSerialize[T: TypeTag: CanSerialize]: (TypeTag[T], CanSerialize[T]) = {
    typeTag[T] -> implicitly[CanSerialize[T]]
  }

  /** return a CanSerialize instance at runtime based a typeTag. */
  def optCanSerialize[T](targetTag: TypeTag[_]) // format: OFF 
      : Option[CanSerialize[T]] = { // format: ON
    val untypedCanSerialize = canSerializers.get(targetTag)
    untypedCanSerialize.asInstanceOf[Option[CanSerialize[T]]]
  }

}