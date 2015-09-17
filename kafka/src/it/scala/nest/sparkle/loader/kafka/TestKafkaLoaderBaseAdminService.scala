package nest.sparkle.loader.kafka

import scala.concurrent.duration._

import spray.http.StatusCodes._
import spray.http.MediaTypes.`application/json`
import spray.testkit.ScalatestRouteTest

import akka.actor.ActorRefFactory

import spray.json._

import nest.sparkle.measure.MeasurementToTsvFile
import nest.sparkle.util.kafka.{KafkaTestSuite, KafkaBroker, KafkaTopic, KafkaGroupOffsets}
import nest.sparkle.util.kafka.KafkaJsonProtocol._

class TestKafkaLoaderBaseAdminService
  extends KafkaTestSuite
    with ScalatestRouteTest
    with KafkaTestConfig 
    with KafkaLoaderBaseAdminService
{
  override def actorRefFactory: ActorRefFactory = system
  implicit def executionContext = system.dispatcher
  implicit override lazy val measurements =
    new MeasurementToTsvFile("/tmp/kafka-loader-tests.tsv")(executionContext) // SCALA why doesn't implicit above catch this?
  
  // Some of the requests currently take a very long time
  implicit val routeTestTimeout = RouteTestTimeout(1.minute)

  override def afterAll(): Unit = {
    super.afterAll()
    measurements.close()
  }
  
  /** validate the response is JSON and convert to T */
  protected def convertJsonResponse[T: JsonFormat]: T = {
    assert(handled, "request was not handled")
    assert(status == OK, "response not OK")
    mediaType shouldBe `application/json`
    
    val json = body.asString
    json.length > 0 shouldBe true
    val ast = json.parseJson
    ast.convertTo[T]
  }
  
  test("The list of brokers is correct") {
    Get("/brokers") ~> allRoutes ~> check {
      val brokers = convertJsonResponse[Seq[KafkaBroker]]
      brokers.length shouldBe 1
      val broker = brokers(0)
      broker.id shouldBe 0
      broker.host shouldBe "localhost"
      broker.port shouldBe 9092
    }
  }
  
  test("The list of topics includes the test topic") {
    Get("/topics") ~> allRoutes ~> check {
      val topics = convertJsonResponse[Map[String,KafkaTopic]]
      topics should contain key TopicName
      val topic = topics(TopicName)
      topic.partitions.length shouldBe NumPartitions
      topic.partitions.zipWithIndex foreach { case (partition, i) =>
        partition.id shouldBe i
        partition.leader shouldBe 0
        partition.brokerIds.length shouldBe 1
        partition.brokerIds(0) shouldBe 0
        partition.earliest should contain (0)
        partition.latest should contain (5)
      }
    }
  }
  
  test("The list of consumer groups includes the test group") {
    Get("/groups") ~> allRoutes ~> check {
      val groups = convertJsonResponse[Seq[String]]
      assert(groups.contains(ConsumerGroup),s"$ConsumerGroup not found in $groups")
    }
  }
  
  test("The list of consumer group topic offsets is correct") {
    Get("/offsets") ~> allRoutes ~> check {
      val groups = convertJsonResponse[Seq[KafkaGroupOffsets]]
      assert(groups.length > 1, "no consumer groups found")
      val optGroup = groups.find(_.group.contentEquals(ConsumerGroup))
      optGroup match {
        case Some(group)  =>
          assert(group.topics.size == 1, "not one topic in the consumer group")
          assert(group.topics.contains(TopicName), "topic not in the consumer group")
          val topic = group.topics(TopicName)
          assert(topic.partitions.length == NumPartitions, s"${topic.topic} does not have $NumPartitions partitions")
          topic.partitions.zipWithIndex foreach { case (offset,i) =>
            assert(offset.partition == i, s"${topic.topic}:$i partition id doesn't equal index")
            assert(offset.offset == Some(2), s"${topic.topic}:$i partition offset doesn't equal 2")
          }
        case _            => fail(s"consumer group $ConsumerGroup not found")
      }
    }
  }

}
