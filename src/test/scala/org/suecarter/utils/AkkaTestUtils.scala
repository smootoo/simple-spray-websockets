package org.suecarter.utils

import akka.actor._
import akka.event.slf4j.SLF4JLogging
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import org.scalatest.Assertions
import java.util.UUID
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

/** Convenience for the boilerplate of setting up a test using an Akka System */
abstract class AkkaWordSpec
    extends HasSystem with TestKitBase
    with DefaultTimeout with ImplicitSender
    with Matchers with SLF4JLogging
    with StoppingTestActorRefs
    with WordSpecLike with SetupAndTearDownSystem {
}

// for when `system` must be defined ahead of a mixin
trait HasSystem {
  implicit val system = ActorSystem()
}

/** equivalent for spray-testkit use (non-trivial ordering of mixins) */
abstract class SprayWordSpec
    extends WordSpecLike with Matchers with SetupAndTearDownSystem
    with StoppingTestActorRefs
    with ScalatestRouteTest with HttpService
    with TestKitBase with DefaultTimeout with ImplicitSender
    with SLF4JLogging {
  def actorRefFactory = system
}

trait SetupAndTearDownSystem extends BeforeAndAfterAll {
  this: WordSpecLike with TestKitBase =>
  // can't mix in SetupAndTearDownSystem because fixture._ is different API
  override protected def beforeAll(): Unit = {
    super.beforeAll()
    SLFJ4BridgeUtil.initialiseBridge()
  }
  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}

/** equivalent fixture.WordSpec */
abstract class AkkaFixtureWordSpec
    extends HasSystem with TestKitBase
    with DefaultTimeout with ImplicitSender
    with Matchers with SLF4JLogging
    with StoppingTestActorRefs
    with org.scalatest.fixture.WordSpecLike with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    super.beforeAll()
    SLFJ4BridgeUtil.initialiseBridge()
  }
  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}

/**
 * `TestActorRef`s are restarted by default on exceptions, but this
 * just stops them.
 *
 * https://groups.google.com/forum/#!topic/akka-user/0Ene7WaDyng
 */
trait StoppingTestActorRefs {
  this: TestKitBase =>

  private lazy val supervisor = system.actorOf(Props[StoppingSupervisor])
  private def randomName = UUID.randomUUID().toString.replace("-", "")

  def StoppingTestActorRef[T <: Actor: ClassTag](props: Props) =
    TestActorRef[T](props, supervisor, randomName)
}
class StoppingSupervisor extends Actor {
  def receive = Actor.emptyBehavior
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
}
