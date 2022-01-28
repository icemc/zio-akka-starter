package me.abanda

import me.abanda.domain.ItemRepository
import me.abanda.infrastructure.{ Postgres, SlickItemRepository }
import me.abanda.infrastructure.Postgres.SchemaAwarePostgresContainer
import me.abanda.infrastructure.flyway.FlywayProvider
import com.typesafe.config.{ Config, ConfigFactory }
import slick.interop.zio.DatabaseProvider
import slick.jdbc.JdbcProfile
import zio.blocking.Blocking
import zio.duration.durationInt
import zio.logging._
import zio.logging.slf4j.Slf4jLogger
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{ Has, Layer, ULayer, ZLayer }
import me.abanda.domain.Subscriber
import me.abanda.infrastructure.EventSubscriber
import scala.jdk.CollectionConverters.MapHasAsJava

object ITSpec {
  type Postgres = Has[SchemaAwarePostgresContainer]
  type ITEnv    = TestEnvironment with Has[FlywayProvider] with Logging with Has[ItemRepository]
    with Has[Subscriber]

  abstract class ITSpec(schema: Option[String] = None) extends RunnableSpec[ITEnv, Any] {
    type ITSpec = ZSpec[ITEnv, Any]

    override def aspects: List[TestAspect[Nothing, ITEnv, Nothing, Any]] =
      List(TestAspect.timeout(60.seconds))

    override def runner: TestRunner[ITEnv, Any] =
      TestRunner(TestExecutor.default(itLayer))

    val blockingLayer: Layer[Nothing, Blocking]       = Blocking.live
    val postgresLayer: ZLayer[Any, Nothing, Postgres] = blockingLayer >>> Postgres.postgres(schema)
    val subscriberLayer: ZLayer[Any, Nothing, Has[Subscriber]] = Logging.ignore  >>> EventSubscriber.live.orDie
    val dbLayer: ZLayer[
      Any with Postgres with Blocking,
      Nothing,
      TestEnvironment with Has[FlywayProvider] with Logging with Has[ItemRepository]
    ] = {

      val config: ZLayer[Postgres, Nothing, Has[Config]] = ZLayer
        .fromService[SchemaAwarePostgresContainer, Config] { container =>
          val config = ConfigFactory.parseMap(
            Map(
              "url"            -> container.jdbcUrl,
              "user"           -> container.username,
              "password"       -> container.password,
              "driver"         -> "org.postgresql.Driver",
              "connectionPool" -> "HikariCP",
              "numThreads"     -> 1,
              "queueSize"      -> 100
            ).asJava
          )
          config
        }

      val dbProvider: ZLayer[Postgres with Any, Throwable, Has[DatabaseProvider]] =
        config ++ ZLayer.succeed[JdbcProfile](slick.jdbc.PostgresProfile) >>> DatabaseProvider.live

      val flyWayProvider = config >>> FlywayProvider.live

      val postgresLayer = Postgres.postgres(schema)
      val blockingLayer = Blocking.live

      val containerDatabaseProvider: ZLayer[Blocking, Throwable, Has[DatabaseProvider]] =
        blockingLayer >>> postgresLayer >>> dbProvider

      val containerRepository: ZLayer[Blocking, Throwable, Has[ItemRepository]] =
        (Logging.ignore ++ containerDatabaseProvider) >>> SlickItemRepository.live

      val logging = Slf4jLogger.make { (context, message) =>
        val logFormat     = "[correlation-id = %s] %s"
        val correlationId = LogAnnotation.CorrelationId.render(
          context.get(LogAnnotation.CorrelationId)
        )
        logFormat.format(correlationId, message)
      }
      zio.test.environment.testEnvironment ++ flyWayProvider ++ logging ++ containerRepository
    }.orDie

    val itLayer: ULayer[ITEnv] =
      (zio.test.environment.testEnvironment ++ postgresLayer ++ blockingLayer) >+> dbLayer ++ subscriberLayer
  }
}
