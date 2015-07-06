package testing

import akka.actor._
import akka.event.slf4j.SLF4JLogging
import akka.io.Tcp.NoAck
import akka.io._
import java.util.UUID
import spray.can.websocket._
import org.suecarter.websocket._

import spray.can._
import spray.can.server.UHttp
import spray.can.websocket.frame.FrameRender
import spray.http._

import concurrent.duration._
import akka.util.Timeout
import spray.can.websocket.frame.TextFrame

/**
 * Server that simply listens for text messages to stress test the
 * write failed errors that we've been seeing. Trying to trigger
 * the sporadic failure we've seen:
 *
 *  https://github.com/wandoulabs/spray-websocket/issues/79
 */
class BuggyServer(conn: ActorRef) extends SimpleWebSocketComboWorker(conn) {
  def websockets = {
    case UpgradedToWebSocket =>
    case frame: TextFrame =>
      log.info(s"got message of length ${frame.payload.length}")
  }

  def route = complete { "Hello" }
}
object BuggyServer extends App with SLF4JLogging {
  implicit val system = ActorSystem()
  import system.dispatcher
  implicit val timeout = Timeout(10 seconds)

  val worker = (conn: ActorRef) => Props(new BuggyServer(conn))
  val (server, command) = WebSocketServer.start(Buggy.host, Buggy.port, worker)
  command.map {
    case bound: Tcp.Bound =>
    case other => log.error("Failed to bind. Kill me now.")
  }
}

object Buggy {
  // for running on a remote
  val host = "fommil.com"
  val port = 9081
}

// websocket/test:run-main testing.BuggyClient
class BuggyClient(
    host: String,
    port: Int
) extends WebSocketClient(host, port) {
  object Go

  val top = self
  val goer = context.actorOf(Props(
    new Actor {
      def receive: Receive = {
        case Ack =>
          log.info("ack")
        //top ! Go
      }
    }
  ))

  def websockets: Receive = {
    case UpgradedToWebSocket =>
      self ! Go

    case Go =>
      // ok     36040032
      // not ok 36040033
      log.info("sending")

      //connection ! TextFrame("A" * 36040033)
      //connection ! TextFrame("A" * 36040032)
      connection ! Tcp.Write(FrameRender(TextFrame("A" * 36040033)), NoAck("Big thing"))
      //sendWithAck(TextFrame("A" * 36040033), goer)

      //sendWithAck(TextFrame("B" * 36040033), goer)
      sendWithAck(TextFrame(UUID.randomUUID.toString), goer)

    case other =>
      log.error("WTF?" + other.getClass)

  }
}
object BuggyClient extends App {
  import BuggyServer._
  val system = ActorSystem()
  system.actorOf(
    Props(new BuggyClient(Buggy.host, Buggy.port)).withMailbox(
      "org.suecarter.websocket.high-priority-ack-mailbox"
    )
  )
}
