package nest.sparkle.time.protocol

import _root_.nest.sparkle.util.LogUtil
import spray.http.MediaTypes.`application/json`
import spray.http.HttpHeaders.`Content-Type`
import spray.http.StatusCodes.OK
import nest.sparkle.time.protocol.ResponseJson._
import spray.httpx.SprayJsonSupport._

class TestMalformedRequests extends PreloadedRamService with StreamRequestor {

  test("missing transform parameters on Raw transform") {
    val msg = s"""
      {
        "messageType": "StreamRequest",
        "message": {
          "sources": [
      		  "$testColumnPath"
          ],
          "transform":"raw",
          "transformParameters":{
          }
        }
      }
    """

    Post("/v1/data", msg) ~> v1protocol ~> check {
      status shouldBe OK
    }
  }
  
  test("missing column returns column not found (601)") {
    val msg = s"""
      {
        "messageType": "StreamRequest",
        "message": {
          "sources": [
      		  "not/really/there"
          ],
          "transform":"raw", 
				  "transformParameters": {}
        }
      }
    """

    LogUtil.withLogLevel(ProtocolError.getClass, "ERROR") {
      Post("/v1/data", msg) ~> v1protocol ~> check {
        status shouldBe OK
        val statusMessage = responseAs[StatusMessage]
        statusMessage.message.code shouldBe 601
      }
    }
  }
  

}