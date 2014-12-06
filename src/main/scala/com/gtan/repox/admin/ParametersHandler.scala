package com.gtan.repox.admin

import com.gtan.repox.Repox
import com.gtan.repox.config.Config
import com.gtan.repox.config.ConfigPersister._
import io.undertow.server.HttpServerExchange
import io.undertow.util.Methods
import collection.JavaConverters._
import scala.concurrent.duration._
import akka.pattern.ask

object ParametersHandler extends RestHandler {

  import WebConfigHandler._

  implicit val timeout = akka.util.Timeout(1 second)

  override def route(implicit exchange: HttpServerExchange) = {
    case (Methods.GET, "parameters") =>
      val config = Config.get
      respondJson(exchange, Map(
        "parameters" -> Seq(
          Map("name" -> "connectionTimeout", "value" -> config.connectionTimeout.toMillis, "unit" -> "ms").asJava,
          Map("name" -> "connectionIdleTimeout", "value" -> config.connectionIdleTimeout.toMillis, "unit" -> "ms").asJava,
          Map("name" -> "mainClientMaxConnections", "value" -> config.mainClientMaxConnections).asJava,
          Map("name" -> "mainClientMaxConnectionsPerHost", "value" -> config.mainClientMaxConnectionsPerHost).asJava,
          Map("name" -> "proxyClientMaxConnections", "value" -> config.proxyClientMaxConnections).asJava,
          Map("name" -> "proxyClientMaxConnectionsPerHost", "value" -> config.proxyClientMaxConnectionsPerHost).asJava
        ).asJava
      ).asJava)
    case (Methods.PUT, "connectionTimeout") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetConnectionTimeout(Duration.apply(newV.toLong, MILLISECONDS)))
    case (Methods.PUT, "connectionIdleTimeout") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetConnectionIdleTimeout(Duration.apply(newV.toLong, MILLISECONDS)))
    case (Methods.PUT, "mainClientMaxConnections") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetMainClientMaxConnections(newV.toInt))
    case (Methods.PUT, "mainClientMaxConnectionsPerHost") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetMainClientMaxConnectionsPerHost(newV.toInt))
    case (Methods.PUT, "proxyClientMaxConnections") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetProxyClientMaxConnections(newV.toInt))
    case (Methods.PUT, "proxyClientMaxConnectionsPerHost") =>
      val newV = exchange.getQueryParameters.get("v").getFirst
      setConfigAndRespond(exchange,
        Repox.configPersister ? SetProxyClientMaxConnectionsPerHost(newV.toInt))
  }
}