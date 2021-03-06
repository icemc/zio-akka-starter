package me.abanda.infrastructure
import me.abanda.domain.{ Subscriber, ItemId }
import zio._
import zio.stream.ZStream

class InMemoryEventSubscriber(deletedEventsSubscribers: Ref[List[Queue[ItemId]]]) extends Subscriber {

  def showDeleteEvents: ZStream[Any, Nothing, ItemId] = ZStream.unwrap {
    for {
      queue <- Queue.unbounded[ItemId]
      _     <- deletedEventsSubscribers.update(queue :: _)
    } yield ZStream.fromQueue(queue)
  }

  def publishDeleteEvents(deletedItemId: ItemId): UIO[List[Boolean]] =
    deletedEventsSubscribers.get.flatMap(subs =>
      // send item to all subscribers
      UIO.foreach(subs)(queue =>
        queue
          .offer(deletedItemId)
          .onInterrupt(
            // if queue was shutdown, remove from subscribers
            deletedEventsSubscribers.update(_.filterNot(_ == queue))
          )
      )
    )
}
object InMemoryEventSubscriber {

  val test: Layer[Nothing, Has[Subscriber]] = (for {
    deleted <- Ref.make(List.empty[Queue[ItemId]])
  } yield new InMemoryEventSubscriber(deleted)).toLayer
}