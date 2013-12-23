package au.com.langdale
package async
import language.higherKinds

/**
 * The basic API for this library declares Processes composed of Actions 
 * that run at Sites which have input and output ports.
 * This can be enhanced by DSLs to construct Processes and graphs of these.
 *
 * To use this API, including the DSLs: import au.com.langdale.async.Flow._
 *
 */
trait Flow extends Labels { 

  /** Represents cooperatively scheduled thread of control that executes at a site. */
  trait Process {

    /** the initial step of this process */
    def action: Action 

    /** document your process */
    def description: String

    /** a process to be executed after this process */
    def followedBy: Option[Process] = None

    override def toString = s"Process($description)"
  }

  /** A continuation, a series of which form a process */
  type Action 

  /** Actions that receive input can be combined */
  type InputAction <: Action with ActionCombinator

  trait ActionCombinator {
    def orElse( alt: InputAction): InputAction
  }

  /** Create an input action */
  def input[Message]( label: InputPort[Message])( step: Message => Action ): InputAction

  /** Create an output action */
  def output[Message]( label: OutputPort[Message], m: Message, n: Int = 0)( step: => Action ): Action 

  /** Create an action that depends on the site */
  def control(step: (Site, Process) => Action): Action

  /** Create an action that depends on the fanout of a port */
  def fanout[Message]( label: OutputPort[Message])(step: Int => Action): Action = 
    control { (site, _) => step(site.fanout(label)) }

  /** Perform actions one after the other */
  def sequence(step1: => Action)(step2: => Action): Action

  /** Fork a parallel process at this site */
  def fork( child: Process, instances: Int = 1 )( parent: => Action ): Action = 
    control { (site, _) => site.run(child, instances); parent }

  /** Continue after a delay or timeout an input operation. */
  def after(millis: Long)(step: => Action): InputAction

  /** A no-op wih no continuation */
  def stop: Action

  /** A site at which processes run, messages are received and sent. */
  type Site <: SiteOps

  trait SiteOps {

    /** change the buffer depth for an input */
    def buffer[Message]( label: InputPort[Message], depth: Int): Unit

    /** Connect an output port to an input port */
    def connect[Message]( label: OutputPort[Message], site: Site, splice: InputPort[Message], n: Int = 0): Unit

    /** Disconnect an output */
    def disconnect[Message](label: OutputPort[Message], n: Int = 0): Unit

    /** Count of the number of output ports with the given label at some recent instant. */
    def fanout[Message](label: OutputPort[Message]): Int

    /** Inject a new continuation to be dispatched at this site */ 
    def run(process: Process, instances: Int = 1): Unit
  }

  /** Create a Site */
  def createSite: Site

  /** The port label for errors and supervision */
  val errors = label[(Site, Process, Throwable)]
}

object Flow extends Processes with Actions with Builder with GraphDSL with Labels.Basic with FlowImpl with Executor.ForkJoin with Trace.Noop {
  object Debug extends Processes with Actions with Builder with GraphDSL with Labels.Basic with FlowImpl with Executor.ForkJoin with Trace.Flat
}
