package factor
package integrationtest

import java.nio.file._

import example._
import cats.implicits._
import cats.effect._

import scala.concurrent.duration._

/* A 3-JVM integration test based on the Sausage Factory example. This tests out the Akka integration including
Remote, Serialisation and shutdown aspects.

To run from SBT: integrationTest/multi-jvm:run factor.integrationtest.SausageFactory

See https://doc.akka.io/docs/akka/2.5/multi-jvm-testing.html
*/

object SausageFactoryMultiJvmMincerNode {
  val address = SystemAddress("SausageMincer", "127.0.0.1", 3556)

  def main(args: Array[String]): Unit = new FactorSystem(address,
    akkaConfigOverrides = "akka.loglevel = WARNING")
}
object SausageFactoryMultiJvmStufferNode {
  val address = SystemAddress("SausageStuffer", "127.0.0.1", 3557)

  def main(args: Array[String]): Unit = new FactorSystem(address,
    akkaConfigOverrides = "akka.loglevel = WARNING")
}

object SausageFactoryMultiJvmControllerNode {
  import scala.concurrent.ExecutionContext.Implicits.global
  import SausageMakingProcess._

  def main(args: Array[String]): Unit = {
    implicit val timeout = 5.second

    val system = new FactorSystem(
      SystemAddress("Controller", "127.0.0.1", 3558),
      akkaConfigOverrides = "akka.loglevel = WARNING")

    val mincer = createMincer(system, SausageFactoryMultiJvmMincerNode.address)
    val sausageStuffer = createStuffer(system, SausageFactoryMultiJvmStufferNode.address)

    val commands = List(
      topupMincerSpiceVats(mincer),
      truckPorkToMincer(1000, mincer),
      mincer.runUpdatePure(makeSausageMinceBatch),
      truckMinceToStuffer(mincer, sausageStuffer),
      mincer.runUpdatePure(makeSausageMinceBatch),
      system.runAsyncUnit(() => stuffSausagesAndAuditLog(sausageStuffer), SausageFactoryMultiJvmStufferNode.address),
      statusUpdate(mincer, sausageStuffer).map(println),
    )

    val assertions = List(
      mincer.getS.map(assertEquals(_, MincerInventory(800, 2000, 100))),
      sausageStuffer.getS.map(assertEquals(_, SausageStufferInventory(50, 500))),
      IO(assertEquals(Files.exists(Paths.get(auditLogfilePath)), true, s"No audit file at: $auditLogfilePath")),
    )

    val cleanup = List[IO[Unit]](
      system.terminateRemoteSystem(SausageFactoryMultiJvmMincerNode.address),
      system.terminateRemoteSystem(SausageFactoryMultiJvmStufferNode.address),
      system.terminate,
      IO(Files.deleteIfExists(Paths.get(auditLogfilePath))),
    )

    val program = (commands ++ assertions).sequence.guarantee(cleanup.sequence.void)

    program.unsafeRunSync()
  }

  def assertEquals(actual: Any, expected: Any, msg: String = "") =
    if (expected != actual)
      throw new RuntimeException(s"Assertion failed: $msg. Expected: $expected  Actual: $actual")
}
