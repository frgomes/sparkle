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

package nest.sparkle.util

import java.nio.charset.Charset
import java.nio.file.StandardOpenOption.{CREATE, TRUNCATE_EXISTING}
import java.nio.file.{Files, NotDirectoryException, Path}
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Success

import akka.actor.ActorSystem
import org.scalatest.{FunSuite, Matchers}

import nest.sparkle.util.FutureAwait.Implicits._

class TestWatchPath extends FunSuite with Matchers {

  test("recursive directory scan") {
    val root = Files.createTempDirectory("TestWatchPath-recursive")
    val subPath = root.resolve("deeper")
    val sub = Files.createDirectory(subPath)
    val a = Files.createFile(root.resolve("a.csv"))
    val b = Files.createFile(sub.resolve("b.csv"))
    val created = List(a,b,sub,root)

    try {
      val found = WatchPath.scan(root, "**.csv")
      found.toList.length === 2

    } finally {
      created foreach Files.deleteIfExists
    }
  }

  test("trying watch single file should throw an exception") {
    val root = Files.createTempDirectory("TestWatchPath-recursive")
    val a = Files.createFile(root.resolve("a.tsv"))
    val writer = Files.newBufferedWriter(a, Charset.forName("UTF-8"), TRUNCATE_EXISTING, CREATE)
    implicit val system = ActorSystem("TestWatchPath")
    val expectedException = Promise[Boolean]()
    try {
      val watcher = WatchPath(a, "**.tsv")
      expectedException.future.await
    } catch {
      case e:NotDirectoryException =>
        expectedException.complete(Success(true))
    } finally {
      system.shutdown()
      Files.deleteIfExists(a)
      Files.deleteIfExists(root)
    }

    expectedException.future.await shouldBe true
  }

  test("watch directory") {
    implicit val system = ActorSystem()
    val root = Files.createTempDirectory("TestDirectoryWatch")
    val subPath = root.resolve("deeper")
    val sub = Files.createDirectory(subPath)
    val watcher = WatchPath(root, "**.csv")

    // triggered when we've found two files
    val allFound = Promise[Boolean]()
    val found = mutable.ArrayBuffer[Path]()

    // get ready to watch for changes
    val ready = watcher.watch { change =>
      change match {
        case WatchPath.Added(path) =>
          found.append(path)
          if (found.length == 3)
            allFound.success(true)

        case _ =>
      }
    }
    if (sys.props("os.name") == "Mac OS X") {
      Thread.sleep(1000)  // jdk7 on MacOS uses slow polling instead of kernel change notification
    }
    ready.await

    val b = Files.createFile(sub.resolve("b.csv"))
    val a = Files.createFile(root.resolve("a.csv"))
    val sub2 = Files.createDirectory(subPath.resolve("andDeeper"))
    val c = Files.createFile(sub2.resolve("c.csv"))

    try {
      allFound.future.await(20.seconds)   // on MacOS, could take a while
      found.length should be (3)
      found.find(_.endsWith("b.csv")) should be ('defined)
      found.find(_.endsWith("a.csv")) should be ('defined)
      found.find(_.endsWith("c.csv")) should be ('defined)
    } finally {
      List(a, b, c, sub2, sub, root) foreach Files.deleteIfExists
      watcher.close()
      system.shutdown()
    }
  }

}

