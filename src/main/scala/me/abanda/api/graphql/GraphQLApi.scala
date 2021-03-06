package me.abanda.api.graphql

import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import caliban.GraphQL.graphQL
import caliban.interop.ziojson.ZioJsonBackend
import caliban.schema.Annotations.GQLDescription
import caliban.schema.GenericSchema
import caliban.wrappers.ApolloTracing.apolloTracing
import caliban.wrappers.Wrappers._
import caliban.{ AkkaHttpAdapter, CalibanError, GraphQL, RootResolver }
import me.abanda.application.ApplicationService
import me.abanda.domain._
import zio._
import zio.clock._
import zio.duration._
import zio.logging._

import scala.language.postfixOps

trait GraphQLApi {
  def routes: Route
}

object GraphQLApi extends GenericSchema[Has[ApplicationService]] {

  private final case class ItemName(name: String)
  private final case class ItemPrice(price: BigDecimal)
  private final case class ItemInput(name: String, price: BigDecimal)

  private final case class Queries(
    @GQLDescription("Find items by name")
    itemByName: ItemName => RIO[Has[ApplicationService], List[Item]],
    @GQLDescription("Find items cheaper than specified price")
    cheaperThan: ItemPrice => RIO[Has[ApplicationService], List[Item]],
    @GQLDescription("Get all items")
    allItems: RIO[Has[ApplicationService], List[Item]],
    @GQLDescription("Get item by ID")
    item: ItemId => RIO[Has[ApplicationService], Option[Item]]
  )

  private final case class Mutations(
    addItem: ItemInput => RIO[Has[ApplicationService], ItemId],
    deleteItem: ItemId => RIO[Has[ApplicationService], Int]
  )

  private val slowQueriesHandler: (Duration, String) => URIO[Logging, Unit] = (time, query) => {
    log.locallyM(logContext => ZIO(UUID.randomUUID()).option.map(logContext.annotate(LogAnnotation.CorrelationId, _))) {
      log.warn(s"Slow query took ${time.render}:\n$query")
    }
  }

  private def runService[A](f: ZIO[Has[ApplicationService], DomainError, A]): RIO[Has[ApplicationService], A] =
    f.mapError(_.asThrowable)

  private val api: GraphQL[Clock with Logging with Has[ApplicationService]] = {
    graphQL(
      RootResolver(
        Queries(
          args => runService(ApplicationService.getItemByName(args.name)),
          args => runService(ApplicationService.getItemsCheaperThan(args.price)),
          runService(ApplicationService.getItems),
          id => runService(ApplicationService.getItem(id))
        ),
        Mutations(
          item => runService(ApplicationService.addItem(item.name, item.price)),
          id => runService(ApplicationService.deleteItem(id))
        )
      )
    ) @@
    maxFields(200) @@                                // query analyzer that limit query fields
    maxDepth(30) @@                                  // query analyzer that limit query depth
    timeout(3 seconds) @@                            // wrapper that fails slow queries
    onSlowQueries(500 millis)(slowQueriesHandler) @@ // wrapper that logs slow queries
    apolloTracing                                    // wrapper for https://github.com/apollographql/apollo-tracing
  }

  val live =
    ZLayer.fromServicesM[
      Logger[String],
      ApplicationService,
      ActorSystem,
      Clock with Logging,
      CalibanError.ValidationError,
      GraphQLApi
    ] { (logger, applicationService, actorSystem) =>
      implicit val system  = actorSystem
      implicit val ec      = system.dispatcher
      implicit val runtime = Runtime.default.map(_ ++ Has(logger) ++ Has(applicationService))
      val adapter          = AkkaHttpAdapter(new ZioJsonBackend)
      api.interpreter.map { interpreter =>
        new GraphQLApi {
          def routes: Route =
            path("api" / "graphql") {
              adapter.makeHttpService(interpreter)
            } ~
            path("graphiql") {
              getFromResource("graphql/graphiql.html")
            }
        }
      }
    }

  // accessors
  val routes: URIO[Has[GraphQLApi], Route] = ZIO.access[Has[GraphQLApi]](a => Route.seal(a.get.routes))
}
