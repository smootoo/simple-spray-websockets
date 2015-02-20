package org.suecarter.websocket

import akka.actor._
import akka.event.LoggingReceive
import akka.io.Tcp.Bound
import akka.io.Tcp.Write
import akka.io._
import akka.pattern.ask
import akka.testkit._
import akka.util._
import org.scalatest._
import scala.concurrent._
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket._
import spray.can.websocket.frame._
import spray.http._
import spray.http.HttpMethods._

import org.suecarter.utils.AkkaWordSpec

/*
NOTE: we have intentionally not used spray-testkit as we want to
confirm that the actual endpoints are functioning as expected (we
don't really care about testing the Route that we have created).

It would be nice to use HTTP TestKit, but the docs have not yet been
written for it or the akka-http client.
*/
class WebSocketSpec extends AkkaWordSpec {
  import WebSocketSpec._

  "REST endpoints" should {
    "serve valid requests" in {
      assert(get("/hello").entity.asString === "<h1>Greetings!</h1>")
    }

    "reject invalid requests" in {
      assert(get("/goodbye").status === StatusCodes.NotFound)
    }
  }

  "WebSocket upgrade" should {
    "server ping (with Ack) and client pong" in {
      val client = newClient()

      receiveN(4) should contain theSameElementsAs (
        List(UpgradedToWebSocket, Ping, Ack, Pong)
      )

      system.stop(client)
    }

    "client actor stop when it closes the connection" in {
      val client = newClient()
      watch(client)

      fishForMessage() {
        case UpgradedToWebSocket => true
        case _ => false
      }

      client ! Close
      fishForMessage() {
        case Terminated(actor) if actor == client => true
        case _ => false
      }
    }

    "client and server can send large frames" in {
      val client = newClient()

      receiveN(4) should contain theSameElementsAs (
        List(UpgradedToWebSocket, Ping, Ack, Pong)
      )

      client ! Big
      val got = expectMsgType[String](10 seconds)
      assert(got == Big, s"got ${got.take(100)}")
    }

    // must be last test -- we're stopping the test server
    "client actor stop when the server stops" in {
      val client = newClient()
      watch(client)

      fishForMessage() {
        case UpgradedToWebSocket => true
        case _ => false
      }
      system.stop(server)

      fishForMessage() {
        case Terminated(actor) if actor == client => true
        case _ => false
      }
    }
  }

  var host: String = _
  var port: Int = _
  var server: ActorRef = _
  override def beforeAll(): Unit = {
    super.beforeAll()

    import system.dispatcher

    val workerProps = (conn: ActorRef) =>
      Props(classOf[TestWorker], testActor, conn)
    val (server, command) = WebSocketServer.start("127.0.0.1", 0, workerProps)

    val binding = Await.result(command, 1 minute) match {
      case bound: Tcp.Bound => bound
      case other => fail(s"failed to bring up server $other")
    }
    val address = binding.localAddress
    host = address.getHostName
    port = address.getPort
    this.server = server
  }

  def newClient(): ActorRef = system.actorOf(Props(
    classOf[TestClient], testActor, host, port
  ))

  def get(path: String): HttpResponse = {
    import system.dispatcher
    val setup = Http.HostConnectorSetup(host, port)
    val resp = for {
      Http.HostConnectorInfo(conn, _) <- IO(UHttp) ? setup
      response <- (conn ? HttpRequest(GET, path)).mapTo[HttpResponse]
      _ <- conn ? Http.CloseAll
    } yield response
    Await.result(resp, timeout.duration)
  }

}

object WebSocketSpec {
  // not the same as the Ping/Pong in the WebSocket spec
  val Ping = TextFrame("PING")
  val Pong = TextFrame("PONG")
  val Close = CloseFrame()
  // this message should be larger than typical TCP buffers
  val Big = "hello world!" * 1024 * 1024
  val BigFrame = TextFrame(Big)

  // can't be in the test or reflection fails
  class TestWorker(testActor: ActorRef, conn: ActorRef)
      extends SimpleWebSocketComboWorker(conn) {
    val websockets: Receive = LoggingReceive {
      case UpgradedToWebSocket =>
      case Ping =>
        testActor ! Ping
        sendWithAck(Pong)

      case Ack =>
        testActor ! Ack

      case str: String =>
        send(TextFrame(str))

      case BigFrame =>
        send(BigFrame)

    }

    def route = path("hello") {
      get {
        complete {
          <h1>Greetings!</h1>
        }
      }
    }
  }

  class TestClient(
      testActor: ActorRef,
      host: String,
      port: Int) extends WebSocketClient(host, port) {
    def websockets: Receive = LoggingReceive {
      case UpgradedToWebSocket =>
        testActor ! UpgradedToWebSocket
        connection ! Ping
      case Close =>
        connection ! Close

      case Pong =>
        testActor ! Pong

      case Big =>
        connection ! BigFrame

      case frame: TextFrame =>
        testActor ! frame.payload.utf8String
    }
  }
}
