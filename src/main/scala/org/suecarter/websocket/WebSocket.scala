package org.suecarter.websocket

import akka.io.Tcp.Command
import akka.io.Tcp.CommandFailed
import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import akka.io._
import akka.pattern.ask
import akka.util._
import scala.concurrent.Future
import scala.util.Success
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket._
import spray.can.websocket.frame._
import spray.http._
import spray.io.{ ClientSSLEngineProvider, ServerSSLEngineProvider }
import spray.routing._

/**
 * Spawns and registers WebSocket-capable worker actors with each new
 * HTTP connection. A convenience is provided in the companion to
 * create a new web socket server.
 *
 * Caveats:
 *
 * 1. Not possible to use IO(Http) and IO(Uhttp) in an ActorSystem:
 *    https://github.com/wandoulabs/spray-websocket/issues/44 Use the
 *    bundled pipelining instead of the Spray Client one (there may be
 *    other third party libraries that use IO(Http))
 *
 * 2. An upgrade request on any path is valid (not restricted):
 *    https://github.com/wandoulabs/spray-websocket/issues/67
 *
 * 3. Backpressure is implemented as a hack, because wandoulabs
 *    didn't consider this to be an important feature.
 *    https://github.com/wandoulabs/spray-websocket/issues/68
 *
 * @param workerFactory taking an HTTP connection actor and returning
 *                      the Props for a `WebSocketServerWorker`
 *                      implementation. `WebSocketComboWorker` is
 *                      provided to simplify the implementation of
 *                      REST/WebSocket servers. Note that a new actor
 *                      is created to serve every request (so there is
 *                      no need to apply the netaporter pattern in
 *                      Spray Route rules).
 */
class WebSocketServer(workerFactory: ActorRef => Props) extends Actor with ActorLogging {
  def receive = LoggingReceive {
    case Http.Connected(remoteAddress, localAddress) =>
      val connection = sender()

      val worker = context.actorOf(
        workerFactory(connection),
        // descriptive actor hierarchy name (includes the remote connection)
        s"${remoteAddress.getHostName}:${remoteAddress.getPort()}:${UUID.randomUUID().toString.take(4)}"
      )
      connection ! Http.Register(worker)
  }
}
object WebSocketServer {
  /**
   * Starts an HTTP server as a child of the implicit context,
   * spawning a worker actor (implementing `WebSocketServerWorker`)
   * for every incoming connection.
   *
   * @param interface to bind to.
   * @param port to bind to (0 will attempt to find a free port).
   * @param workerProps as defined in the `WebSocketServer` docs.
   * @param name of the server actor.
   * @param context the supervisor.
   * @param timeout for receiving the binding (the binding may
   *        still succeed even if this future times out).
   * @return the server actor and a future containing the binding.
   */
  def start(
    interface: String,
    port: Int,
    workerProps: ActorRef => Props,
    name: String = "uhttp"
  )(implicit
    context: ActorRefFactory,
    system: ActorSystem,
    ssl: ServerSSLEngineProvider,
    timeout: Timeout): (ActorRef, Future[Tcp.Event]) = {
    val worker: ActorRef => Props = workerProps(_).withMailbox(
      "org.suecarter.websocket.high-priority-ack-mailbox"
    )

    val serverProps = Props(classOf[WebSocketServer], worker)
    val server = context.actorOf(serverProps, name)

    // If you get an exception on this line about "IO-HTTP" actor
    // already existing, then you've fallen foul of caveat #1,
    // documented at the top of this file. The solution is to call
    // startupUhttpFirst as early as possible when first starting your
    // ActorSystem and then find out which other component is trying
    // to register an actor at the same path. A workaround is provided
    // for integration with Spray Client, but other libraries are
    // expected to fall foul of the caveat.
    val binding = IO(UHttp) ? Http.Bind(server, interface, port)
    (server, binding.mapTo[Tcp.Event])
  }

  /**
   * Evil hack to workaround caveat #1 as documented above.
   *
   * Do this first on any `ActorSystem` when running tests or the like.
   */
  def startupUhttpFirst()(implicit system: ActorSystem): Unit = {
    IO(UHttp)
  }
}

/**
 * Abstract actor that makes an HTTP connection request to the
 * provided location and upgrades to WebSocket when connected.
 *
 * NOTE: this uses a Stash so needs a dequeue mailbox. The included
 *
 * {{{
 *    .withMailbox("org.suecarter.websocket.high-priority-ack-mailbox")
 * }}}
 *
 * is highly recommended.
 */
abstract class WebSocketClient(
    host: String,
    port: Int,
    path: String = "/",
    ssl: Boolean = false
) extends WebSocketClientWorker with Stash with WebSocketCommon {
  override def preStart(): Unit = {
    import context.system

    IO(UHttp) ! Http.Connect(host, port, sslEncryption = ssl)
  }

  // WORKAROUND:
  // https://github.com/wandoulabs/spray-websocket/issues/70
  // (no need to copy the rest of the file because they otherwise
  // handle stashing)
  override def receive = handshaking orElse closeLogic orElse stashing

  val WebSocketUpgradeHeaders = List(
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    // base64 encoding of the WebSocket GUID
    // see http://tools.ietf.org/html/rfc6455
    // 258EAFA5-E914-47DA-95CA-C5AB0DC85B11
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
    HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate")
  )

  def userHeaders: List[HttpHeader] = Nil

  def upgradeRequest = HttpRequest(
    HttpMethods.GET, path,
    HttpHeaders.Host(host, port) :: WebSocketUpgradeHeaders ::: userHeaders
  )

  // workaround upstream naming convention
  final def businessLogic: Receive = websockets
  def websockets: Receive

}

/** Ack received in response to WebSocketComboWorker.sendWithAck */
object Ack extends Tcp.Event with spray.io.Droppable {
  override def toString = "Ack"
}

/**
 * Provides a UHTTP-enabled worker for an HTTP connection with the
 * ability to deal with both REST and WebSocket messages.
 *
 * Mostly copied from `WebSocketServerWorker` with workarounds for
 * acking and stashing.
 */
abstract class WebSocketComboWorker(
    val connection: ActorRef
) extends Actor with ActorLogging with Stash with WebSocketCommon {
  import spray.can.websocket

  // headers may be useful for authentication and such
  // NOTE these are only available in the WebSocket code
  var headers: List[HttpHeader] = _

  // from upstream
  def closeLogic: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
  }

  // from upstream plus unstashAll() and setting maskingKey
  def handshaking: Receive = {
    case websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure =>
          sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext =>
          headers = wsContext.request.headers
          // https://github.com/wandoulabs/spray-websocket/issues/69
          // maskingKey = ???
          sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
      }

    case UHttp.Upgraded =>
      log.info(s"Upgraded with $headers")

      context.become(websockets orElse closeLogic)
      self ! websocket.UpgradedToWebSocket
      unstashAll()
  }

  // our rest logic PLUS https://github.com/wandoulabs/spray-websocket/issues/70
  def receive = handshaking orElse rest orElse closeLogic orElse stashing

  /** User-defined websocket handler. */
  def websockets: Receive

  /**
   * User-defined REST handler.
   *
   * Defined as a `Receive` for performance (to allow sharing of
   * `Route` between sessions).
   */
  def rest: Receive

  // from upstream
  // def send(frame: Frame) {
  //   serverConnection ! FrameCommand(frame)
  // }
}

/**
 * Convenient, but not performance optimal, implementation of
 * `WebSocketComboWorker` that recreates a Spray `Route` for every
 * connection.
 */
abstract class SimpleWebSocketComboWorker(conn: ActorRef)
    extends WebSocketComboWorker(conn) with HttpService {

  implicit def actorRefFactory: ActorRefFactory = context
  implicit def settings = RoutingSettings.default(actorRefFactory)
  implicit def handler = ExceptionHandler.default
  implicit def rejecter = RejectionHandler.Default
  final def rest: Receive = runRoute(route)

  def route: Route
}

// actor logic shared between client and server workers
private[websocket] trait WebSocketCommon {
  this: Actor with Stash with ActorLogging =>
  def connection: ActorRef
  def closeLogic: Receive

  /**
   * Wandoulabs' WebSocket implementation doesn't support ack/nack-ing
   * on the CommandFrame level. But it is possible to drop down to TCP
   * messages by duplicating their FrameRendering logic in the
   * ServerWorker actor. Here we construct a payload that is ignored
   * by the ConnectionManager which requests an Ack.
   *
   * A similar pattern could be applied for non-Text Frames and NACK
   * based writing, in order to skip the FrameRendering logic.
   *
   * WARNING: callers should be aware of the implications of changing
   * the state in this method. Multiple calls to this **must not** be
   * made in the same receive block, or the earlier Acks will be
   * missed.
   *
   * For more information, see
   * https://groups.google.com/d/msg/akka-user/ckUJ9wlltuc/h37ZRCkAA6cJ
   */
  def sendWithAck(frame: TextFrame, downstream: ActorRef): Unit = {
    connection ! Tcp.Write(FrameRender(frame), Ack)
    context.become(closeLogic orElse waitingForAck(downstream, frame) orElse stashing, discardOld = false)
  }

  def stashing: Receive = {
    case msg => stash()
  }

  def waitingForAck(downstream: ActorRef, sending: Frame): Receive = {
    case Ack =>
      context.unbecome()
      unstashAll()

      if (downstream != self)
        downstream forward Ack

    case FrameCommandFailed(frame: Frame, _) if frame == sending =>
      log.error(s"Failed to send frame, retrying: $frame")

      connection ! Tcp.ResumeWriting

      context.become(
        waitingForRecovery(frame) orElse closeLogic orElse stashing,
        discardOld = false
      )
  }

  def waitingForRecovery(frame: Frame): Receive = {
    case Tcp.WritingResumed =>
      connection ! Tcp.Write(FrameRender(frame), Ack)
      context.unbecome()
    // is there a message that says "sorry, can't resume" that we can handle?
  }

}
