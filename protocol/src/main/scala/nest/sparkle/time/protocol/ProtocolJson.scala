package nest.sparkle.time.protocol

import spray.json._

import nest.sparkle.store.Event
import nest.sparkle.store.cassandra.MilliTime

/** Spray json converters for transform parameters */
object TransformParametersJson extends DefaultJsonProtocol {
  implicit def RangeIntervalFormat[T: JsonFormat]: RootJsonFormat[RangeInterval[T]] =
    jsonFormat3(RangeInterval.apply[T])
  implicit def SummaryParametersFormat[T: JsonFormat]: RootJsonFormat[SummaryParameters[T]] =
    jsonFormat8(SummaryParameters.apply[T])
  implicit def IntervalParametersFormat[T: JsonFormat]: RootJsonFormat[IntervalParameters[T]] =
    jsonFormat3(IntervalParameters.apply[T])
  implicit def RawParametersFormat[T: JsonFormat]: RootJsonFormat[RawParameters[T]] =
    jsonFormat1(RawParameters.apply[T])
}

/** Spray json encoder/decoder for JsonStreamType */
object JsonStreamTypeFormat extends DefaultJsonProtocol {
  implicit object JsonStreamTypeFormatter extends JsonFormat[JsonStreamType] {
    def write(jsonStreamType: JsonStreamType): JsValue = JsString(jsonStreamType.name)

    def read(value: JsValue): JsonStreamType = value match {
      case JsString(KeyValueType.name) => KeyValueType
      case JsString(ValueType.name)    => ValueType
      case _                           => throw new DeserializationException("JsonStreamType expected")
    }
  }
}

/** spray json converters for request messages */
object RequestJson extends DefaultJsonProtocol {
  implicit val StreamRequestFormat = jsonFormat5(StreamRequest)
  implicit val RealmToServerFormat = jsonFormat3(RealmToServer)
  implicit val LoggableRealmToServerFormat = jsonFormat3(LoggableRealmToServer.apply)
  implicit val StreamRequestMessageFormat = jsonFormat5(StreamRequestMessage)
  implicit val CustomSelectorFormat = jsonFormat2(CustomSelector)
}

/** spray json converters for response messages */
object ResponseJson extends DefaultJsonProtocol {
  import JsonStreamTypeFormat._
  implicit val StreamFormat = jsonFormat5(Stream)
  implicit val StreamsFormat = jsonFormat1(Streams)
  import RequestJson.RealmToServerFormat
  implicit val RealmToClientFormat = jsonFormat1(RealmToClient)
  implicit val StreamsMessageFormat = jsonFormat5(StreamsMessage)
  implicit val StatusFormat = jsonFormat2(Status)
  implicit val StatusMessageFormat = jsonFormat5(StatusMessage)
  implicit val UpdateFormat = jsonFormat3(Update)
  implicit val UpdateMessageFormat = jsonFormat5(UpdateMessage)
}

/** spray json converters for MilliTime */
object TimeJson extends DefaultJsonProtocol {
  implicit object MilliTimeFormat extends JsonFormat[MilliTime] {
    def write(milliTime: MilliTime): JsValue = JsNumber(milliTime.millis)
    def read(value: JsValue): MilliTime = value match {
      case JsNumber(millis) => MilliTime(millis.toLong)
      case _                => throw new DeserializationException("MilliTime expected")
    }
  }
}

/** spray json converters for Event */
object EventJson extends DefaultJsonProtocol {
  implicit def EventFormat[T: JsonFormat, U: JsonFormat]: JsonFormat[Event[T, U]] = { // SCALA, avoid the def here
    val keyFormat = implicitly[JsonFormat[T]]
    val valueFormat = implicitly[JsonFormat[U]]

    new JsonFormat[Event[T, U]] {
      def write(event: Event[T, U]): JsValue = {
        JsArray(keyFormat.write(event.key), valueFormat.write(event.value))
      }

      def read(value: JsValue): Event[T, U] = value match {
        case JsArray(Seq(elem1, elem2)) =>
          val key = keyFormat.read(elem1)
          val value = valueFormat.read(elem2)
          Event(key, value)
        case _ => throw new DeserializationException("JsonStreamType expected")

      }
    }
  }
}
