package nest.sparkle.util

import java.nio.ByteBuffer

import spray.json.{ JsValue, JsonFormat }
import scala.reflect.runtime.universe._
import scala.util.Try
import spray.json.JsNull
import nest.sparkle.util.OptionConversion._
import nest.sparkle.util.SparkleJsonProtocol._

case class JsonFormatNotFound(msg: String) extends RuntimeException(msg)

/** Dynamically get a JsonFormat instance from a TypeTag.  This enables generic
  * programming on numeric types where the specific numeric type is unknown
  * until the dynamic type is recovered. e.g. from serialized data over the network
  * or data in a database, the type is recovered at runtime during deserialization)
  */
object RecoverJsonFormat {
  /** mapping from typeTag to JsonFormat for standard types */
  val jsonFormats: Map[TypeTag[_], JsonFormat[_]] = Map(
    typeToFormat[Boolean],
    typeToFormat[Byte],
    typeToFormat[Short],
    typeToFormat[Int],
    typeToFormat[Long],
    typeToFormat[Double],
    typeToFormat[Char],
    typeToFormat[String],
    optionToNullFormat[Boolean],
    optionToNullFormat[Byte],
    optionToNullFormat[Short],
    optionToNullFormat[Int],
    optionToNullFormat[Long],
    optionToNullFormat[Double],
    optionToNullFormat[Char],
    optionToNullFormat[String],
    typeToFormat[JsValue],
    typeToFormat[ByteBuffer]
  )

  /** return a mapping from a typetag to an JsonFormat */
  private def typeToFormat[T: TypeTag: JsonFormat]: (TypeTag[T], JsonFormat[T]) = {
    typeTag[T] -> implicitly[JsonFormat[T]]
  }

  /** return a mapping from a typeTag for an optional value to an JsonFormat 
   *  where None is translated into null. */
  private def optionToNullFormat[T] // format: OFF
      (implicit tag: TypeTag[Option[T]], baseFormat:JsonFormat[T])
      : (TypeTag[Option[T]], JsonFormat[Option[T]]) = { // format: ON
    
    val format = new JsonFormat[Option[T]] {
      override def read(json: JsValue): Option[T] = {
        json match {
          case JsNull => None
          case _      => Some(baseFormat.read(json))
        }
      }
      
      override def write(obj: Option[T]): JsValue = {
        obj match {
          case Some(value) => baseFormat.write(value)
          case None        => JsNull
        }
      }
    }
    (tag, format)
  }

  /** return a JsonFormat instance at runtime based a typeTag. */ // TODO get rid of this, or at least build on tryJsonFormat
  def jsonFormat[T](targetTag: TypeTag[_]) // format: OFF 
      : JsonFormat[T] = { // format: ON
    val untypedFormat = jsonFormats.get(targetTag).getOrElse {
      throw JsonFormatNotFound(targetTag.tpe.toString)
    }

    untypedFormat.asInstanceOf[JsonFormat[T]]
  }

  /** return a JsonFormat instance at runtime based a typeTag. */
  def tryJsonFormat[T](targetTag: TypeTag[_]) // format: OFF 
      : Try[JsonFormat[T]] = { // format: ON
    val untypedFormat: Try[JsonFormat[_]] = jsonFormats.get(targetTag).toTryOr(JsonFormatNotFound(targetTag.tpe.toString))
    untypedFormat.asInstanceOf[Try[JsonFormat[T]]]
  }

}