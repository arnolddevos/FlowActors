package au.com.langdale
package async

import scala.util.control.NonFatal
import java.util.{Timer, TimerTask}

trait FlowImpl extends Flow with Primitives with Queueing { this: Trace with Executor =>

  def createSite = new Site

  case class Activation(process: Process, stack: List[Any=>Action[_]])  

  final class Site extends SiteOps with PrimitiveActor { site =>

    private[FlowImpl] val inputs = new StateMap with Queueing {
      type Key[_] = InputPort[_]
    }

    private[FlowImpl] val outputs = new StateMap with Wiring {
      type Key[_] = (OutputPort[_], Int)
    }

    /** create a connection to this site */
    private[FlowImpl] def connection[Message](label: InputPort[Message]): Connection[Message] = 
      new Connection[Message] {
        def send(mk: MK[Message])(implicit t: Task): Unit =  enqueue { implicit t =>
          inputs(label) { inputs.transition(mk) }
        }
      }

    private[FlowImpl] def runStep( activation: Activation, step: => Action[_] )(implicit t: Task) = spawn { implicit t => 
      try { 
        trace(site, "=>", "Step")
        val a = step 
        enqueue(implicit t => a.dispatch(this, activation))
      }
      catch { 
        case NonFatal(e) => 
          trace("error", site, activation.process, "uncaught exception", e)
          outputs(errors, 0) { outputs.transition(implicit t => Result(this, activation.process, Left(e)))}
      }
    } 

    /** inject an action into the site */
    def run(process: Process, instances: Int) = enqueue { implicit t => 
      trace(site, "=>", "Run")
      val activation = Activation(process, Nil)
      for( i <- 1 to instances) runStep( activation, process.action )
    } (externalTask)

    /** change buffering depth */  
    def buffer[Message]( label: InputPort[Message], depth: Int): Unit = enqueue { implicit t =>
      inputs(label) { inputs.transition(depth) }
    } (externalTask)

    /** Connect an output port to an input port */
    def connect[Message]( labelA: OutputPort[Message], siteB: Site, labelB: InputPort[Message], n: Int): Unit = {
      enqueue { implicit t =>
        outputs(labelA, n) { outputs.transition( siteB.connection(labelB)) }
      } (externalTask)
    }

    /** disconnect an output */
    def disconnect[Message](label: OutputPort[Message], n: Int): Unit = enqueue { implicit t =>
      outputs(label, n) { outputs.transition[Message]() }
    } (externalTask)

    def fanout[Message](label: OutputPort[Message]): Int = outputs.keys.filter( _._1 == label ).size
  }

  /** Create an input action */
  def input[Message, U]( label: InputPort[Message])( step: Message => Action[U] ): InputAction[U] = 
    new SingleInputAction(label)(step)

  /** Create an output action */
  def output[Message, U]( label: OutputPort[Message], m: Message, n: Int)( step: => Action[U] ): Action[U] = 
    new OutputAction(label, n: Int, m)(step)

  /** Perform actions one after the other */
  def sequence[U,V](step1: => Action[V])(step2: V => Action[U]): Action[U] =
    new Sequence(step1, step2)

  /** create an action that depends on a port's fanout */
  def control[U](step: (Site, Process) => Action[U]): Action[U] = 
    new Control(step)

  /** Continue after a delay or timeout an input operation. */
  def after[U](millis: Long)(step: => Action[U]): InputAction[U] = 
    new TimerAction(millis)(step)

  /** create a stop action */
  def stop[U](u: U): Action[U] = Stop(u)

  sealed trait Action[+U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task): Unit
  }
  
  sealed trait InputAction[+U] extends Action[U] with ActionCombinator[U] {
    def orElse[V >: U]( other: InputAction[V] ): InputAction[V] = new Alternatives[V](this, other)
  }

  private class OutputAction[Message, U]( label: OutputPort[Message], n: Int, m: Message)( step: => Action[U] ) extends Action[U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task) = { import site._
      outputs(label, n) { outputs.transition(implicit t => { runStep(activation, step); m })}
    }
  }

  private trait BasicInputAction[U] extends InputAction[U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task) = dispatchLater(site, activation, Cancellation { t => () })
    def dispatchLater(site: Site, activation: Activation, cancel: Cancellation)(implicit t: Task): Cancellation
    def dispatchNow(site: Site, activation: Activation)(implicit t: Task): Boolean
  }

  case class Cancellation( f: Task => Unit ) {
    def run( implicit t:Task) = f(t)
  }

  private class SingleInputAction[Message, U]( label: InputPort[Message])( step: Message => Action[U] ) extends BasicInputAction[U] {
      
    def dispatchLater(site: Site, activation: Activation, cancel: Cancellation)(implicit t: Task) = { import site._
      val id = new CancelRef
      inputs(label) { 
        inputs.transition[Message](id){ implicit t => m =>
          cancel.run; runStep( activation, step(m))
        }
      }
      Cancellation { implicit t => inputs(label) { inputs.cancel(id) }}
    }

    def dispatchNow(site: Site, activation: Activation)(implicit t: Task): Boolean = { import site._
      inputs.run(label) { 
        inputs.transitionMaybe[Message]{ implicit t => m =>
          runStep( activation, step(m))
        }
      }
    }
  }

  private object timer extends Timer {
    def after( delay: Long)( effect: => Unit) = {
      val event = new TimerTask { def run = effect }
      schedule(event, delay)
      event
    }
  }

  private class TimerAction[U](delay: Long)(step: => Action[U]) extends BasicInputAction[U] {
    def dispatchLater(site: Site, activation: Activation, cancel: Cancellation)(implicit t: Task) = {
      @volatile var cancelled = false
      val event = timer.after(delay) { site.enqueue { implicit t => if(!cancelled) { cancel.run; site.runStep( activation, step)}}}
      Cancellation { t => cancelled = true; event.cancel }
    }
    def dispatchNow(site: Site, activation: Activation)(implicit t: Task) = false
  }
  
  private case class Alternatives[U](a1: InputAction[U], a2: InputAction[U]) extends InputAction[U] {
    
    final def toList: List[BasicInputAction[U]] = {
      def gather(a: InputAction[U]): List[BasicInputAction[U]] = a match {
        case a: BasicInputAction[U] => List(a)
        case Alternatives(a1, a2) => gather(a2) ::: gather(a1)
      }
      gather(this)
    }
    
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task): Unit = {
      val actions = toList
      val done = actions.exists(_.dispatchNow(site, activation))
      if( ! done ) {
        var cs: List[Cancellation] = List(Cancellation( t => sys.error("dispatch called outside enqueue")))
        def cancelAll = Cancellation { implicit t => for( c <- cs ) { c.run }}
        cs = actions.map( _.dispatchLater(site, activation, cancelAll))
      }
    }
  }

  private class Control[U]( step: (Site, Process) => Action[U] ) extends Action[U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task) = { 
      site.runStep(activation, step(site, activation.process))
    }
  }

  private class Sequence[U,V]( step1: => Action[V], step2: V => Action[U]) extends Action[U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task) = { 
      site.runStep( Activation( activation.process, (step2.asInstanceOf[Any=>Action[_]]) :: activation.stack), step1)
    }
  }
  
  private case class Stop[U](u: U) extends Action[U] {
    private[FlowImpl] def dispatch(site: Site, activation: Activation)(implicit t: Task) { import site._
      activation match {
        case Activation( process, next :: stack) => 
          runStep( Activation(process, stack), next(u))
        case Activation( process, Nil) => 
          trace(site, process, "=>", "Stop")
          outputs(errors, 0) { outputs.transition(implicit t => Result(site, process, Right(u)))}
      }
    }
  }
}
