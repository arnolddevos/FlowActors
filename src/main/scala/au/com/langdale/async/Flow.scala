package au.com.langdale
package async
import language.higherKinds

trait Flow {

  /** External view of an input port for wiring */
  type InputPort[-Message] <: InputOps[Message]

  trait InputOps[-Message] {
    def !(m: Message): Unit
    def buffer(n: Int): Unit
  }

  /** External view of an output port for wiring */
  type OutputPort[+Message] <: OutputOps[Message]

  trait OutputOps[+Message] { 
    def -->[M >: Message]( c: InputPort[M]): Unit
    def disconnect: Unit
  }

  /** A site at which messages are processed. */
  type Site <: SiteOps

  trait SiteOps {

    /** A continuation that can be dispatched at this site */
    type Action 
    type InputAction <: Action with ActionCombinator

    trait ActionCombinator {
      def orElse( alt: InputAction): InputAction
    }

    /** Internal view of an input port for reacting to incoming messages */
    trait InputReactor[+Message] {
      def apply( step: Message => Action ): InputAction
      def react( step: Message => Action ) = apply(step)
    }

    /** Internal view of an output port for sending outgoing messages */
    trait OutputReactor[-Message] {
      def apply( m: Message)( step: => Action ): Action 
    }

    /** Create an input port */
    def input[Message]( buffer: Int = 1): InputReactor[Message] with InputPort[Message]

    /** Create an output port */
    def output[Message](): OutputReactor[Message] with OutputPort[Message]
    
    /** The type of object used in error reporting */
    type Reference

    /** The error output port for this site */
    def error: OutputReactor[(Reference, Throwable)] with OutputPort[(Reference, Throwable)]

    /** An empty continuation */
    def stop: Action

    /** Inject a continuation to be dispatched at this site */ 
    def inject(step: => Action, instances: Int = 1): Unit
  }

  /** Create a Site */
  def site(): Site { type Reference = Site }

  /** Create a Site with a reference for error reporting */
  def site[R](ref: R): Site { type Reference = R }

  /** A base trait for a simple actor wrapping a Site */
  trait Actor { 
    protected val self = site(this)

    type Action = self.Action
    def input[Message]( buffer: Int = 1) = self.input[Message](buffer)
    def output[Message]() = self.output[Message]()
    def stop = self.stop
    def error = self.error

    def start = self.inject(act())
    protected def act(): Action
  }
}

object Flow extends Flow with FlowImpl with FlowExecutor.ForkJoin with FlowTrace.Graphviz
