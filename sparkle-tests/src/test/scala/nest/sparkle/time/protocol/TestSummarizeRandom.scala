package nest.sparkle.time.protocol
import spray.json.DefaultJsonProtocol._
import scala.collection.mutable
import nest.sparkle.store.Event

class TestSummarizeRandom extends PreloadedRamService with StreamRequestor {
  nest.sparkle.util.InitializeReflection.init
  test("summarize random simple data set") {
    val message = summaryRequestOne[Long]("SummarizeRandom", selector = SelectString(simpleColumnPath))

    val found = mutable.HashSet[Event[Long,Double]]()
    (1 to 100).foreach { _ => 
      v1TypicalRequest(message){ events =>
        events.length shouldBe 1
        found.add(events.head)
      }
    }
    
    found.toSet shouldBe simpleEvents.toSet    
  }
}