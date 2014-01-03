package examples


object ScalingAkkaStyle {
  // this is scala + Akka
  import akka.actor._

  val system = ActorSystem("SimpleSystem")
  def scaling(downstream: ActorRef) = system.actorOf(Props(new Scaling(downstream)))

  case class ScaleFactor(factor: Double)
  case class Measurement(value: Double)
  case class ScaledMeasurement(value: Double)

  trait State
  case class Init( deferred: Seq[Measurement]) extends State
  case class Loop(factor: Double) extends State

  class Scaling(downstream: ActorRef) extends Actor {
    var state: State = Init(Seq())

    def receive = { 
      case ScaleFactor(newFactor) =>
        state match {

          case Init(deferred) =>
            for(Measurement(value) <- deferred)
              scaleAndSend(newFactor, value)
            state = Loop(newFactor)

          case Loop(_) =>
            state = Loop(newFactor)
        }

      case m @ Measurement(value) =>
        state match {

          case Init(deferred) =>
            state = Init( deferred :+ m)

          case Loop(factor) =>
            scaleAndSend(factor, value)
        }

      case _ =>  
    }
    
    def scaleAndSend(factor: Double, value: Double) = 
      downstream ! ScaledMeasurement(factor * value)
  }
}

object ScalingAkkaBecomesStyle {
  // this is scala + Akka
  import akka.actor._

  val system = ActorSystem("SimpleSystem")
  def scaling(downstream: ActorRef) = system.actorOf(Props(new Scaling(downstream)))

  case class ScaleFactor(factor: Double)
  case class Measurement(value: Double)
  case class ScaledMeasurement(value: Double)

  class Scaling(downstream: ActorRef) extends Actor with Stash {
    def receive = {
      case ScaleFactor(f) =>
        context.become(loop(f))
        unstashAll()

      case _: Measurement =>
        stash()
    }

    def loop(f: Double): Receive = {
      case ScaleFactor(fNext) => 
        context.become(loop(fNext))

      case Measurement(m) => 
        downstream ! ScaledMeasurement(m * f)
    }
  }
}


object ScalingOriginalStyle {

  // this is scala + original actors
  import actors.Actor
  import Actor._

  case class ScaleFactor(factor: Double)
  case class Measurement(value: Double)
  case class ScaledMeasurement(value: Double)

  def start(downstream: Actor) = actor {
    receive {
      case ScaleFactor(factor) => loop(downstream, factor)
    }
  }

  def loop( downstream: Actor, factor: Double ): Nothing = {
    receive {
      case Measurement(value) =>
        downstream ! ScaledMeasurement(value * factor)
        loop(downstream, factor)

      case ScaleFactor(newFactor) =>
        loop(downstream, newFactor)

      case _ =>
        loop(downstream, factor)
    }
  }
}
