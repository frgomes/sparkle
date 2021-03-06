/* Copyright 2013  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */
package nest.sparkle.loader

/**
 * Trait that provides serialization/deserialization implementations for type T
 * @tparam T
 */
trait SparkleSerializer[T] {
  def toBytes(data: T): Array[Byte];
  def fromBytes(bytes:Array[Byte]): T;
}

/** Indicates serialization/deserialization issues */
case class SparkleSerializationException(cause: Throwable) extends RuntimeException(cause)
case class SparkleDeserializationException(cause: Throwable) extends RuntimeException(cause)
