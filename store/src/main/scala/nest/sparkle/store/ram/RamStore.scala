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

package nest.sparkle.store.ram

import scala.collection.mutable
import scala.concurrent.Future

import nest.sparkle.store._
import nest.sparkle.store.cassandra.{CanSerialize, WriteableColumn}
import nest.sparkle.util.OptionConversion.OptionFuture

/** A java heap resident database of Columns */
class WriteableRamStore extends ReadWriteStore {
  private val columns = mutable.Map[String, RamColumn[_, _]]()
  
  override val writeListener = new WriteNotification()

  /** Return the entity paths associated with the given lookup key. If none, the Future
    * if failed with EntityNotFoundForLookupKey. */
  override def entities(lookupKey: String): Future[Seq[String]] = {
    ???
  }

  /** Return the specified entity's column paths. */
  override def entityColumnPaths(entityPath: String): Future[Seq[String]] = {
    ???
  }

  /** Return the specified leaf dataSet's column paths, where a leaf dataSet is
    * a dataSet with only columns (not other dataSets) as children */
  override def leafDataSetColumnPaths(dataSet: String): Future[Seq[String]] = {
    ???
  }

  /** return the dataset for the provided name name or path (fooSet/barSet/mySet).  */
  override def dataSet(name: String): Future[DataSet] = {
    if (dataSetColumnPaths(name).isEmpty) {
      Future.failed(DataSetNotFound(name))
    } else {
      Future.successful(RamDataSet(this, name))
    }
  }

  /** return a column from a columnPath e.g. "fooSet/barSet/columName". */
  override def column[T, U](columnPath: String): Future[Column[T, U]] = {
    val optTypedColumn = columns.get(columnPath).map { _.asInstanceOf[Column[T, U]] }
    optTypedColumn.toFutureOr(ColumnNotFound(columnPath))
  }

  /** return a WriteableColumn for the given columnPath.  (returned as a future
   *  for compatibility with other slower Storage types) */
  override def writeableColumn[T: CanSerialize, U: CanSerialize](columnPath: String) // format: OFF
      : Future[WriteableColumn[T, U]] = { // format: ON
    implicit val keyTag = implicitly[CanSerialize[T]].typedTag
    implicit val valueTag = implicitly[CanSerialize[U]].typedTag
    val ramColumn = new WriteableRamColumn[T, U]("foo")
    columns += columnPath -> ramColumn
    Future.successful(ramColumn)
  }

  override def format(): Unit = ???
  override def writeNotifier: nest.sparkle.store.WriteNotifier = ???

  /**
   * Return the columnPaths of the children of the DataSet
   * @param name DataSet to return children for
   * @return Seq of ColumnPaths of children.
   */
  private[ram] def dataSetColumnPaths(name: String): Seq[String] = {
   columns.keys.filter {columnPath =>
      val (dataSetName, _) = Store.setAndColumn(columnPath)
      dataSetName == name
    }.toSeq
  }

  /** free memory we might have been hanging on to */
  def close(): Unit = {
    columns.clear()
  }

}

