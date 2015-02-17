/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.util.control.NoStackTrace
import akka.stream.Supervision
import akka.stream.stage.PushPullStage
import akka.stream.stage.Context
import akka.stream.stage.Directive
import akka.stream.stage.Stage
import akka.stream.stage.PushStage

object InterpreterSpec {
  class RestartTestStage extends PushPullStage[Int, Int] {
    var sum = 0
    def onPush(elem: Int, ctx: Context[Int]): Directive = {
      sum += elem
      ctx.push(sum)
    }

    override def onPull(ctx: Context[Int]): Directive = {
      ctx.pull()
    }

    override def decide(t: Throwable): Supervision.Directive = Supervision.Restart

    override def restart(): Stage[Int, Int] = {
      sum = 0
      this
    }

  }
}

class InterpreterSpec extends InterpreterSpecKit {
  import InterpreterSpec._
  import Supervision.stoppingDecider
  import Supervision.resumingDecider
  import Supervision.restartingDecider

  "Interpreter" must {

    "implement map correctly" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(2)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement chain of maps correctly" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, stoppingDecider),
      Map((x: Int) ⇒ x * 2, stoppingDecider),
      Map((x: Int) ⇒ x + 1, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(5)))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "work with only boundary ops" in new TestSetup(Seq.empty) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement one-to-many many-to-one chain correctly" in new TestSetup(Seq(
      Doubler(),
      Filter((x: Int) ⇒ x != 0))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement many-to-one one-to-many chain correctly" in new TestSetup(Seq(
      Filter((x: Int) ⇒ x != 0),
      Doubler())) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement take" in new TestSetup(Seq(Take(2))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1), Cancel, OnComplete))
    }

    "implement take inside a chain" in new TestSetup(Seq(
      Filter((x: Int) ⇒ x != 0),
      Take(2),
      Map((x: Int) ⇒ x + 1, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(Cancel, OnComplete, OnNext(3)))
    }

    "implement fold" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(3), OnComplete))
    }

    "implement fold with proper cancel" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "work if fold completes while not in a push position" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {

      lastEvents() should be(Set.empty)

      upstream.onComplete()
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnComplete, OnNext(0)))
    }

    "implement grouped" in new TestSetup(Seq(Grouped(3))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(Vector(0, 1, 2))))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(Vector(3)), OnComplete))
    }

    "implement conflate" in new TestSetup(Seq(Conflate(
      (in: Int) ⇒ in,
      (agg: Int, x: Int) ⇒ agg + x,
      stoppingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement expand" in new TestSetup(Seq(Expand(
      (in: Int) ⇒ in,
      (agg: Int) ⇒ (agg, agg)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "work with conflate-conflate" in new TestSetup(Seq(
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider),
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))

    }

    "work with expand-expand" in new TestSetup(Seq(
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg + 1)),
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg + 1)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onNext(10)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(2))) // One element is still in the pipeline

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(10)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(11)))

      upstream.onComplete()
      downstream.requestOne()
      // This is correct! If you don't believe, run the interpreter with Debug on
      lastEvents() should be(Set(OnComplete, OnNext(12)))
    }

    "implement conflate-expand" in new TestSetup(Seq(
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider),
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(2)))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement expand-conflate" in {
      pending
      // Needs to detect divergent loops
    }

    "implement doubler-conflate" in new TestSetup(Seq(
      Doubler(),
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider))) {
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(6)))

    }

    "implement expand-filter" in pending

    "implement take-conflate" in pending

    "implement conflate-take" in pending

    "implement take-expand" in pending

    "implement expand-take" in pending

    "implement take-take" in pending

    "implement take-drop" in pending

    "implement drop-take" in pending

    val TE = new Exception("TEST") with NoStackTrace {
      override def toString = "TE"
    }

    "handle external failure" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      upstream.onError(TE)
      lastEvents() should be(Set(OnError(TE)))

    }

    "emit failure when op throws" in new TestSetup(Seq(Map((x: Int) ⇒ if (x == 0) throw TE else x, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "resume when op throws" in new TestSetup(Seq(Map((x: Int) ⇒ if (x == 0) throw TE else x, resumingDecider))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(3)))
    }

    "emit failure when op throws in middle of the chain" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, stoppingDecider),
      Map((x: Int) ⇒ if (x == 0) throw TE else x + 10, stoppingDecider),
      Map((x: Int) ⇒ x + 100, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "resume when op throws in middle of the chain" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x == 0) throw TE else x + 10, resumingDecider),
      Map((x: Int) ⇒ x + 100, resumingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(114)))
    }

    "resume when op throws before grouped" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x <= 0) throw TE else x + 10, resumingDecider),
      Grouped(3))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(Vector(13, 14, 15))))
    }

    "complete after resume when op throws before grouped" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x <= 0) throw TE else x + 10, resumingDecider),
      Grouped(1000))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(Vector(13, 14)), OnComplete))
    }

    "restart when onPush throws" in {
      val stage = new RestartTestStage {
        override def onPush(elem: Int, ctx: Context[Int]): Directive = {
          if (elem <= 0) throw TE
          else super.onPush(elem, ctx)
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        lastEvents() should be(Set.empty)

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-1) // boom
        lastEvents() should be(Set(RequestOne))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(104)))
      }
    }

    "restart when onPush throws after ctx.push" in {
      val stage = new RestartTestStage {
        override def onPush(elem: Int, ctx: Context[Int]): Directive = {
          val ret = ctx.push(sum)
          super.onPush(elem, ctx)
          if (elem <= 0) throw TE
          ret
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        lastEvents() should be(Set.empty)

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-1) // boom
        lastEvents() should be(Set(RequestOne))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(104)))
      }
    }

    "restart when onPull throws" in {
      val stage = new RestartTestStage {
        override def onPull(ctx: Context[Int]): Directive = {
          if (sum < 0) throw TE
          super.onPull(ctx)
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        lastEvents() should be(Set.empty)

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-5) // this will trigger failure of next requestOne (pull)
        lastEvents() should be(Set(OnNext(99)))

        downstream.requestOne() // this failed, but resume will pull
        lastEvents() should be(Set(RequestOne))
        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(104)))
      }
    }

    "resume failing onPull" in {
      val stage = new PushPullStage[Int, Int] {
        var n = 0
        var lastElem = 0
        def onPush(elem: Int, ctx: Context[Int]): Directive = {
          n = 3
          lastElem = elem
          ctx.push(elem)
        }

        override def onPull(ctx: Context[Int]): Directive = {
          if (n == 0)
            ctx.pull()
          else {
            n -= 1
            if (n == 1) throw TE
            ctx.push(lastElem)
          }
        }

        override def decide(t: Throwable): Supervision.Directive = Supervision.Resume
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        lastEvents() should be(Set.empty)

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
      }
    }

    "restart when conflate `seed` throws" in new TestSetup(Seq(Conflate(
      (seed: Int) ⇒ if (seed == 1) throw TE else seed,
      (agg: Int, x: Int) ⇒ agg + x,
      restartingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(10)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(12))) // note that 1 has been discarded

      downstream.requestOne()
      lastEvents() should be(Set.empty)
    }

    "restart when conflate `aggregate` throws" in new TestSetup(Seq(Conflate(
      (seed: Int) ⇒ seed,
      (agg: Int, x: Int) ⇒ if (x == 2) throw TE else agg + x,
      restartingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(10)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(10))) // note that 1 and 2 has been discarded

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "work with keep-going ops" in pending

  }

}