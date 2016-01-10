package edu.rice.habanero.benchmarks.piprecision

import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import com.typesafe.config.ConfigFactory
import edu.rice.habanero.actors.{AkkaActorState, AkkaActor}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}
import edu.rice.habanero.benchmarks.piprecision.PiPrecisionConfig.{StartMessage, StopMessage}

object PiPrecisionAkkaClusterActorBenchmark {

  def main(args: Array[String]) {
    startMaster(3301, "master")
    startWorker(3303, "worker")
    startWorker(3304, "worker")
    startWorker(3305, "worker")
    startWorker(3306, "worker")
  }

  def startMaster(port: Int, role: String): Unit = {
    val config = ConfigFactory.parseString(s"akka.cluster.roles=[$role]").
      withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")).
      withFallback(ConfigFactory.load())

    val numWorkers: Int = 4
    val precision: Int = 1000

    val system = ActorSystem("SavinaSystem", config)

    system.actorOf(MonitoringActor.props)
    val master = system.actorOf(Master.props(numWorkers, precision), "master")

    AkkaActorState.startActor(master)

    Cluster(system) registerOnMemberUp {
      AkkaActorState.awaitTermination(system)
    }
  }

  def startWorker(port: Int, role: String): Unit = {
    val config = ConfigFactory.parseString(s"akka.cluster.roles=[$role]").
      withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("SavinaSystem", config)

    system.actorOf(Worker.props, "worker")
  }
}
class PiPrecisionAkkaClusterActorBenchmark {

  protected final class PiPrecisionAkkaClusterActorBenchmark extends Benchmark {

    def main(args: Array[String]) {
      BenchmarkRunner.runBenchmark(args, new PiPrecisionAkkaClusterActorBenchmark)
    }

    def initialize(args: Array[String]) {
      PiPrecisionConfig.parseArgs(args)
    }

    def printArgInfo() {
      PiPrecisionConfig.printArgs()
    }

    def runIteration() {
      //      val numWorkers: Int = PiPrecisionConfig.NUM_WORKERS
      val numWorkers: Int = 4
      val precision: Int = 1000

      val system = AkkaActorState.newActorSystem("SavinaSystem")

      val master = system.actorOf(Master.props(numWorkers, precision))
      AkkaActorState.startActor(master)

      Cluster(system) registerOnMemberUp {
        AkkaActorState.awaitTermination(system)
      }
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) {
    }
  }
}


object Master {
  protected class Master(numWorkers: Int, scale: Int) extends AkkaActor[AnyRef] {

    private final var workers = IndexedSeq.empty[ActorRef]
    //Array.tabulate[ActorRef](numWorkers)(i => context.system.actorOf(Props(new Worker(self, i))))
    private var result: BigDecimal = BigDecimal.ZERO
    private final val tolerance = BigDecimal.ONE.movePointLeft(scale)
    private final val numWorkersTerminated: AtomicInteger = new AtomicInteger(0)
    private var numTermsRequested: Int = 0
    private var numTermsReceived: Int = 0
    private var stopRequests: Boolean = false

    /**
      * Generates work for the given worker
      *
      * @param workerId the id of te worker to send work
      */
    private def generateWork(workerId: Int) {
      val wm: PiPrecisionConfig.WorkMessage = new PiPrecisionConfig.WorkMessage(scale, numTermsRequested, workerId)
      workers(workerId) ! wm
      numTermsRequested += 1
    }

    def requestWorkersToExit() {
      workers.foreach(loopWorker => {
        loopWorker ! StopMessage.ONLY
      })
    }
    override def process(msg: AnyRef) {
      msg match {
        case Worker.Register if !workers.contains(sender()) =>
          context watch sender()
          workers = workers :+ sender()
          if (workers.size == numWorkers) self ! StartMessage.ONLY
        case Terminated(a) =>
          workers = workers.filterNot(_ == a)
        case rm: PiPrecisionConfig.ResultMessage =>
          numTermsReceived += 1
          result = result.add(rm.result)
          if (rm.result.compareTo(tolerance) <= 0) {
            stopRequests = true
          }
          if (!stopRequests) {
            generateWork(rm.workerId)
          }
          if (numTermsReceived == numTermsRequested) {
            requestWorkersToExit()
          }
        case _: PiPrecisionConfig.StopMessage =>
          val numTerminated: Int = numWorkersTerminated.incrementAndGet
          if (numTerminated == numWorkers) {
            exit()
          }
        case _: PiPrecisionConfig.StartMessage =>
          var t: Int = 0
          while (t < Math.min(scale, 10 * numWorkers)) {
            generateWork(t % numWorkers)
            t += 1
          }
        case message =>
          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }

    def getResult: String = {
      result.toPlainString
    }
  }

  case object Start

  def props(numWorkers: Int, scale: Int) = Props(new Master(numWorkers, scale))
}

object Worker {
  protected class Worker extends AkkaActor[AnyRef] {
    val cluster = Cluster(context.system)

    override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp])

    override def postStop(): Unit = {
      cluster.unsubscribe(self)
      context.system.shutdown()
    }

    override def process(msg: AnyRef) = {
      msg match {
        case state: CurrentClusterState =>
          state.members.filter(_.status == MemberStatus.Up) foreach register
        case MemberUp(m) => register(m)
        case _: PiPrecisionConfig.StopMessage =>
          sender() ! new PiPrecisionConfig.StopMessage
          exit()
        case wm: PiPrecisionConfig.WorkMessage =>
          val result: BigDecimal = PiPrecisionConfig.calculateBbpTerm(wm.scale, wm.term)
          sender() ! new PiPrecisionConfig.ResultMessage(result, wm.id)
        case message =>
          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }

    def register(member: Member): Unit =
      if (member.hasRole("master")) context.actorSelection(RootActorPath(member.address) / "user" / "master") ! Register
  }

  case object Register extends Serializable

  val props = Props(classOf[Worker])
}

object MonitoringActor {

  class MonitoringActor extends Actor with ActorLogging {

    val cluster = Cluster(context.system)

    def receive = {
      case state: CurrentClusterState => log.info(s"Current state: $state")
      case MemberUp(member) => log.info(s"Member is up: $member, roles: ${member.roles}")
      case MemberRemoved(member, previousState) => log.info(s"Member removed: $member, roles: ${member.roles}")
      case MemberExited(member) => log.info(s"Member exited: $member, roles: ${member.roles}")
      case UnreachableMember(member) => log.info(s"Member unreachable: $member, roles: ${member.roles}")
      case LeaderChanged(address) => log.info(s"Leader changed: $address")
      case RoleLeaderChanged(role, member) => log.info(s"Role $role leader changed: $member")
      case e: ClusterDomainEvent => //log.info("???: {}", e)
    }

    // subscribe to cluster changes, resubscribe when restarted
    override def preStart(): Unit = {
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
    }
  }
  val props = Props(classOf[MonitoringActor])
}