package org.suecarter.websocket

import akka.actor.Actor
import akka.actor.Actor.Receive
import akka.actor.ActorLogging
import akka.testkit.TestActorRef
import spray.can.websocket.frame.TextFrame

import spray.json._

import org.suecarter.utils._

import SprayJsonSupportTestActor._

class WebSocketSprayJsonSupportSpec extends AkkaFlatSpec {

  val actor = TestActorRef[SprayJsonSupportTestActor]

  "SprayJsonSupport" should "unmarshall API messages" in {
    actor ! TextFrame("""{"text": "hello"}""")
    expectMsg(Foo("hello"))
  }

  it should "unmarshall API messages containing UTF-8" in {
    actor ! TextFrame("""{"text": "¢"}""")
    expectMsg(Foo("¢"))
  }

  it should "not unmarshall non-API messages" in {
    val frame = TextFrame("not hello")
    actor ! frame
    expectMsg(frame)
  }

  it should "handle JSON deserialisation errors" in {
    actor ! TextFrame("""{"blah": "hello"}""")
    expectMsgPF() {
      case (json: JsValue, t: DeserializationException) =>
    }
  }

}

object SprayJsonSupportTestActor {
  case class Foo(text: String)
  case class Bar(number: Long)
}
class SprayJsonSupportTestActor extends Actor with ActorLogging
    with SprayJsonSupport {
  import DefaultJsonProtocol._
  type Incoming = Foo
  type Outgoing = Bar
  implicit def incomingFormat: JsonReader[Incoming] = jsonFormat1(Foo)
  implicit def outgoingFormat: JsonWriter[Outgoing] = jsonFormat1(Bar)

  def incoming: PartialFunction[Incoming, Unit] = {
    case i: Incoming => sender() ! i
  }
  def message: Receive = {
    case other => sender() ! other
  }
  def jsonErr(json: JsValue, t: DeserializationException): Unit = {
    sender() ! (json, t)
  }

  def receive = json orElse message
}
