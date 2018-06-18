package factor.example

import scala.concurrent.duration._

import cats.implicits._

import factor._

import SausageMakingProcess._

/** A non-exhaustive sample set of simple unit tests for the "business logic" in SausageMakingProcess.
  *
  * Replaces actual remote Factors with TestFactor, a simple stateful cell.
  *
  * Main takeaway is that we can peel the tests of our system logic away from the issues around distribution
  * and concurrency. So the logic tests remain simple, fast to run and cost-efficient to build.
  * */
class SausageFactorySpec extends org.specs2.mutable.Specification {
  implicit val timeout = 1.second

  "truckPorkToMincer" ! {
    val mincer = TestFactor(
      Recipe(spiceGramsPerKilo = 40),
      MincerInventory(0, 200, 0))
    val program = truckPorkToMincer(100, mincer) >> mincer.getS
    val inv = program.unsafeRunSync()
    inv must_== MincerInventory(100, 200, 0)
  }

  "makeSausageMinceBatch" ! {
    val mincer = TestFactor(
      Recipe(spiceGramsPerKilo = 40),
      MincerInventory(200, 5000, 50))
    val program = mincer.runUpdatePure(makeSausageMinceBatch) >> mincer.getS
    val inv = program.unsafeRunSync()
    inv must_== MincerInventory(100, 1000, 150)
  }

  "truckMinceToStuffer is no-op when load too small" ! {
    val initMincerInv = MincerInventory(200, 5000, 19)
    val initStufferInv = SausageStufferInventory(0, 0)
    val mincer = TestFactor(
      Recipe(spiceGramsPerKilo = 40),
      initMincerInv)
    val stuffer = TestFactor(
      SausageThickness(28),
      initStufferInv)
    val program = truckMinceToStuffer(mincer, stuffer) >> (mincer.getS, stuffer.getS).tupled
    val (mincerInv, stufferInv) = program.unsafeRunSync()
    mincerInv must_== initMincerInv
    stufferInv must_== initStufferInv
  }

}
