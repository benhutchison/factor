package factor

import akka.actor._
import akka.remote._
import akka.actor.Status._
import akka.pattern.ask
import akka.util._
import com.typesafe.config._

import cats.implicits._
import cats.effect._
import mouse.all._

import scala.concurrent._
import scala.concurrent.duration._

/** A "Factor" (functional-actor) is a potentially remote Akka actor that stores two kinds
  * of internal data:
  *  - It has an immutable "environment" of some type E, representing some kind of initial context or configuration specific
  *  to the actor that never changes. If not needed it can be set to `Unit`
  *  - It has a state of type S that can be modified by computations performed in the actor
  *
  * And has exactly one behavior:
  * - When sent a Function2 instance (aka lambda, closure), it invokes passed the function passing it E and S. It requires
  * the function to return a tuple `(S, A)`, being the new state `S` and the reply `A` The reply is then sent as a message to
  * the sender.
  *
  * If an exception or timeout occurs during invocation, the state is not updated, and the sender is sent a failure message.
  *
  * If it is sent any message save a `Function2` a failure is sent back.
  *
  * The code `f` passed to a Factor should be synchronous and not include async non-blocking operations.
  * That is, it should act locally over the S and E data only. If remote/async calls are needed, do them before or after
  * accessing the state, from an AsyncActor.
  * */
class Factor[E, S](env: E, initState: S, timeout: Duration) extends Actor {
  var state: S = initState

  def receive = {
    case f: Function2[E, S, IO[(S, Any)] @unchecked] =>
      try {
        f(env, state).unsafeRunTimed(timeout) match {
          case Some((nextState, reply)) =>
            sender() ! reply
            state = nextState
          case None =>
            sender() ! Failure(new FactorTimeout(s"Actor: ${this.toString}: Timeout after: ${timeout} on: ${f}"))
        }
      }
      catch {
        case e: Exception => sender() ! Failure(e)
      }
    case other =>
      sender() ! Failure(new IllegalArgumentException(s"Actor: ${this.toString}: Unexpected msg: $other"))
  }
}

/** A stateless, single use actor intended to allow asynchronous operations to be run on a given address.
  *
  * For example, orchestrating a chain of calls to other actors either locally or remote.
  *
  * The actor will stop itself after receiving just one message, but any Futures/Tasks that are in flight will complete
  * regardless.
  * */
class AsyncActor(timeout: FiniteDuration) extends Actor {
  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case f: Function0[IO[Any] @unchecked] =>
      val sendr = sender()
      f().timeout(timeout).unsafeRunAsync {
        case Right(a) => sendr ! a
        case Left(e) => sendr ! Failure(e)
      }
      self ! PoisonPill
    case other =>

      sender() ! Failure(new IllegalArgumentException(s"AsyncActor@${context.system.name}: Unexpected msg: $other"))
  }
}

class SystemTerminateActor extends Actor {

  def receive = {
    case _ => context.system.terminate()
  }

}

case class SystemAddress(name: String, hostname: String, port: Int)

/** A FactorSystem can host host actors locally, or act as a client/controller to create or locate Factors on another host.
  *
  * It wraps up the underlying Akka machinery, exposing simply an FactorRef which can be used to send computations to Factors. */
case class FactorSystem(address: SystemAddress, akkaConfigOverrides: String = "") {

  val defaultConfig = ConfigFactory.parseString(s"""
   |akka {
   |  actor {
   |    provider = remote
   |    warn-about-java-serializer-usage = false
   |  }
   |  remote {
   |    artery {
   |      enabled = on
   |      transport = aeron-udp
   |      canonical.port = ${address.port}
   |      canonical.hostname = "${address.hostname}"
   |    }
   |  }
   |  log-dead-letters-during-shutdown = false
   |}
  """.stripMargin)

  val system = ActorSystem(address.name, ConfigFactory.parseString(akkaConfigOverrides).withFallback(defaultConfig))

  def createFactor[E, S](env: E, initialState: S, name: String, address: SystemAddress)(implicit timeout: FiniteDuration): FactorRef[E, S] = {
    val akkaAddress = Address("akka", address.name, address.hostname, address.port)
    val ref = system.actorOf(
      Props(new Factor[E, S](env, initialState, timeout)).withDeploy(Deploy(scope = RemoteScope(akkaAddress))), name)

    new FactorRefImpl(ref)
  }

  def createFactorS[S](initialState: S, name: String, address: SystemAddress)(implicit timeout: FiniteDuration): FactorRef[Unit, S] =
    createFactor[Unit, S]((), initialState, name, address)

  /** Locates an existing Factor previously created using `createFactor` by name and host.
    *
    * Note that the E and S types are assertions or downcasts, not guarantees.
    * */
  def findFactor[E, S](name: String, address: SystemAddress)(implicit timeout: FiniteDuration): IO[FactorRef[E, S]] = {
    val s = system.actorSelection(s"akka://${address.name}@${address.hostname}:${address.port}/user/$name")
    IO.fromFuture(IO.pure(s.resolveOne()(new Timeout(timeout)))).
      map(ref => new FactorRefImpl(ref))
  }

  /** Run a stateless, potentially async operation on a remote machine. An emphemeral actor will host it.*/
  def runAsync[A](f: ()=>IO[A], address: SystemAddress)(implicit timeout: FiniteDuration): IO[A] = {
    val akkaAddress = Address("akka", address.name, address.hostname, address.port)

    val ref = system.actorOf(Props(new AsyncActor(timeout)).withDeploy(Deploy(scope = RemoteScope(akkaAddress))))

    IO.fromFuture(IO.pure(ref.ask(f)(new Timeout(timeout)).asInstanceOf[Future[A]]))
  }
  def runAsyncUnit[A](f: ()=>IO[A], address: SystemAddress)(implicit timeout: FiniteDuration): IO[Unit] =
    runAsync(f, address) >> IO.unit


  def terminate = IO.fromFuture(IO(system.terminate()))>> IO.unit

  def terminateRemoteSystem(address: SystemAddress, waitTime: FiniteDuration = 3.second)(implicit ec: ExecutionContext) = IO {
    val akkaAddress = Address("akka", address.name, address.hostname, address.port)

    val ref = system.actorOf(Props(new SystemTerminateActor).withDeploy(Deploy(scope = RemoteScope(akkaAddress))))
    ref ! s"Terminate system at: $address"
  } >> IO.sleep(waitTime)
}


trait FactorRef[E, S] extends Serializable {

  /** The fundamental operation that Factor computation is built on: sending a serializable function to a Factor to be run
    * over its environment E and state S, yielding a new state and a reply A. */
  def run[A](f: (E, S) => IO[(S, A)])(implicit timeout: FiniteDuration): IO[A]

  //derived methods built atop run that perform more limited functions
  def runPure[A](f: (E, S) => (S, A))(implicit timeout: FiniteDuration): IO[A] = run { case (e, s) => IO.pure(f(e, s))}
  def runS[A](f: (S) => IO[(S, A)])(implicit timeout: FiniteDuration): IO[A] = run { case (e, s) => f(s)}
  def runSPure[A](f: (S) => (S, A))(implicit timeout: FiniteDuration): IO[A] = run { case (e, s) => IO.pure(f(s))}
  def runUpdatePure(f: (E, S) => S)(implicit timeout: FiniteDuration): IO[Unit] = run { case (e, s) => IO.pure(f(e, s), ())}
  def runSUpdatePure(f: (S) => S)(implicit timeout: FiniteDuration): IO[Unit] = run { case (e, s) => IO.pure(f(s), ())}
  def getS(implicit timeout: FiniteDuration): IO[S] = run { case (e, s) => IO.pure(s, s)}
  def putS(newState: S)(implicit timeout: FiniteDuration): IO[Unit] = run { case (e, s) => IO.pure(newState, ())}
  def getE(implicit timeout: FiniteDuration): IO[E] = run { case (e, s) => IO.pure(s, e)}

  def stop: Unit
}

class FactorRefImpl[E, S](ref: ActorRef) extends FactorRef[E, S] {

  def run[A](f: (E, S) => IO[(S, A)])(implicit timeout: FiniteDuration): IO[A] = {
    IO.fromFuture(IO(ref.ask(f)(new Timeout(timeout)).asInstanceOf[Future[A]]))
  }

  def stop: Unit = ref ! PoisonPill
}


object FactorSystem {

  def main(args: Array[String]) = {
    val largs = args.lift
    val optSystem = for {
      name <- largs(0)
      h <- largs(1)
      hostname <- ValidRegex.Hostname.findFirstIn(h)
      p <- largs(2)
      port <- p.parseIntOption
    } yield new FactorSystem(SystemAddress(name, hostname, port))

    println(optSystem.cata(
      sys => s"FactorSystem '${sys.address.name}' started.",
      "Usage: FactorSystem <name> <my-hostname> <port>"
    ))
  }
}


/** Supports unit testing factor business logic without starting a FactorSystem */
case class TestFactor[E, S](env: E, initState: S) extends FactorRef[E, S] {
  var state: S = initState

  def run[A](f: (E, S) => IO[(S, A)])(implicit timeout: FiniteDuration): IO[A] =
    f(env, state).map {
      case (s, a) =>
        state = s
        a
    }

  def stop: Unit = ()
}

class FactorTimeout(msg: String) extends RuntimeException(msg)

object ValidRegex {
  val IpAddress = "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$".r

  val Hostname = "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$".r
}



