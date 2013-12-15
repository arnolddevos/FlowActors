package au.com.langdale
package async

import scala.util.control.NonFatal
import java.util.{Timer, TimerTask}

trait FlowImpl extends Flow with Primitives with Queueing { this: Trace with Executor =>

  def createSite(process: Process) = new Site(process)

  final class Site(val process: Process) extends SiteOps with PrimitiveActor { site =>

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

    private[FlowImpl] def runStep( step: => Action )(implicit t: Task) = spawn { implicit t => 
      val a = try { 
        trace(site, "=>", "Step")
        step 
      }
      catch { 
        case NonFatal(e) => 
          trace("error", site, "uncaught exception", e)
          output(errors, (this, e), 0)(stop)
      }
      
      enqueue(implicit t => a.dispatch(this))
    } 
    
    /** inject an action into the site */
    def run(step: => Action, instances: Int) = request { implicit t => 
      trace(site, "=>", "Run")
      for( i <- 1 to instances) runStep { step }
    }
      
    /** change buffering depth */  
    def buffer[Message]( label: InputPort[Message], depth: Int): Unit = request { implicit t =>
      inputs(label) { inputs.transition(depth) }
    }

    /** Connect an output port to an input port */
    def connect[Message]( labelA: OutputPort[Message], siteB: Site, labelB: InputPort[Message], n: Int): Unit = {
      request { implicit t =>
        outputs(labelA, n) { outputs.transition( siteB.connection(labelB)) }
      }
    }

    /** disconnect an output */
    def disconnect[Message](label: OutputPort[Message], n: Int): Unit = request { implicit t =>
      outputs(label, n) { outputs.transition[Message]() }
    }

    def fanout[Message](label: OutputPort[Message]): Int = outputs.keys.filter( _._1 == label ).size
  }

  /** Create an input action */
  def input[Message]( label: InputPort[Message])( step: Message => Action ): InputAction = 
    new SingleInputAction(label)(step)

  /** Create an output action */
  def output[Message]( label: OutputPort[Message], m: Message, n: Int)( step: => Action ): Action = 
    new OutputAction(label, n: Int, m)(step)

  /** create an action that depends on a port's fanout */
  def control(step: Site => Action): Action = 
    new Control(step)

  /** Continue after a delay or timeout an input operation. */
  def after(millis: Long)(step: => Action): InputAction = 
    new TimerAction(millis)(step)

  /** create a stop action */
  def stop: Action = Stop()

  sealed trait Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task): Unit
  }
  
  sealed trait InputAction extends Action with ActionCombinator {
    def orElse( other: InputAction ): InputAction = new Alternatives(this, other)
  }

  private class OutputAction[Message]( label: OutputPort[Message], n: Int, m: Message)( step: => Action ) extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) = { import site._
      outputs(label, n) { outputs.transition(implicit t => { runStep(step); m })}
    }
  }

  private trait BasicInputAction extends InputAction {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) = dispatchLater(site, Cancellation { t => () })
    def dispatchLater(site: Site, cancel: Cancellation)(implicit t: Task): Cancellation
    def dispatchNow(site: Site)(implicit t: Task): Boolean
  }

  case class Cancellation( f: Task => Unit ) {
    def run( implicit t:Task) = f(t)
  }

  private class SingleInputAction[Message]( label: InputPort[Message])( step: Message => Action ) extends BasicInputAction {
      
    def dispatchLater(site: Site, cancel: Cancellation)(implicit t: Task) = { import site._
      val id = new CancelRef
      inputs(label) { 
        inputs.transition[Message](id){ implicit t => m =>
          cancel.run; runStep { step(m) }
        }
      }
      Cancellation { implicit t => inputs(label) { inputs.cancel(id) }}
    }

    def dispatchNow(site: Site)(implicit t: Task): Boolean = { import site._
      inputs.run(label) { 
        inputs.transitionMaybe[Message]{ implicit t => m =>
          runStep { step(m) }
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

  private class TimerAction(delay: Long)(step: => Action) extends BasicInputAction {
    def dispatchLater(site: Site, cancel: Cancellation)(implicit t: Task) = {
      @volatile var cancelled = false
      val event = timer.after(delay) { site.enqueue { implicit t => if(!cancelled) { cancel.run; site.runStep { step }}}}
      Cancellation { t => cancelled = true; event.cancel }
    }
    def dispatchNow(site: Site)(implicit t: Task) = false
  }
  
  private case class Alternatives(a1: InputAction, a2: InputAction) extends InputAction {
    
    final def toList: List[BasicInputAction] = {
      def gather(a: InputAction): List[BasicInputAction] = a match {
        case a: BasicInputAction => List(a)
        case Alternatives(a1, a2) => gather(a2) ::: gather(a1)
      }
      gather(this)
    }
    
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task): Unit = {
      val actions = toList
      val done = actions.exists(_.dispatchNow(site))
      if( ! done ) {
        var cs: List[Cancellation] = List(Cancellation( t => sys.error("dispatch called outside enqueue")))
        def cancelAll = Cancellation { implicit t => for( c <- cs ) { c.run }}
        cs = actions.map( _.dispatchLater(site, cancelAll))
      }
    }
  }

  private class Control( step: Site => Action ) extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) = { 
      site.runStep(step(site))
    }
  }
  
  private case class Stop() extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) { trace(site, "=>", "Stop")}
  }
}
