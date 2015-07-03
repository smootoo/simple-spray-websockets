package org.suecarter.websocket

import akka.actor._
import akka.dispatch._
import com.typesafe.config.Config
import java.util.concurrent.LinkedBlockingDeque

import spray.can.websocket.FrameCommandFailed

/**
 * When using `context.become` to wait for an `Ack`, then `Ack` will
 * normally be placed at the end of the queue. This custom mailbox
 * will prioritise `Ack` messages so that they are always placed at
 * the front of the queue.
 *
 * This showed a performance improvement of 1 hour to 2 minutes when
 * sending about 100,000 messages, as the client actor was spending
 * the vast majority of its time traversing the work queue and
 * re-stashing messages.
 */
case class HighPriorityAckMailbox(settings: ActorSystem.Settings, config: Config)
    extends PriorityUnboundedDequeMailbox(settings, config) {
  override def priority(e: Envelope): Boolean = e.message match {
    case Ack => true
    case fail: FrameCommandFailed => true
    case _ => false
  }
}

/**
 * Specialist priority (user provides the rules), unbounded, deque
 * (can be used for Stashing) mailbox.
 *
 * Very useful for messages of high priority, such as `Ack`s in I/O
 * situations.
 *
 * Based on UnboundedDequeBasedMailbox from Akka.
 */
abstract class PriorityUnboundedDequeMailbox extends MailboxType
    with ProducesMessageQueue[UnboundedDequeBasedMailbox.MessageQueue] {
  def this(settings: ActorSystem.Settings, config: Config) = this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new PriorityUnboundedDequeMailbox.MessageQueue(priority)

  /**
   * When true, the queue will place this envelope at the front of the
   * queue (as if it was just stashed).
   */
  def priority(e: Envelope): Boolean
}

object PriorityUnboundedDequeMailbox {
  class MessageQueue(priority: Envelope => Boolean) extends LinkedBlockingDeque[Envelope] with UnboundedDequeBasedMessageQueue {
    final val queue = this

    override def enqueue(receiver: ActorRef, handle: Envelope): Unit =
      if (priority(handle)) super.enqueueFirst(receiver, handle)
      else super.enqueue(receiver, handle)
  }
}
