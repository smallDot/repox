package com.gtan.repox

import java.net.URL
import java.nio.file.Paths
import java.util.Date

import akka.actor.{Props, ActorSystem}
import com.gtan.repox.HeaderCache.Query
import com.ning.http.client._
import com.ning.http.client.resumable.ResumableIOExceptionFilter
import com.typesafe.scalalogging.LazyLogging
import io.undertow.Handlers
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.resource.FileResourceManager
import io.undertow.util.{StatusCodes, MimeMappings, Headers, HttpString}

import scala.collection.JavaConverters._
import scala.collection.Set
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}


object Repox extends LazyLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  import concurrent.duration._

  val system = ActorSystem("repox")

  val storage = Paths.get(System.getProperty("user.home"), ".repox", "storage")

  val upstreams = List(
//    Repo("osc", "http://maven.oschina.net/content/groups/public", priority = 2),
    Repo("koala", "http://nexus.openkoala.org/nexus/content/groups/Koala-release", priority = 1),
    Repo("sbt-plugin", "http://dl.bintray.com/sbt/sbt-plugin-releases", priority = 3),
    Repo("typesafe", "http://repo.typesafe.com/typesafe/releases", priority = 6),
    Repo("sonatype", "http://oss.sonatype.org/content/repositories/releases", priority = 3),
//    Repo("spray", "http://repo.spray.io"),
    Repo("scalaz", "http://dl.bintray.com/scalaz/releases"),
    Repo("uk", "http://uk.maven.org/maven2", priority = 5)
//    Repo("central", "http://repo1.maven.org/maven2", priority = 5)
  )
  val blacklistRules = List(
    BlacklistRule("""/org/slf4j/slf4j-parent/.+/slf4j-parent-.+\.pom.*""", "typesafe"),
    BlacklistRule("""/com/ning/async-http-client/.+/async-http-client-.+\.pom.*""", "typesafe"),
    BlacklistRule("""/org/apache/apache/.+/apache-.+\.pom*""", "typesafe"),
    BlacklistRule("""/org/apache/commons/commons-parent/.+/commons-parent-.+\.pom.*""", "typesafe"),
    BlacklistRule("""/commons-io/commons-io/.+/commons-io-.+\.pom.*""", "typesafe"),
    BlacklistRule("""/org/sonatype/oss/oss-parent/.+/oss-parent-.+\.pom.*""", "typesafe"),
    BlacklistRule("""/org/ow2/asm/.+\.pom.*""", "typesafe")
  )

  val immediat404Rules = List(
    Immediate404Rule( """.+-javadoc.jar"""), // we don't want javadoc
    Immediate404Rule( """.+-parent.*.jar"""), // parent have no jar
    Immediate404Rule( """/org/scala-sbt/.*""", exclude = Some( """/org/scala-sbt/test-interface/.*""")), // ivy only artifact have no maven uri
    //    Immediat404Rule( """/org/scala-tools/.*"""), // ivy only artifact have no maven uri
    Immediate404Rule( """/com/eed3si9n/.*"""), // ivy only artifact have no maven uri
    Immediate404Rule( """/io\.spray/.*""", exclude = Some( """/io\.spray/sbt-revolver.*""")), // maven only artifact have no ivy uri
    Immediate404Rule( """/org/jboss/xnio/xnio-all/.+\.jar"""),
    Immediate404Rule( """/org\.jboss\.xnio/xnio-all/.+\.jar"""),
    Immediate404Rule( """/org/apache/apache/(\d+)/.+\.jar"""),
    Immediate404Rule( """/org\.apache/apache/(\d+)/.+\.jar"""),
    Immediate404Rule( """/com/google/google/(\d+)/.+\.jar"""),
    Immediate404Rule( """/com\.google/google/(\d+)/.+\.jar"""),
    Immediate404Rule( """/org/ow2/ow2/.+\.jar"""),
    Immediate404Rule( """/org\.ow2/ow2/.+\.jar"""),
    Immediate404Rule( """/com/github/mpeltonen/sbt-idea/.*\.jar"""),
    Immediate404Rule( """/com\.github\.mpeltonen/sbt-idea/.*\.jar"""),
    Immediate404Rule( """/org/fusesource/leveldbjni/.+-sources\.jar"""),
    Immediate404Rule( """/org\.fusesource\.leveldbjni/.+-sources\.jar"""),
    Immediate404Rule( """.*/jsr305.*\-sources\.jar""")
  )
  val client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
    .addIOExceptionFilter(new ResumableIOExceptionFilter())
    .setRequestTimeoutInMs(Int.MaxValue)
    .setConnectionTimeoutInMs(6000)
    .setAllowPoolingConnection(true)
    .setAllowSslConnectionPool(true)
    .setMaximumConnectionsPerHost(10)
    .setMaximumConnectionsTotal(200)
    //    .setIdleConnectionInPoolTimeoutInMs(Int.MaxValue)
    //    .setIdleConnectionTimeoutInMs(Int.MaxValue)
    .setFollowRedirects(true)
    .build()
  )
  val proxyClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
    .addIOExceptionFilter(new ResumableIOExceptionFilter())
    .setRequestTimeoutInMs(Int.MaxValue)
    .setConnectionTimeoutInMs(6000)
    .setAllowPoolingConnection(true)
    .setAllowSslConnectionPool(true)
    .setMaximumConnectionsPerHost(1)
    .setMaximumConnectionsTotal(2)
    .setProxyServer(new ProxyServer("127.0.0.1", 8787))
    //    .setIdleConnectionInPoolTimeoutInMs(Int.MaxValue)
    //    .setIdleConnectionTimeoutInMs(Int.MaxValue)
    .setFollowRedirects(true)
    .build()
  )

  val resourceManager = new FileResourceManager(storage.toFile, 100 * 1024)

  type StatusCode = Int
  type ResponseHeaders = Map[String, java.util.List[String]]
  type HeaderResponse = (Repo, StatusCode, ResponseHeaders)

  val candidates = upstreams.groupBy(_.priority).toList.sortBy(_._1).map(_._2)
  val headerCache = system.actorOf(Props[HeaderCache], "HeaderCache")

  private def reduce(xss: List[List[Repo]], notFoundIn: Set[Repo]): List[List[Repo]] = {
    xss.map(_.filterNot(notFoundIn.contains)).filter(_.nonEmpty)
  }

  import akka.pattern.ask
  import concurrent.duration._
  implicit val timeout = akka.util.Timeout(1 second)

  private def immediat404(uri: String): Boolean = {
    Repox.immediat404Rules.exists(_.matches(uri))
  }

  def handle(exchange: HttpServerExchange): Unit = {
    val uri = exchange.getRequestURI
    val resolvedPath = storage.resolve(uri.tail)

    exchange.getRequestMethod.toString.toUpperCase match {
      case "HEAD" =>
        val file = resolvedPath.toFile
        if (file.exists()) {
          val resource = resourceManager.getResource(uri)
          exchange.setResponseCode(StatusCodes.NO_CONTENT)
          val headers = exchange.getResponseHeaders
          headers.put(Headers.CONTENT_TYPE, file.length())
            .put(Headers.SERVER, "repox")
            .put(Headers.CONNECTION, Headers.KEEP_ALIVE.toString)
            .put(Headers.CONTENT_TYPE, resource.getContentType(MimeMappings.DEFAULT))
            .put(Headers.LAST_MODIFIED, resource.getLastModifiedString)
          exchange.endExchange()
          logger.debug(s"Direct HEAD $uri. ")
        } else {
          if (immediat404(uri)) {
            logger.debug(s"$uri immediat 404")
            exchange.setResponseCode(StatusCodes.NOT_FOUND)
            exchange.endExchange()
          } else {
            val blacklistedUpstreams = upstreams.filterNot(_.name == "osc").filterNot { repo =>
              Repox.blacklistRules.exists { rule =>
                uri.matches(rule.pattern) && rule.repoName == repo.name
              }
            }
            new HeadWorker(exchange, blacklistedUpstreams).`try`(times = 3)
          }
        }
      case "GET" =>
        if (resolvedPath.toFile.exists()) {
          logger.debug(s"$uri Already downloaded. Serve immediately.")
          Handlers.resource(resourceManager).handleRequest(exchange)
        } else {
          val smallFileRepo = candidates//if(uri.endsWith(".jar")) candidates else candidates.tail

          (headerCache ? Query(uri)).onComplete {
            case Success(None) => // all
              logger.debug(s"All candidates will be check. Start download $uri ....")
              GetMaster.run(exchange, resolvedPath, smallFileRepo)
            case Success(Some(Entry(repos, _))) => // previous head request found candidates
              if (repos.isEmpty) {
                logger.debug(s"All candidates will be check. Start download $uri ....")
                GetMaster.run(exchange, resolvedPath, smallFileRepo)
              } else {
                val filtered = reduce(smallFileRepo, notFoundIn = repos)
                logger.debug(s"Check ${filtered.map(_.map(_.name))} only. Start download $uri ....")
                GetMaster.run(exchange, resolvedPath, filtered)
              }
            case Failure(t) =>
              t.printStackTrace()
              exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR)
              exchange.endExchange()
            case _ =>
              logger.error("this should not happen")
          }
        }
    }
  }

}
