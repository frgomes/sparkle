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

package nest.sparkle.store.cassandra

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import nest.sparkle.store.Event

/** A modifiable Storage column */
trait WriteableColumn[T, U] {
  /** Write events to the column.  Events are considered immutable once written.  Rewriting the same event is safe.  
   *  
   *  Overwriting an event with different values has undefined results.  i.e. in a time-value column, writing the same time twice will
   *  have unpredictable results on downstream readers.  */
  def write(items:Iterable[Event[T,U]])(implicit executionContext: ExecutionContext): Future[Unit]
  def create(description: String)(implicit executionContext: ExecutionContext): Future[Unit]
  def erase()(implicit executionContext:ExecutionContext): Future[Unit]
}
