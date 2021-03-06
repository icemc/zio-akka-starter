package me.abanda.infrastructure
import me.abanda.domain.{Subscriber, ItemId }
import zio._
import zio.logging._
import zio.stream.ZStream

class EventSubscriber(env: Logging, deletedEventsSubscribers: Ref[List[Queue[ItemId]]]) extends Subscriber {

  def showDeleteEvents: ZStream[Any, Nothing, ItemId] = ZStream.unwrap {
    for {
      queue: Queue[ItemId] <- Queue.unbounded[ItemId]
      _                    <- deletedEventsSubscribers.update(queue :: _)
    } yield ZStream.fromQueue(queue)
  }

  def publishDeleteEvents(deletedItemId: ItemId): ZIO[Any, Nothing, List[Boolean]] =
    log.info(s"Publishing delete event for item ${deletedItemId.value}").provide(env) *>
      deletedEventsSubscribers.get.flatMap[Any, Nothing, List[Boolean]] { subs =>
        // send item to all subscribers
        log.info("subs" + subs)
        UIO.foreach(subs) { queue =>
          log.info("queue" + queue)

          queue
            .offer(deletedItemId)
            .onInterrupt(
              // if queue was shutdown, remove from subscribers
              deletedEventsSubscribers.update(_.filterNot(_ == queue))
            )
        }
      }

}
object EventSubscriber {

  val live: RLayer[Logging, Has[Subscriber]] =
    ZLayer.fromFunctionM(env => Ref.make(List.empty[Queue[ItemId]]).map(new EventSubscriber(env, _)))
}
