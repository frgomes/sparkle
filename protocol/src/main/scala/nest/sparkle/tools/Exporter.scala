package nest.sparkle.tools

import scala.concurrent.{ExecutionContext, Future}
import scala.language.existentials
import scala.reflect.runtime.universe._

import nest.sparkle.store.Column
import nest.sparkle.store.EventGroup.transposeSlices
import nest.sparkle.store.Store
import nest.sparkle.util.Log
import nest.sparkle.util.ObservableFuture.WrappedObservable
import nest.sparkle.util.StringUtil.lastPart

/** Base trait for exporting data from the column Store in tabular form.
  * Subclasses should call leafDatSet() to retrieve a table of data from all
  * the columns in a leaf DataSet, or column() to retrieve a table of data
  * from a particular column.
  */
trait Exporter extends Log {
  implicit def execution: ExecutionContext
  def store: Store

  /** a table of data along with the name and type of its columns. */
  case class Tabular(rows: Future[Seq[Row]], columns: Seq[NameAndType])
  case class Row(values: Seq[Option[Any]])
  case class NameAndType(name: String, typed: TypeTag[_])

  /** return a table of data from all the columns in a leaf dataSet, where
    * a leaf dataSet is a dataSet with only columns (not other dataSets) as
    * children */
  protected def leafDatSet(dataSet: String): Future[Tabular] = {
    store.leafDataSetColumnPaths(dataSet).flatMap { columnPaths =>
      exportRows(columnPaths)
    }
  }

  /** return a table of data from a particular column */
  protected def column(columnPath: String): Future[Tabular] = {
    exportRows(Seq(columnPath))
  }

  /** return a table of data from the specified columns */
  private def exportRows(columnPaths: Seq[String]): Future[Tabular] = {

    val futureNameColumns = fetchColumns(columnPaths)

    futureNameColumns.map { nameColumns =>
      val (names, columns) = nameColumns.unzip

      val columnTypes = {
        val keyTypes = columns.map(_.keyType)
        require(keyTypes.forall { _ == keyTypes(0) })
        val valueTypes = columns.map(_.valueType)
        keyTypes.head +: valueTypes
      }

      val columnNames = {
        val columnValueNames = names.map { lastPart(_) }
        "key" +: columnValueNames
      }

      val namesAndTypes = columnNames zip columnTypes map { case (name, typed) => NameAndType(name, typed) }
      val rows = fetchAndCompositeRows(columns)
      Tabular(rows, namesAndTypes)
    }
  }

  /** return a future containing Column from the store along with their columnPath */
  private def fetchColumns(names: Seq[String]): Future[Seq[(String, Column[_, _])]] = {
    val futureColumns =
      names.map { name =>
        store.column[Any, Any](name).map { column => name -> column }
      }

    Future.sequence(futureColumns)
  }

  /** fetch all the events from a set of columns and composite the column data into
    * into tabular rows
    */
  private def fetchAndCompositeRows(columns: Seq[Column[_, _]]): Future[Seq[Row]] = {
    val events = columns.map { column =>
      val castColumn = column.asInstanceOf[Column[Any, Any]]
      castColumn.readRangeOld().initial.toFutureSeq
    }

    Future.sequence(events).map { allEvents =>
      // convert from columnar form to tabular form. 
      transposeSlices(allEvents).map { values => Row(values) }
    }
  }

}
