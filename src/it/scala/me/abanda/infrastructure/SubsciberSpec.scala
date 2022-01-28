package me.abanda.infrastructure

import me.abanda.ITSpec.ITSpec
import me.abanda.domain._
import me.abanda.infrastructure.flyway.FlywayProvider
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.{ suite, testM, _ }

object deleteSpecForTests extends ITSpec(Some("items")) {

  val migrateDbSchema =
    FlywayProvider.flyway
      .flatMap(_.migrate)
      .toManaged_

  def flippingFailure(value: Any): Exception =
    new Exception(
      "Flipping failed! The referred effect was successful with `" + value + "` result of `" + value.getClass + "` type!"
    )

  migrateDbSchema.useNow

  val spec: ITSpec =
    suite("Subscriber")(
      testM("check empty queue") {
        val name: String      = "name"
        val price: BigDecimal = 100.0
        for {
          _: ItemId <- ItemRepository.add(ItemData(name, price))
          things    <- Subscriber.showDeletedEvents.runCollect
        } yield assert(things.toList)(equalTo(List(ItemId(1))))
      } @@ ignore ,
      testM("publishDelete event") {
        val name: String      = "name"
        val price: BigDecimal = 100.0
        for {
          _: ItemId <- ItemRepository.add(ItemData(name, price))
          _         <- ItemRepository.delete(ItemId(1))
          things    <- Subscriber.showDeletedEvents.runCollect
        } yield assert(things.toList)(equalTo(List(ItemId(1))))
      } @@ ignore
    ) @@ before(FlywayProvider.flyway.flatMap(_.migrate).orDie)

}
