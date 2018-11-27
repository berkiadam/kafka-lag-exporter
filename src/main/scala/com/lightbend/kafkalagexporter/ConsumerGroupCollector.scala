package com.lightbend.kafkalagexporter

import java.time.Instant

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import com.lightbend.kafkalagexporter.Offsets._

  sealed trait Message
  sealed trait Collect extends Message
  case object Collect extends Collect
  case class NewOffsets(
                         now: Long,
                         latestOffsets: Map[Offsets.TopicPartition, Long],
                         lastGroupOffsets: Map[Offsets.ConsumerGroup, Map[Offsets.TopicPartition, Long]])
    extends Message

  def init(appConfig: AppConfig,
           clientCreator: () => KafkaClientContract,
           reporter: ActorRef[LagReporter.Message]): Behavior[ConsumerGroupCollector.Message] = Behaviors.setup { _ =>
    val lastCommittedOffsets = Offsets.LastCommittedOffsets()
    val latestOffsets = Offsets.LatestOffsets()

    collector(appConfig, clientCreator, latestOffsets, lastCommittedOffsets, reporter)
  }

  def collector(appConfig: AppConfig,
                clientCreator: () => KafkaClientContract,
                latestOffsets: Offsets.LatestOffsets,
                lastCommittedOffsets: Offsets.LastCommittedOffsets,
                reporter: ActorRef[LagReporter.Message]): Behavior[Message] = Behaviors.receive {

    case (context, _: Collect) =>
      implicit val ec: ExecutionContextExecutor = context.executionContext

      val client = clientCreator()

      def getLatestAndGroupOffsets(groups: List[ConsumerGroup]): Future[NewOffsets] = {
        val now = Instant.now().toEpochMilli
        val groupOffsetsF = client.getGroupOffsets(groups)
        val latestOffsetsF = client.getLatestOffsets(groups)

        for {
          groupOffsets <- groupOffsetsF
          latestOffsets <- latestOffsetsF
        } yield NewOffsets(now, latestOffsets, groupOffsets)
      }

      val f = for {
        groups <- client.getGroups()
        newOffsets <- getLatestAndGroupOffsets(groups)
      } yield newOffsets

      f.onComplete {
        case Success(newOffsets) =>
          client.close()
          context.self ! newOffsets
        case Failure(ex)         =>
          println(s"An error occurred while retrieving offsets: $ex")
          throw ex
      }(ec)

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val updatedLastCommittedOffsets: Map[GroupTopicPartition, Measurement] = for {
        (consumerGroup, offsetsMap) <- newOffsets.lastGroupOffsets
        (topicPartition, offset) <- offsetsMap
      } yield {
        val gtp = GroupTopicPartition(consumerGroup, topicPartition)
        val newMeasurement = Offsets.Single(offset, newOffsets.now)
        gtp -> lastCommittedOffsets
          .get(gtp)
          .map(measurement => measurement.addMeasurement(newMeasurement))
          .getOrElse(newMeasurement)
      }

      for((tp, offset) <- newOffsets.latestOffsets)
        reporter ! LagReporter.LatestOffsetMetric(tp, offset)

      for {
        (gtp, measurement: Double) <- updatedLastCommittedOffsets withFilter {
          case (_, _: Double) => true
          case _ => false
        }
        member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
        latestOffset: Long = newOffsets.latestOffsets.getOrElse(gtp.topicPartition, 0)
      } {
        reporter ! LagReporter.LastGroupOffsetMetric(gtp, member, measurement.b.offset)
        reporter ! LagReporter.OffsetLagMetric(gtp, member, measurement.offsetLag(latestOffset))
        reporter ! LagReporter.TimeLagMetric(gtp, member, measurement.lag(newOffsets.now, latestOffset))
      }

      context.scheduleOnce(appConfig.pollInterval, context.self, Collect)

      collector(appConfig, clientCreator, newOffsets.latestOffsets, updatedLastCommittedOffsets, reporter)
  }
}