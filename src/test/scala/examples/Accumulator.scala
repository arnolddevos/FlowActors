package examples

object AkkaAccumulator {
  // this is scala + Akka
  import akka.actor._

  val system = ActorSystem("SimpleSystem")
  val accumulator = system.actorOf(Props[Accumulator])

  case class Increment(amount: Int)
  case class Accumulation(amount: Int)

  class Accumulator(results: ActorRef) extends Actor {
    var value = 0

    results ! Accumulation(value)
    def receive = {
      case Increment(amount) => 
        value += amount
        results ! Accumulation(value)
    }
  }
}

object Accumulator {

  // this is scala + FlowLib
  import au.com.langdale.async.Flow._

  val accumulator = process(loop(0))

  val increment, accumulation = label[Int]

  def loop(value: Int): Action[Nothing] =
    output(accumulation, value) {
      input(increment) { 
        amount => loop(value + amount) 
      }
    }
}
