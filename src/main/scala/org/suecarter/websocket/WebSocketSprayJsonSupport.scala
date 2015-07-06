package org.suecarter.websocket

import akka.actor.Actor.Receive
import akka.actor._

import scala.util._
import scala.util.control.NonFatal

import spray.can.websocket._
import spray.can.websocket.frame._

import spray.json._

protected trait SprayJsonSupport {
  this: ActorLogging =>

  type Incoming
  type Outgoing

  // not a PartialFunction, everything must be handled
  def incoming: Function[Incoming, Unit]
  def message: Receive

  implicit def incomingFormat: JsonReader[Incoming]
  implicit def outgoingFormat: JsonWriter[Outgoing]

  def jsonErr(json: JsValue, t: DeserializationException): Unit

  final def json: Receive = {
    case JsValue(json) =>
      Try(json.convertTo[Incoming]) match {
        case Success(payload) => incoming(payload)
        case Failure(d: DeserializationException) => jsonErr(json, d)
        case Failure(t) => throw t
      }
  }

  // note that failed matches on this will internally throw an
  // exception (expensive), so position carefully within the pattern
  // match and reuse the result as much as possible.
  object JsValue {
    def unapply(a: Any): Option[JsValue] = a match {
      case frame: TextFrame =>
        try Some(JsonParser(ParserInput(frame.payload.utf8String)))
        catch {
          case NonFatal(t) =>
            log.warning(s"not valid JSON: ${frame.payload.utf8String}")
            None
        }
      case _ => None
    }
  }

}

/**
 * Provides conveniences to create pattern matchers for
 * JSON-marshalled incoming `Frame` messages and for sending
 * JSON-marshallable outgoing messages wrapped as `CommandFrame`.
 */
trait WebSocketServerSprayJsonSupport extends SprayJsonSupport {
  this: WebSocketComboWorker =>

  final override def websockets: Receive = json orElse message

  // def send(t: Outgoing): Unit = {
  //   val body = outgoingFormat.write(t).compactPrint
  //   send(TextFrame(body))
  // }

  def sendWithAck(t: Outgoing, downstream: ActorRef = sender()): Unit = {
    val body = outgoingFormat.write(t).compactPrint
    sendWithAck(TextFrame(body), downstream)
  }
}

trait WebSocketClientSprayJsonSupport extends SprayJsonSupport {
  this: WebSocketClient =>

  final override def websockets: Receive = json orElse message

  // def send(t: Outgoing): Unit = {
  //   val body = outgoingFormat.write(t).compactPrint
  //   connection ! TextFrame(body)
  // }

  def sendWithAck(t: Outgoing, downstream: ActorRef = sender()): Unit = {
    val body = outgoingFormat.write(t).compactPrint
    sendWithAck(TextFrame(body), downstream)
  }

}
