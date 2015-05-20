# simple-spray-websockets
[![Build Status](https://travis-ci.org/smootoo/simple-spray-websockets.svg?branch=master)](https://travis-ci.org/smootoo/simple-spray-websockets)
[![Coverage Status](https://coveralls.io/repos/smootoo/simple-spray-websockets/badge.svg?branch=master)](https://coveralls.io/r/smootoo/simple-spray-websockets?branch=master)

Simple example of REST/WebSockets on Spray Can.

This small project introduces convenience classes / patterns to set up
a server that can handle both REST and WebSockets as well as the
example server and client actors below.

[spray-websocket by Wandou Labs](https://github.com/wandoulabs/spray-websocket)
is an extension to [spray](https://github.com/spray/spray) adding the
[WebSocket](https://tools.ietf.org/html/rfc6455) asynchronous upgrade
to HTTP. Use of `spray-websocket` is quite low level and requires some
boilerplate to get an example up and running.

## Actor per Request

An added advantage of using this pattern is that it automatically
provides the
[net-a-porter actor-per-request](https://github.com/NET-A-PORTER/spray-actor-per-request)
pattern (by accident). This is because an Actor is spawned to deal
with every incoming request and will serve a REST request unless a
stateful WebSocket `Upgrade` is obtained, at which point it switches
to serving async messages until closed.

## Server Backpressure

Back-pressure on the HTTP `Route`s is provided by
[spray-can](http://spray.io/documentation/1.2.2/spray-can/configuration/)
if enabled.

Back-pressure with wandoulabs WebSockets is an afterthought and must
be carefully crafted. We provide a `sendWithAck(f: TextFrame)` method
in the server which will allow us to receive an `Ack`. Backpressure
must therefore be written in the application tier using the `become`
principles outlined in
[the akka-io documentation](http://doc.akka.io/docs/akka/snapshot/scala/io-tcp.html#throttling-reads-and-writes).
The documentation on `sendWithAck` provides more information about how
to implement acks/nacks for other types of frames.

## Performance

The `SimpleWebSocketComboWorker` is simple to understand and implement
but if you want high performance, you will want to use the lower level
`WebSocketComboWorker` and share your `runRoute` between actors to
save on the initialisation costs per connection.


## Example

The following example is created and tested in
[`WebSocketsSpec.scala`](src/test/scala/org/suecarter/websocket/WebSocketSpec.scala).

```scala
val Ping = TextFrame("PING")
val Pong = TextFrame("PONG")

class ExampleServer(conn: ActorRef) extends SimpleWebSocketComboWorker(conn) {
  def websockets = {
    case UpgradedToWebSocket =>
    case Ping => sendWithAck(Pong)
    case Ack => // useful for backpressure
  }

  def route = path("hello") {
    get {
      complete {
        <h1>Greetings!</h1>
      }
    }
  }
}

class ExampleClient extends WebSocketClient(host, port) {
  def websockets: Receive = {
    case UpgradedToWebSocket =>
      connection ! Ping
    case Pong =>
  }
}
```
