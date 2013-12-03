package au.com.langdale
package async

import scala.util.control.NonFatal

trait FlowImpl extends Flow with FlowPrimitives with FlowQueueing { this: FlowTrace with FlowExecutor =>

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
          output(supervisor, (this, e), 0)(stop)
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
    new BasicInputAction(label)(step)

  /** Create an output action */
  def output[Message]( label: OutputPort[Message], m: Message, n: Int)( step: => Action ): Action = 
    new OutputAction(label, n: Int, m)(step)

  /** create an action that depends on a port's fanout */
  def fanout[Message]( label: OutputPort[Message])(step: Int => Action): Action = 
    new Fanout(label)(step)
  
  /** Fork another thread of control */
  def fork( step1: => Action )( step2: => Action ): Action =
    new Fork(step1, step2)

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

  private class Fanout[Message]( label: OutputPort[Message] )( step: Int => Action ) extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) = { 
      site.runStep(step(site.fanout(label)))
    }
  }

  private class BasicInputAction[Message]( label: InputPort[Message])( step: Message => Action ) 
    extends InputAction with CancelRef { id: CancelRef =>
    
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) = dispatchLater(site, () => ())
    
    def dispatchLater(site: Site, cancel: () => Unit)(implicit t: Task) = { import site._
      inputs(label) { 
        inputs.transition[Message](id){ implicit t => m =>
          cancel(); runStep { step(m) }
        }
      }
    }

    def dispatchNow(site: Site)(implicit t: Task): Boolean = { import site._
      inputs.run(label) { 
        inputs.transitionMaybe[Message]{ implicit t => m =>
          runStep { step(m) }
        }
      }
    }
    
    def cancel(site: Site)(implicit t: Task) = { import site._
      inputs(label) { inputs.cancel(id) }
    }
  }
  
  private case class Alternatives(a1: InputAction, a2: InputAction) extends InputAction {

    final def exists(a: InputAction=this)(f: BasicInputAction[_] => Boolean): Boolean = a match {
      case a: BasicInputAction[_] => f(a)
      case Alternatives(a1, a2) => exists(a1)(f) || exists(a2)(f)
    }
    
    final def foreach(a: InputAction=this)(f: BasicInputAction[_] => Unit): Unit = a match {
      case a: BasicInputAction[_] => f(a)
      case Alternatives(a1, a2) => foreach(a1)(f); foreach(a2)(f)
    }
    
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task): Unit = {
      val done = exists()(_.dispatchNow(site))
      if( ! done ) {
        val cancel = () => foreach() { _.cancel(site) }
        foreach() { _.dispatchLater(site, cancel) }
      }
    }
  }

  private class Fork( step1: => Action, step2: => Action ) extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task): Unit = { import site._
      runStep(step1); runStep(step2)
    }
  }
  
  private case class Stop() extends Action {
    private[FlowImpl] def dispatch(site: Site)(implicit t: Task) { trace(site, "=>", "Stop")}
  }
}
