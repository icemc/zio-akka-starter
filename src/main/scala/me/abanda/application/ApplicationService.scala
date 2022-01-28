package me.abanda.application

import zio.{ Has, IO, URLayer, ZIO, ZLayer }
import zio.stream.{ Stream, ZStream }
import me.abanda.domain._

trait ApplicationService {

  def addItem(name: String, price: BigDecimal): IO[DomainError, ItemId]

  def deletedEvents: Stream[Nothing, ItemId]

  def deleteItem(itemId: ItemId): IO[DomainError, Int]

  def getItem(itemId: ItemId): IO[DomainError, Option[Item]]

  def getItemByName(name: String): IO[DomainError, List[Item]]

  def getItemsCheaperThan(price: BigDecimal): IO[DomainError, List[Item]]

  val getItems: IO[DomainError, List[Item]]

  def partialUpdateItem(
    itemId: ItemId,
    name: Option[String],
    price: Option[BigDecimal]
  ): IO[DomainError, Option[Unit]]

  def updateItem(
    itemId: ItemId,
    name: String,
    price: BigDecimal
  ): IO[DomainError, Option[Unit]]
}

object ApplicationService {

  val live: URLayer[Has[ItemRepository] with Has[Subscriber], Has[ApplicationService]] = ZLayer.fromServices[ItemRepository, Subscriber, ApplicationService] { case (repo, sbscr) =>
    new ApplicationService {
      def addItem(name: String, price: BigDecimal): IO[DomainError, ItemId] = repo.add(ItemData(name, price))

      def deletedEvents: Stream[Nothing, ItemId] = sbscr.showDeleteEvents

      def deleteItem(itemId: ItemId): IO[DomainError, Int] = 
        for {
          out <- repo.delete(itemId)
          _   <- sbscr.publishDeleteEvents(itemId)
        } yield out

      def getItem(itemId: ItemId): IO[DomainError, Option[Item]] = repo.getById(itemId)

      def getItemByName(name: String): IO[DomainError, List[Item]] = repo.getByName(name)

      def getItemsCheaperThan(price: BigDecimal): IO[DomainError, List[Item]] = repo.getCheaperThan(price)

      val getItems: IO[DomainError, List[Item]] = repo.getAll

      def partialUpdateItem(
        itemId: ItemId,
        name: Option[String],
        price: Option[BigDecimal]
      ): IO[DomainError, Option[Unit]] = 
        (for {
          item <- repo.getById(itemId).some
          _ <- repo.update(itemId, ItemData(name.getOrElse(item.name), price.getOrElse(item.price)))
                .mapError(Some(_))
        } yield ()).optional

      def updateItem(
        itemId: ItemId,
        name: String,
        price: BigDecimal
      ): IO[DomainError, Option[Unit]] = repo.update(itemId, ItemData(name, price))
    }
  }

  def addItem(name: String, price: BigDecimal): ZIO[Has[ApplicationService], DomainError, ItemId] =
    ZIO.accessM(_.get.addItem(name, price))

  def deletedEvents: ZStream[Has[ApplicationService], Nothing, ItemId] =
    ZStream.accessStream(_.get.deletedEvents)

  def deleteItem(itemId: ItemId): ZIO[Has[ApplicationService], DomainError, Int] =
    ZIO.accessM(_.get.deleteItem(itemId))

  def getItem(itemId: ItemId): ZIO[Has[ApplicationService], DomainError, Option[Item]] =
    ZIO.accessM(_.get.getItem(itemId))

  def getItemByName(name: String): ZIO[Has[ApplicationService], DomainError, List[Item]] =
    ZIO.accessM(_.get.getItemByName(name))

  def getItemsCheaperThan(price: BigDecimal): ZIO[Has[ApplicationService], DomainError, List[Item]] =
    ZIO.accessM(_.get.getItemsCheaperThan(price))

  val getItems: ZIO[Has[ApplicationService], DomainError, List[Item]] =
    ZIO.accessM(_.get.getItems)

  def partialUpdateItem(
    itemId: ItemId,
    name: Option[String],
    price: Option[BigDecimal]
  ): ZIO[Has[ApplicationService], DomainError, Option[Unit]] =
    ZIO.accessM(_.get.partialUpdateItem(itemId, name, price))

  def updateItem(
    itemId: ItemId,
    name: String,
    price: BigDecimal
  ): ZIO[Has[ApplicationService], DomainError, Option[Unit]] =
    ZIO.accessM(_.get.updateItem(itemId, name, price))

}
