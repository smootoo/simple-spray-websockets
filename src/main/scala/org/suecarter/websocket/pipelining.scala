package org.suecarter.websocket

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.actor.{ ActorRefFactory, ActorRef }
import akka.util.Timeout
import akka.pattern.ask
import akka.io.IO
import spray.can.server.UHttp
import spray.httpx.{ ResponseTransformation, RequestBuilding }
import spray.can.Http
import spray.util.actorSystem
import spray.http._

/**
 * Copied from https://github.com/spray/spray/blob/master/spray-client/src/main/scala/spray/client/pipelining.scala#L35
 *
 * (copyright Spray, licensed Apache 2.0)
 *
 * Workaround for caveat #1 in WebSocket.scala.
 */
object pipelining extends RequestBuilding with ResponseTransformation {
  type SendReceive = HttpRequest => Future[HttpResponse]

  def sendReceive(implicit refFactory: ActorRefFactory, executionContext: ExecutionContext,
    futureTimeout: Timeout = 60.seconds): SendReceive =
    sendReceive(IO(UHttp)(actorSystem))

  def sendReceive(transport: ActorRef)(implicit ec: ExecutionContext, futureTimeout: Timeout): SendReceive =
    request => transport ? request map {
      case x: HttpResponse => x
      case x: HttpResponsePart => sys.error("sendReceive doesn't support chunked responses, try sendTo instead")
      case x: Http.ConnectionClosed => sys.error("Connection closed before reception of response: " + x)
      case x => sys.error("Unexpected response from HTTP transport: " + x)
    }

  def sendTo(transport: ActorRef) = new SendTo(transport)

  class SendTo(transport: ActorRef) {
    def withResponsesReceivedBy(receiver: ActorRef): HttpRequest => Unit =
      request => transport.tell(request, receiver)
  }
}
