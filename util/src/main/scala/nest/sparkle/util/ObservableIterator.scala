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

package nest.sparkle.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import rx.lang.scala.{Observable, Subscriber}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

object ObservableIterator extends Log {
  implicit class WrappedIterator[T](val iterator: Iterator[T]) extends AnyVal {
    /** Convert an Iterator to an Observable by dedicating a thread to iteration.
      *
      * Note that this consumes a threadpool thread until the iterator completes or the
      * subscription is cancelled.
      */
    def toObservable(implicit executionContext: ExecutionContext): Observable[T] = {
      // currently active subscribers
      val subscribers: mutable.Set[Subscriber[T]] = {
        val map = new ConcurrentHashMap[Subscriber[T], java.lang.Boolean]
        Collections.newSetFromMap(map).asScala
      }

      // TODO change to atomicBoolean, and definitely set to true somewhere!
      var running = false // true if the iteration thread is running

      /** start the background iterator if unstarted */
      def start(): Unit = {
        synchronized {
          if (!running) {
            startBackgroundIterator()
          }
        }
      }

      /** return the current list of subscribers, culling any that have
       *  unsubscribed as a side effect */
      def currentSubscribers(): mutable.Set[Subscriber[T]] = {
        subscribers.filter(_.isUnsubscribed).foreach { dead =>
          subscribers.remove(dead)
        }
        subscribers
      }

      /** return true if there are any active subscribers */
      def stillActiveSubscribers: Boolean = {
        subscribers.nonEmpty && subscribers.forall(!_.isUnsubscribed)
      }

      /** a single execution thread reads from the iterable. iteration continues
        * until all the current subscribers unsubscribe.
        */
      def startBackgroundIterator(): Unit = {
        val runnable = new Runnable {
          def run(): Unit = { // run in a background thread.
            // TODO add try block, and write to onError
            iterator.takeWhile { value =>
              // TODO add backpressure, either with newish Rx backpressure mechanism
              // or is onNext sufficiently thread synchronous? (that was the original 
              // Rx backpressure mechanism.. does it work here?)
              
              // TODO consider what if onNext has an exception
              currentSubscribers().foreach { _.onNext(value) }
              stillActiveSubscribers
            }.foreach { _ => } // trigger iteration until cancelled
            synchronized { running = false }
          }
        }
        executionContext.execute(runnable)
      }

      // when we get a subscriber add them to the active list and make sure
      // the background thread is started
      Observable { subscriber: Subscriber[T] =>
        subscribers += subscriber
        start()
      }

    }
  }
}
