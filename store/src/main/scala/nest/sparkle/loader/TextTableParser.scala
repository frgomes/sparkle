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

package nest.sparkle.loader

import java.lang.{ Double => JDouble }
import java.lang.{ Long => JLong }
import java.util.zip.DataFormatException
import scala.collection.mutable
import scala.collection.mutable.ArrayBuilder
import scala.util.control.Exception.{ catching, nonFatalCatch }
import scala.util.Try
import scala.reflect.runtime.universe._
import scala.reflect._
import scala.language.existentials

import org.joda.time.format.ISODateTimeFormat

import nest.sparkle.util.{ GuessType, ParseStringTo, Log }

object TextTableParser extends Log {
  /** Parse all the data from an iterator that produces an array of strings and a column map that describes which
    * columns have which names.
    */
  def rowParser(originalRows: Iterator[Array[String]], columnMap: ColumnMap): Try[RowInfo] = {
    val (columnInfos, rows) = guessColumnTypes(columnMap, originalRows)
    val rowIterator: Iterator[RowData] = makeRowIterator(rows, columnMap, columnInfos)

    val result = Try {
      ConcreteRowInfo(valueColumns = columnInfos,
        keyColumn = true, // LATER support loading files w/o a key column
        rows = rowIterator
      )
    }

    result.failed.map { err => log.warn("error parsing rows", err) }
    result
  }

  /** Sort data in the form produced by zipWithIndex. Expects 2 element tuples where
    * the second value has an Ordering. Returns a sequence of the first element.
    */
  private def sortByZippedIndex[T, U: Ordering](seq: Seq[(T, U)]): Seq[T] = {
    val sorted = seq.sortBy { case (value, index) => index }
    sorted.map { case (value, index) => value }
  }

  case class TextParserNotFound(msg: String) extends RuntimeException(msg)
  /** Return the column types of the columns in the form of Seq of StringColumnInfo. If
    * the column names don't include type ascriptions, scans
    * the column values if necessary to guess at their types. Returns a new iterator
    * for the rows so that the caller will re-iterate rows evaluated by guessing.
    */
  private def guessColumnTypes(columnMap: ColumnMap, rows: Iterator[Array[String]]) // format: OFF
     :(Seq[StringColumnInfo[_]], Iterator[Array[String]]) = { // format: ON
    val guessSize = 1000 // check this many rows to guess the type of the data
    val guessRows = rows.take(guessSize).toSeq

    val stringsByColumn =
      columnMap.mapValueColumns { (columnName, index) =>
        val values = guessRows.map { row => row(index) }
        (columnName, index, values)
      }

    val columnInfos = stringsByColumn.map {
      case (columnName, index, values) =>
        val (parser, revisedName) =
          columnName match {
            case TypedColumnHeader(tag) =>
              val parseTo =
                ParseStringTo.findParser(tag).getOrElse {
                  throw TextParserNotFound(tag.tpe.termSymbol.name.decodedName.toString)
                }
              (parseTo, TypedColumnHeader.withoutSuffix(columnName))
            case _ =>
              (GuessType.parserTypeFromSampleStrings(values), columnName)
          }
        StringColumnInfo(revisedName, index, parser)
    }

    val resetRows = guessRows.toIterator ++ rows
    (columnInfos, resetRows)
  }

  /** return an iterator that will parse incoming string arrays into parsed numeric data */
  private def makeRowIterator(rows: Iterator[Array[String]], columnMap: ColumnMap,
                              valueColumns: Seq[StringColumnInfo[_]]): Iterator[RowData] = {

    val valueIndices: Seq[Int] = columnMap.data.map { case (name, index) => index }.toSeq

    /** an iterator that will pull data from the source parser */
    val iterator = new Iterator[RowData] {
      def hasNext: Boolean = rows.hasNext
      def next(): RowData = {
        val row = rows.next()
        val timeString = row(columnMap.key)
        val key = parseTime(timeString).getOrElse {
          formatError(s"couldn't parse the time in line ${row.mkString(",")}")
        }
        val values: Seq[Option[Any]] = valueColumns.map {
          case StringColumnInfo(name, index, parser) =>
            val valueString = row(index)
            val result = nonFatalCatch opt { parser.parse(valueString) }
            result.orElse { log.warn(s"can't parse $valueString in column $name"); None }
        }
        val rowData = RowData(Some(key) +: values)
        rowData
      }
    }

    iterator
  }

  object IsoDateParse { // TODO move this to ParseStringTo
    import com.github.nscala_time.time.Imports._
    val isoParser = ISODateTimeFormat.dateHourMinuteSecondMillis().withZoneUTC()
    def unapply(string: String): Option[Long] = {
      isoParser.parseOption(string) map (_.getMillis)
    }
  }

  object LongParse {
    def unapply(string: String): Option[Long] = nonFatalCatch opt JLong.parseLong(string)
  }

  object DoubleParse {
    def unapply(string: String): Option[Double] = nonFatalCatch opt JDouble.parseDouble(string)
  }

  /** Parse a date in epoch seconds, epoch milliseconds or in the format:  2013-02-15T01:32:50.186 */
  protected[loader] def parseTime(timeString: String): Option[Long] = {
    timeString match {
      case IsoDateParse(millis) => Some(millis)
      case LongParse(millis)    => Some(millis)
      case DoubleParse(seconds) => Some((seconds * 1000.0).toLong)
      case _                    => None
    }
  }

  /** optionally parse a double */
  private def parseDouble(str: String): Option[Double] = {
    nonFatalCatch opt JDouble.parseDouble(str)
  }

  private def formatError(msg: String): Nothing = throw new DataFormatException(msg)

}
