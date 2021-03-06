/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.scaladsl

import akka.Done
import akka.kafka._
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class PartitionedSourcesSpec extends SpecBase(kafkaPort = KafkaPorts.PartitionedSourcesSpec) with Inside {

  implicit val patience = PatienceConfig(15.seconds, 500.millis)
  override def sleepAfterProduce: FiniteDuration = 500.millis

  def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort,
                        zooKeeperPort,
                        Map(
                          "offsets.topic.replication.factor" -> "1"
                        ))

  "Partitioned source" must {

    "begin consuming from the beginning of the topic" in assertAllStagesStopped {
      val topic = createTopic(1)
      val group = createGroupId(1)

      awaitProduce(produce(topic, 1 to 100))

      val probe = Consumer
        .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                            Subscriptions.topics(topic),
                                            _ => Future.successful(Map.empty))
        .flatMapMerge(1, _._2)
        .map(_.value())
        .runWith(TestSink.probe)

      probe
        .request(100)
        .expectNextN((1 to 100).map(_.toString))

      probe.cancel()
    }

    "begin consuming from the middle of the topic" in assertAllStagesStopped {
      val topic = createTopic(1)
      val group = createGroupId(1)

      givenInitializedTopic(topic)

      awaitProduce(produce(topic, 1 to 100))

      val probe = Consumer
        .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                            Subscriptions.topics(topic),
                                            tp => Future.successful(tp.map(_ -> 51L).toMap))
        .flatMapMerge(1, _._2)
        .filterNot(_.value == InitialMsg)
        .map(_.value())
        .runWith(TestSink.probe)

      probe
        .request(50)
        .expectNextN((51 to 100).map(_.toString))

      probe.cancel()
    }

    "call the onRevoked hook" in assertAllStagesStopped {
      val topic = createTopic()
      val group = createGroupId(1)

      awaitProduce(produce(topic, 1 to 100))

      var revoked = false

      val source = Consumer
        .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                            Subscriptions.topics(topic),
                                            _ => Future.successful(Map.empty),
                                            _ => revoked = true)
        .flatMapMerge(1, _._2)
        .map(_.value())

      val (control1, probe1) = source.toMat(TestSink.probe)(Keep.both).run()

      probe1.request(50)

      sleep(consumerDefaults.waitClosePartition)

      val probe2 = source.runWith(TestSink.probe)

      eventually {
        assert(revoked, "revoked hook should have been called")
      }

      probe1.cancel()
      probe2.cancel()
      control1.isShutdown.futureValue should be(Done)
    }

  }
}
