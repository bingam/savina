package edu.rice.habanero.experimental

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import edu.rice.habanero.experimental.Worker.WorkerRegistration

object Worker {

  class Worker extends Actor {

    val cluster = Cluster(context.system)

    override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp])
    override def postStop(): Unit = cluster.unsubscribe(self)

    def receive = {
      case state: CurrentClusterState =>
        state.members.filter(_.status == MemberStatus.Up) foreach register
      case MemberUp(m) => register(m)
    }

    def register(member: Member): Unit =
      if (member.hasRole("master")) context.actorSelection(RootActorPath(member.address) / "user" / "master") ! WorkerRegistration
  }

  case object WorkerRegistration

  val props = Props(classOf[Worker])
}

object Master {

  class Master extends Actor {
    var workers = IndexedSeq.empty[ActorRef]

    def receive = {
      case Start =>
        println("READY TO PARTY")
      case WorkerRegistration if !workers.contains(sender()) =>
        context watch sender()
        workers = workers :+ sender()
      case Terminated(a) =>
        workers = workers.filterNot(_ == a)
    }
  }

  case object Start

  val props = Props(classOf[Master])
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