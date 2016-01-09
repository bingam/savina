package edu.rice.habanero.experimental

import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import edu.rice.habanero.experimental.Master.Start


object App {

  def main(args: Array[String]) {
    if(args.isEmpty){
      startMaster(3301, "master")
      startWorker(3303, "worker")
      startWorker(3304, "worker")
      startWorker(3305, "worker")
      startWorker(3306, "worker")
    }
  }

  def startMaster(port: Int, role: String): Unit = {
    val config = ConfigFactory.parseString(s"akka.cluster.roles=[$role]").
      withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port" )).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("SavinaSystem", config)

    system.actorOf(MonitoringActor.props)
    val master = system.actorOf(Master.props, "master")

    Cluster(system) registerOnMemberUp {
      master ! Start
    }
  }

  def startWorker(port: Int, role: String):Unit = {
    val config = ConfigFactory.parseString(s"akka.cluster.roles=[$role]").
      withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("SavinaSystem", config)

    system.actorOf(Worker.props, "worker")
  }
}

