/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.remote.AddressUidExtension
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object RestartNode2SpecMultiJvmSpec extends MultiNodeConfig {
  val seed1 = role("seed1")
  val seed2 = role("seed2")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster.auto-down-unreachable-after = 2s
      akka.cluster.retry-unsuccessful-join-after = 3s
      akka.remote.retry-gate-closed-for = 45s # setting this 5s will make the test pass
      akka.remote.log-remote-lifecycle-events = INFO
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  class KeepAlive extends Actor {
    context.system.scheduler.schedule(1.second, 100.millis, self, "tick")(context.dispatcher)
    Cluster(context.system).subscribe(self, InitialStateAsEvents, classOf[MemberUp])
    var nodes = Set.empty[Address]

    def receive = {
      case ClusterEvent.MemberUp(m) ⇒
        nodes += m.address
      case "tick" ⇒
        nodes.foreach { node ⇒
          context.actorSelection(self.path.toStringWithAddress(node)) ! "ping"
        }
      case "ping" ⇒
    }
  }
}

class RestartNode2SpecMultiJvmNode1 extends RestartNode2SpecSpec
class RestartNode2SpecMultiJvmNode2 extends RestartNode2SpecSpec

abstract class RestartNode2SpecSpec
  extends MultiNodeSpec(RestartNode2SpecMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {

  import RestartNode2SpecMultiJvmSpec._

  @volatile var seedNode1Address: Address = _

  // use a separate ActorSystem, to be able to simulate restart
  lazy val seed1System = ActorSystem(system.name, system.settings.config)

  def seedNodes: immutable.IndexedSeq[Address] = Vector(seedNode1Address, seed2)

  lazy val restartedSeed1System = ActorSystem(system.name,
    ConfigFactory.parseString("akka.remote.netty.tcp.port=" + seedNodes.head.port.get).
      withFallback(system.settings.config))

  override def afterAll(): Unit = {
    runOn(seed1) {
      shutdown(
        if (seed1System.whenTerminated.isCompleted) restartedSeed1System else seed1System)
    }
    super.afterAll()
  }

  "Cluster seed nodes" must {
    "be able to restart first seed node and join other seed nodes" taggedAs LongRunningTest in within(60.seconds) {
      // FIXME I was thinking if sending messages to the shutdown address would influence rejection of new uid
      //system.actorOf(Props[KeepAlive], "keepAlive")

      // seed1System is a separate ActorSystem, to be able to simulate restart
      // we must transfer its address to seed2
      runOn(seed2) {
        system.actorOf(Props(new Actor {
          def receive = {
            case a: Address ⇒
              seedNode1Address = a
              sender() ! "ok"
          }
        }).withDeploy(Deploy.local), name = "address-receiver")
        enterBarrier("seed1-address-receiver-ready")
      }

      runOn(seed1) {
        enterBarrier("seed1-address-receiver-ready")
        seedNode1Address = Cluster(seed1System).selfAddress
        List(seed2) foreach { r ⇒
          system.actorSelection(RootActorPath(r) / "user" / "address-receiver") ! seedNode1Address
          expectMsg(5.seconds, "ok")
        }
      }
      enterBarrier("seed1-address-transfered")

      // now we can join seed1System, seed2 together

      runOn(seed1) {
        Cluster(seed1System).joinSeedNodes(seedNodes)
        awaitAssert(Cluster(seed1System).readView.members.size should be(2))
        awaitAssert(Cluster(seed1System).readView.members.map(_.status) should be(Set(Up)))
      }
      runOn(seed2) {
        cluster.joinSeedNodes(seedNodes)
        awaitMembersUp(2)
      }
      enterBarrier("started")

      // shutdown seed1System
      runOn(seed1) {
        shutdown(seed1System, remainingOrDefault)
      }
      enterBarrier("seed1-shutdown")

      // FIXME I was thinking if member removal -> quarantine on the other side would influence rejection of new uid
      //Thread.sleep(15000)

      // then start restartedSeed1System, which has the same address as seed1System
      runOn(seed1) {
        println(s"# RESTART ${AddressUidExtension(seed1System).addressUid} -> ${AddressUidExtension(restartedSeed1System).addressUid}") // FIXME
        Cluster(restartedSeed1System).joinSeedNodes(seedNodes)
        within(30.seconds) {
          awaitAssert(Cluster(restartedSeed1System).readView.members.size should be(2))
          awaitAssert(Cluster(restartedSeed1System).readView.members.map(_.status) should be(Set(Up)))
        }
      }
      runOn(seed2) {
        awaitMembersUp(2)
      }
      enterBarrier("seed1-restarted")

    }

  }
}