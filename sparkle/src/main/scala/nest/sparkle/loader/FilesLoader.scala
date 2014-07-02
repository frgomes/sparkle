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

import nest.sparkle.util.WatchPath
import nest.sparkle.util.WatchPath._
import akka.actor.ActorSystem
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import nest.sparkle.store.cassandra.serializers._
import scala.concurrent.Future
import nest.sparkle.store.cassandra.WriteableColumn
import scala.collection.immutable.Range
import nest.sparkle.store.WriteableStore
import nest.sparkle.store.Event
import nest.sparkle.util.Exceptions.NYI
import scala.concurrent.Promise
import scala.util.Success
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import nest.sparkle.util.PathWatcher
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import nest.sparkle.util.TryToFuture._
import nest.sparkle.util.Log
import com.typesafe.config.Config
import nest.sparkle.store.cassandra.RecoverCanSerialize
import nest.sparkle.store.cassandra.CanSerialize

/** emitted to the event stream when the column has been completely loaded */
case class LoadComplete(columnPath: String)
case class FileLoadComplete(relativePath: String)

case class LoadPathDoesNotExist(path: String) extends RuntimeException

object FilesLoader extends Log {
  def apply(sparkleConfig: Config, rootDirectory: String, store: WriteableStore, strip: Int = 0) // format: OFF
      (implicit system: ActorSystem): FilesLoader = { // format: ON
    new FilesLoader(sparkleConfig, rootDirectory, store, strip)
  }
}

/** Load all the events in the csv/tsv files in a directory or load a single file.
  * The directory or file must exist.
  *
  * @param loadPath Path to directory to load from/watch or a single file to load.
  * @param store Store to write data to.
  * @param strip Number of leading path elements to strip when
  * creating the DataSet name.
  */
protected class FilesLoader(sparkleConfig: Config, loadPath: String, store: WriteableStore, strip: Int) // format: OFF
    (implicit system: ActorSystem) { // format: ON
  import FilesLoader.log

  /** number of rows to read in a block */
  val batchSize = sparkleConfig.getInt("files-loader.batch-size")

  implicit val executor = system.dispatcher
  private val root: Path = Paths.get(loadPath)
  private var closed = false
  private var watcher: Option[PathWatcher] = None

  if (Files.notExists(root)) {
    log.error(s"$loadPath does not exist. Can not load data from this path")
    throw LoadPathDoesNotExist(loadPath)
  }

  /** root to use in calculating the dataset. If we're loading a directory,
    * the dataset begins with the subdirectory or file below the directory.
    * If we're loading a single file, the dataset begins with the filename.
    */
  private val dataSetRoot: Path =
    if (Files.isDirectory(root)) {
      root
    } else {
      root.getParent()
    }

  if (Files.isDirectory(root)) {
    log.info(s"Watching $loadPath for files to load into store")
    val pathWatcher = WatchPath(root)
    watcher = Some(pathWatcher)
    val initialFiles = pathWatcher.watch{ change => fileChange(change, store) }
    initialFiles.foreach{ futureFiles =>
      futureFiles.foreach{ path =>
        if (!closed) {
          loadFile(root.resolve(path), store)
        }
      }
    }
  } else {
    loadFile(root, store)
  }

  /** terminate the file loader */
  def close() {
    watcher.foreach { _.close() }
    closed = true
  }

  /** called when a file is changed in the directory we're watching */
  private def fileChange(change: Change, store: WriteableStore): Unit = {
    change match {
      case Added(path) =>
        loadFile(path, store)
      case Removed(path) =>
        log.warn(s"removed $path.  ignoring for now")
      case Modified(path) =>
        log.warn(s"modified $path.  ignoring for now")
    }
  }

  /** Load a single file into the store. Returns immediately, the loading
    * continues in a background thread.
    */
  private def loadFile(fullPath: Path, store: WriteableStore): Unit = {
    fullPath match {
      case ParseableFile(format) if Files.isRegularFile(fullPath) =>
        log.info(s"Started loading $fullPath into the Store")
        val relativePath = dataSetRoot.relativize(fullPath)
        TabularFile.load(fullPath, format).map { rowInfo =>
          val dataSet = pathToDataSet(relativePath)
          log.info(s"loading rows from $relativePath into dataSet: $dataSet")
          val loaded =
            loadRows(rowInfo, store, dataSet).andThen {
              case _ => rowInfo.close()
            }.map { dataSet =>
              if (!relativePath.getFileName.startsWith("_")) {
                log.info(s"Finished loading dataSet: $dataSet into the store")
                system.eventStream.publish(LoadComplete(dataSet))
              }
            }

          loaded.foreach { _ => system.eventStream.publish(FileLoadComplete(relativePath.toString)) }
          loaded.failed.foreach { failure => log.error(s"loading $fullPath failed", failure) }
        }
      case x => log.warn(s"$fullPath could not be parsed, ignoring")
    }
  }

  case class FileLoaderTypeConfiguration(msg: String) extends RuntimeException(msg)

  /** store data rows into columns the Store, return a future that completes with
    * the name of the dataset into which the columns were written
    */
  private def loadRows(rowInfo: CloseableRowInfo, store: WriteableStore, dataSet: String): Future[String] = {
    /** collect up paths and futures that complete with column write interface objects */
    val pathAndColumns: Seq[(String, Future[WriteableColumn[Any, Any]])] = {
      rowInfo.valueColumns.map {
        case StringColumnInfo(name, _, parser) =>
          val columnPath = dataSet + "/" + name
          val serializeValue = RecoverCanSerialize.optCanSerialize[Any](parser.typed).getOrElse {
            log.error(s"can't file serializer for type ${parser.typed}")
            throw FileLoaderTypeConfiguration(s"can't find canSerialize for type: ${parser.typed}")
          }
          import nest.sparkle.store.cassandra.serializers.LongSerializer
          val column = store.writeableColumn(columnPath)(LongSerializer, serializeValue)
          val anyColumn = column map { futureColumn =>
            futureColumn.asInstanceOf[WriteableColumn[Any, Any]]
          }
          (columnPath, anyColumn)
      }
    }

    val (columnPaths, futureColumns) = pathAndColumns.unzip

    /** write all the row data into storage columns. The columns in the provided Seq
      * should match the order of the rowInfo data columns.
      */
    def writeColumns(rowInfo: RowInfo,
      columns: Seq[WriteableColumn[Any, Any]]): Future[Unit] = {
      rowInfo.keyColumn || NYI("tables without key column")

      def rowToEvents(row: RowData): Seq[Event[Any, Any]] = {
        for {
          key <- row.key(rowInfo).toSeq
          valueOpt <- row.values(rowInfo)
          value <- valueOpt
        } yield {
          Event(key, value)
        }
      }

      val rowGroups = rowInfo.rows.grouped(batchSize)
      val eventsInColumnsBlocks =
        for {
          group <- rowGroups
          _ = log.info(s"loading batch of ${group.length} rows")
          eventsInRows = group.map { row => rowToEvents(row) }
        } yield {
          eventsInRows.transpose
        }

      val allWrites =
        for {
          eventsColumns <- eventsInColumnsBlocks
          (events, column) <- eventsColumns.zip(columns)
        } yield {
          column.write(events)
        }

      Future.sequence(allWrites).map { _ => () }
    }

    val pathWritten: Future[String] =
      for {
        columns <- Future.sequence(futureColumns)
        written <- writeColumns(rowInfo, columns)
      } yield {
        columnPaths.foreach { columnPath =>
          log.info(s"Finished loading $columnPath into the store")
          system.eventStream.publish(LoadComplete(columnPath))
        }
        dataSet
      }

    pathWritten
  }

  case class PathToDataSetFailed(msg: String) extends RuntimeException(msg)

  /** Make the DataSet string from the file's path.
    *
    * The dataset string is the file's path minus any parts beginning with an
    * underscore.
    *
    * Strips off leading path elements if strip > 0.
    * Skips path elements beginning with an underscore.
    * Strips off .tsv or .csv suffixes
    *
    * After stripping and skipping _ prefixed files, if no path components
    * remain for the dataset, use "default" as the dataset.
    *
    * @param path Path of the tsv/csv file
    * @return The DataSet as a string.
    */
  private def pathToDataSet(path: Path): String = {
    val parentOpt: Option[String] = Option(path.getParent).map { parent =>
      val parentElements =
        parent.iterator.drop(strip)
          .filterNot(p => p.toString.startsWith("_"))
      parentElements.mkString("/")
    }

    val fileName = path.getFileName.toString

    val combined =
      if (fileName.startsWith("_")) {
        parentOpt match {
          case Some(parent) => parent
          case None         => "default"
        }
      } else {
        val strippedFileName = fileName.stripSuffix(".tsv").stripSuffix(".csv")
        parentOpt match {
          case Some(parent) => s"$parent/$strippedFileName"
          case None         => strippedFileName
        }
      }

    combined
  }

}

