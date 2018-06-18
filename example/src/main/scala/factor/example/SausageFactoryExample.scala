package factor
package example

import java.io._
import java.nio.file._
import java.time.LocalTime

import factor._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._
import mouse.all._
import monocle.macros.syntax.lens._

import scala.concurrent.duration._
import scala.util._

//This example models a sausage factory using two Factors and a orchestrating client

//Case classes modelling the state and configuration of the two Factors in the example
case class Recipe(spiceGramsPerKilo: Int)

case class MincerInventory(availPorkKgs: Int, availableSpicesGrams: Int, minceKgs: Int)

case class SausageThickness(mm: Int)

case class SausageStufferInventory(minceKgs: Int, sausageCount: Int)

//JVM running the Mincer
object SausageMincer {
  val address = SystemAddress("SausageMincer", "127.0.0.1", 3456)

  def main(args: Array[String]): Unit = new FactorSystem(address)
}

//JVM running the Sausage Stuffer
object SausageStuffer {
  val address = SystemAddress("SausageStuffer", "127.0.0.1", 3457)

  def main(args: Array[String]): Unit = new FactorSystem(address)
}

//JVM running the client controller
object SausageFactoryController {
  import SausageMakingProcess._

  val port = 3458

  def main(args: Array[String]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = 5.second

    val controller = new FactorSystem(SystemAddress("controller", "127.0.0.1", port))

    val mincer = createMincer(controller, SausageMincer.address)
    val sausageStuffer = createStuffer(controller, SausageStuffer.address)

    val initialCommands = List(topupMincerSpiceVats(mincer), truckPorkToMincer(1000, mincer))

    val commandsWithStatusChecks = generateRandomCommands(100, controller, mincer, sausageStuffer).grouped(20).toList
      .intercalate(List(statusUpdate(mincer, sausageStuffer).map(println)))

    val allCommands = (initialCommands ++ commandsWithStatusChecks).sequence
    val program = allCommands.guarantee(
      controller.terminateRemoteSystem(SausageStuffer.address) >>
      controller.terminateRemoteSystem(SausageMincer.address) >>
      controller.terminate
    )

    program.unsafeRunSync()
  }

  //randomly generates a collection of commands drawn from a weighted distribution
  def generateRandomCommands(n: Int, sys: FactorSystem, mincer: MincerRef, sausageStuffer: SausageStufferRef)(implicit timeout: FiniteDuration): List[IO[Unit]] = {
    val commandsAndProportions = Map(
      truckPorkToMincer(1000 + Random.nextInt(1000), mincer) -> 0.1,
      topupMincerSpiceVats(mincer) -> 0.1,
      mincer.runUpdatePure(makeSausageMinceBatch) -> 0.35,
      sys.runAsyncUnit(() => truckMinceToStuffer(mincer, sausageStuffer), SausageStuffer.address) -> 0.1,
      sys.runAsyncUnit(() => stuffSausagesAndAuditLog(sausageStuffer), SausageStuffer.address) -> 0.35
    )
    val allCommands: List[IO[Unit]] = commandsAndProportions.flatMap {case (c, p) =>
      val count = (n * p).toInt
      List.fill(count)(c)
    }.toList
    Random.shuffle(allCommands)
  }
}

//all the business logic of the sausage factory is declared as operations in this object
object SausageMakingProcess {

  type MincerRef = FactorRef[Recipe, MincerInventory]
  type SausageStufferRef = FactorRef[SausageThickness, SausageStufferInventory]

  def createMincer(sys: FactorSystem, address: SystemAddress)(implicit timeout: FiniteDuration) =
    sys.createFactor[Recipe, MincerInventory](
      Recipe(spiceGramsPerKilo = 40),
      initialState = MincerInventory(0, 0, 0),
      "mincer",
      address
    )

  def createStuffer(sys: FactorSystem, address: SystemAddress)(implicit timeout: FiniteDuration) =
    sys.createFactor[SausageThickness, SausageStufferInventory](
      SausageThickness(mm = 24),
      initialState = SausageStufferInventory(0, 0),
      "sausageStuffer",
      address
    )


  def truckPorkToMincer(porkKgs: Int, mincer: MincerRef)(implicit timeout: FiniteDuration) =
    mincer.runSUpdatePure(_.lens(_.availPorkKgs).modify(_ + porkKgs))


  def topupMincerSpiceVats(mincer: MincerRef)(implicit timeout: FiniteDuration) =
    mincer.runSUpdatePure(_.lens(_.availableSpicesGrams).set(10000))

  def makeSausageMinceBatch(recipe: Recipe, inventory: MincerInventory)(implicit timeout: FiniteDuration): MincerInventory = {
    val MaxBatch = 100
    val batchSize = MaxBatch min inventory.availPorkKgs min (inventory.availableSpicesGrams * recipe.spiceGramsPerKilo)
    inventory
      .lens(_.availPorkKgs).modify(_ - batchSize)
      .lens(_.availableSpicesGrams).modify(_ - batchSize * recipe.spiceGramsPerKilo)
      .lens(_.minceKgs).modify(_ + batchSize)
  }

  def truckMinceToStuffer(mincer: MincerRef, sausageStuffer: SausageStufferRef)(implicit timeout: FiniteDuration): IO[Option[Int]] = {
    val MinimumViableLoad = 20
    val MaximumLoad = 2000
    for {
      qtyInTruck <- mincer.runSPure {case inv@MincerInventory(_, _, minceKgs) =>
        if (minceKgs >= MinimumViableLoad) {
          val qty = minceKgs min MaximumLoad
          (inv.lens(_.minceKgs).modify(_ - qty), Some(qty))
        } else
          (inv, Option.empty)
      }
      _ <- qtyInTruck match {
        case Some(qty) => sausageStuffer.runSUpdatePure(_.lens(_.minceKgs).modify(_ + qty))
        case None => IO.unit
      }
    } yield qtyInTruck
  }

  def stuffSausages(thickness: SausageThickness, inventory: SausageStufferInventory)(implicit timeout: FiniteDuration): (SausageStufferInventory, Int) = {
    val MaxBatch = 50
    val sausagePerKg = 6000 / thickness.mm.squared
    val minceConsumed = MaxBatch min inventory.minceKgs
    val sausagesProduced = minceConsumed * sausagePerKg
    (inventory
      .lens(_.minceKgs).modify(_ - minceConsumed)
      .lens(_.sausageCount).modify(_ + sausagesProduced),
      sausagesProduced)
  }

  def auditLogfilePath = s"${System.getProperty("java.io.tmpdir")}${File.separator}SausageFactoryExampleAudit.log"

  def stuffSausagesAndAuditLog(sausageStuffer: SausageStufferRef)(implicit timeout: FiniteDuration) =
    fileWriterResource(auditLogfilePath).use(fw =>
      for {
        qty <- sausageStuffer.runPure(stuffSausages)
        time <- IO(LocalTime.now())
        _ <- IO(fw.write(s"Manufactured $qty sausages at $time.\n"))
      } yield (())
    )

  def fileWriterResource(path: String) =
    Resource.make(IO(new FileWriter(path, true)))(fw => IO(fw.close()))

  def statusUpdate(mincer: MincerRef, sausageStuffer: SausageStufferRef)(implicit timeout: FiniteDuration) = for {
    mincerInventory <- mincer.getS
    sausageStufferInventory <- sausageStuffer.getS
  } yield (
    s"""Inventory of the sausage manufacturing supply chain:
       |Mincer plant has ${mincerInventory.availPorkKgs} kgs of pork ${mincerInventory.availableSpicesGrams} gs of spices available, and ${mincerInventory.minceKgs} kgs of sausage mince awaiting shipment.
       |Sausage Stuffer has ${sausageStufferInventory.minceKgs} kgs of mince and ${sausageStufferInventory.sausageCount} sausages ready for shipping.
     """.stripMargin)
}

