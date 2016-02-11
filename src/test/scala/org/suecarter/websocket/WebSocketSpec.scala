package org.suecarter.websocket

import java.net.InetSocketAddress

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

import org.suecarter.utils._

/*
NOTE: we have intentionally not used spray-testkit as we want to
confirm that the actual endpoints are functioning as expected (we
don't really care about testing the Route that we have created).

It would be nice to use HTTP TestKit, but the docs have not yet been
written for it or the akka-http client.
*/
class WebSocketSpec extends AkkaFlatSpec {
  import WebSocketSpec._

  "REST endpoints" should "serve valid requests" in {
    assert(get("/hello").entity.asString === "<h1>Greetings!</h1>")
  }

  it should "reject invalid requests" in {
    assert(get("/goodbye").status === StatusCodes.NotFound)
  }

  "Server worker actor" should "stop when connection is closed" in {
    val connected = Http.Connected(new InetSocketAddress(0), new InetSocketAddress(0))
    val Http.Register(worker, _) = Await.result(server ? connected, 10 seconds)
    watch(worker)
    worker ! Http.Closed
    expectTerminated(worker, 10 seconds)
  }

  "WebSocket upgrade" should "server foo (with Ack) and client bar (with Ack)" in {
    val client = newClientAndWaitForFooBar()

    system.stop(client)
  }

  it should "client actor stop when it closes the connection" in {
    val client = newClientAndWaitForFooBar()

    watch(client)

    client ! Close

    fishForMessage() {
      case Terminated(actor) if actor == client => true
      case _ => false
    }
  }

  it should "client and server can send large frames" in {
    val client = newClientAndWaitForFooBar()

    client ! Big

    receiveN(2) should contain theSameElementsAs (
      List(Ack, Big)
    )

  }

  // must be last test -- we're stopping the test server
  it should "client actor stop when the server stops" in {
    val client = newClientAndWaitForFooBar()

    watch(client)

    system.stop(server)

    fishForMessage() {
      case Terminated(actor) if actor == client => true
      case _ => false
    }
  }

  var host: String = _
  var port: Int = _
  var server: ActorRef = _
  override def beforeAll(): Unit = {
    super.beforeAll()

    import system.dispatcher

    val workerProps = (conn: ActorRef) => Props(classOf[TestWorker], testActor, conn)
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

  def newClientAndWaitForFooBar(): ActorRef = {
    val client = system.actorOf(Props(
      classOf[TestClient], testActor, host, port
    ).withMailbox(
      "org.suecarter.websocket.high-priority-ack-mailbox"
    ))

    client ! Foo

    receiveN(6) should contain theSameElementsAs (
      List(UpgradedToWebSocket, UpgradedToWebSocket, Foo, Ack, Bar, Ack)
    )

    client
  }

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
  val Foo = TextFrame("FOO")
  val Bar = TextFrame("BAR")
  val Close = CloseFrame()
  // this message should be larger than typical TCP buffers
  val Big = "hello world!" * 1024 * 1024
  val BigFrame = TextFrame(Big)

  // can't be in the test or reflection fails
  class TestWorker(testActor: ActorRef, conn: ActorRef)
      extends SimpleWebSocketComboWorker(conn) {
    val websockets: Receive = LoggingReceive {
      case UpgradedToWebSocket =>
        testActor ! UpgradedToWebSocket
      case Foo =>
        testActor ! Foo
        sendWithAck(Bar, testActor)

      case str: String =>
        sendWithAck(TextFrame(str), self)

      case BigFrame =>
        sendWithAck(BigFrame, self)

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
      port: Int
  ) extends WebSocketClient(host, port) {
    def websockets: Receive = LoggingReceive {
      case UpgradedToWebSocket =>
        testActor ! UpgradedToWebSocket

      case Foo =>
        sendWithAck(Foo, sender()) // testActor sends us Foo, so Ack goes to them

      case Close =>
        connection ! Close

      case Bar =>
        testActor ! Bar

      case Big =>
        sendWithAck(BigFrame, sender())

      case frame: TextFrame =>
        testActor ! frame.payload.utf8String
    }
  }
}
