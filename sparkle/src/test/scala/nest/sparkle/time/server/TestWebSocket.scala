package nest.sparkle.time.server

import org.scalatest.FunSuite
import org.scalatest.Matchers
import nest.sparkle.time.protocol.TestStore

class TestWebSocket extends FunSuite with Matchers with TestStore {
  ignore("websocket") {
    val s = new DataWebSocket(store, ???)
    Thread.sleep(1000*60*60*24)
  }
}