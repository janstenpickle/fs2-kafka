package fs2.kafka

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import fs2.Stream
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException
import org.apache.kafka.common.TopicPartition

import scala.concurrent.duration._

final class KafkaConsumerSpec extends BaseKafkaSpec {
  describe("KafkaConsumer#stream") {
    tests(_.stream)
  }

  describe("KafkaConsumer#partitionedStream") {
    tests(_.partitionedStream.parJoin(partitions))
  }

  val partitions = 3

  def tests(
    f: KafkaConsumer[IO, String, String] => Stream[IO, CommittableMessage[IO, String, String]]
  ): Unit = {
    it("should consume all messages") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = partitions)
        val produced = (0 until 100).map(n => s"key-$n" -> s"value->$n")
        publishToKafka(topic, produced)

        val consumed =
          (for {
            consumerSettings <- consumerSettings(config)
            consumer <- consumerStream[IO].using(consumerSettings)
            _ <- consumer.subscribe(NonEmptyList.of(topic))
            consumed <- f(consumer).take(produced.size.toLong)
            record = consumed.record
          } yield record.key -> record.value).compile.toVector.unsafeRunSync

        consumed should contain theSameElementsAs produced
      }
    }

    it("should read from the given offset") {
      withKafka {
        seekTest(numMessages = 100, readOffset = 90)
      }
    }

    it("should fail to read from a negative offset") {
      withKafka {
        an[IllegalArgumentException] should be thrownBy seekTest(numMessages = 100, readOffset = -1)(_, _)
      }
    }

    it("should fail to read from a partition not assigned to this consumer") {
      withKafka {
        an[IllegalStateException] should be thrownBy seekTest(numMessages = 100, readOffset = 90, partition = Some(123))(_, _)
      }
    }

    it("should commit the last processed offsets") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = partitions)
        val produced = (0 until 100).map(n => s"key-$n" -> s"value->$n")
        publishToKafka(topic, produced)

        val committed =
          (for {
            consumerSettings <- consumerSettings(config)
            consumer <- consumerStream[IO].using(consumerSettings)
            _ <- consumer.subscribe(topic.r)
            offsets <- f(consumer)
              .take(produced.size.toLong)
              .map(_.committableOffset)
              .fold(CommittableOffsetBatch.empty[IO])(_ updated _)
              .evalMap(batch => batch.commit.as(batch.offsets))
          } yield offsets).compile.lastOrError.unsafeRunSync

        assert {
          committed.values.toList.foldMap(_.offset) == produced.size.toLong &&
          withKafkaConsumer[String, String](consumerProperties(config)) { consumer =>
            committed.foldLeft(true) {
              case (result, (topicPartition, offsetAndMetadata)) =>
                result && offsetAndMetadata == consumer.committed(topicPartition)
            }
          }
        }
      }
    }

    it("should interrupt the stream when cancelled") {
      withKafka { (config, topic) =>
        val consumed =
          (for {
            consumerSettings <- consumerSettings(config)
            consumer <- consumerStream[IO].using(consumerSettings)
            _ <- consumer.subscribe(NonEmptyList.of(topic))
            _ <- Stream.eval(consumer.fiber.cancel)
            _ <- f(consumer)
            _ <- Stream.eval(consumer.fiber.join)
          } yield ()).compile.toVector.unsafeRunSync

        assert(consumed.isEmpty)
      }
    }

    it("should fail with an error if not subscribed") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = partitions)

        val consumed =
          (for {
            consumerSettings <- consumerSettings(config)
            consumer <- consumerStream[IO].using(consumerSettings)
            _ <- f(consumer)
          } yield ()).compile.lastOrError.attempt.unsafeRunSync

        assert(consumed.left.toOption.contains(NotSubscribedException))
      }
    }

    it("should propagate consumer errors to stream") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = partitions)

        val consumed =
          (for {
            consumerSettings <- consumerSettings(config)
              .map(_.withAutoOffsetReset(AutoOffsetReset.None))
            consumer <- consumerStream[IO].using(consumerSettings)
            _ <- consumer.subscribe(NonEmptyList.of(topic))
            _ <- f(consumer)
          } yield ()).compile.lastOrError.attempt.unsafeRunSync

        consumed.left.toOption match {
          case Some(_: NoOffsetForPartitionException) => succeed
          case Some(cause)                            => fail("Unexpected exception", cause)
          case None                                   => fail(s"Unexpected result [$consumed]")
        }
      }
    }

    it("should be able to retrieve offsets") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = 1)
        val produced = (0 until 100).map(n => s"key-$n" -> s"value->$n")
        publishToKafka(topic, produced)

        val topicPartition = new TopicPartition(topic, 0)
        val topicPartitions = Set(topicPartition)
        val timeout = 10.seconds

        (for {
          consumerSettings <- consumerSettings(config)
          consumer <- consumerStream[IO].using(consumerSettings)
          _ <- consumer.subscribe(NonEmptyList.of(topic))
          _ <- f(consumer)
            .take(produced.size.toLong)
            .map(_.committableOffset)
            .to(commitBatch[IO])

          start <- Stream.eval(consumer.beginningOffsets(topicPartitions))
          startTimeout <- Stream.eval(consumer.beginningOffsets(topicPartitions, timeout))
          _ <- Stream.eval(IO {
            assert(start == startTimeout && start == Map(topicPartition -> 0L))
          })
          end <- Stream.eval(consumer.endOffsets(topicPartitions))
          endTimeout <- Stream.eval(consumer.endOffsets(topicPartitions, timeout))
          _ <- Stream.eval(IO {
            assert(end == endTimeout && end == Map(topicPartition -> produced.size.toLong))
          })
        } yield ()).compile.drain.unsafeRunSync
      }
    }

    def seekTest(numMessages: Long, readOffset: Long, partition: Option[Int] = None)(config: EmbeddedKafkaConfig, topic: String) = {
      createCustomTopic(topic)

      val produced = (0l until numMessages).map(n => s"key-$n" -> s"value->$n")
      publishToKafka(topic, produced)

      val consumed =
          consumerSettings(config).flatMap { consumerSettings =>
            consumerStream[IO].using(consumerSettings).flatMap { consumer =>

              val validSeekParams =
                f(consumer)
                  .take(Math.max(readOffset, 1))
                  .map(_.committableOffset).compile.toList.map(_.last)
                  .map(co => (co.topicPartition, co.offsetAndMetadata.offset()))

              val seekParams =
                validSeekParams.map { case (topicPartition, offset) =>
                  val p = partition.map(new TopicPartition(topic, _)).getOrElse(topicPartition)
                  val o = Math.min(readOffset, offset)

                  (p, o)
                }

              val setOffset =
                seekParams.flatMap { case (tp, o) => consumer.seek(tp, o) }

              val consume = f(consumer).take(numMessages-readOffset)

              consumer.subscribe(NonEmptyList.one(topic)).drain ++
              (Stream.eval_(setOffset) ++ consume)
                .map(_.record)
                .map(record => record.key -> record.value())

            }
          }.compile.toVector.unsafeRunSync()

        consumed should contain theSameElementsAs produced.drop(readOffset.toInt)
      }

  }



}
