package au.com.langdale
package async
import language.higherKinds

trait Flow {

  type InputChannel[-Message] <: InputOps[Message]

  trait InputOps[-Message] {
    def !(m: Message): Unit
    def buffer(n: Int): Unit
  }

  type OutputChannel[+Message] <: OutputOps[Message]

  trait OutputOps[+Message] { 
    def -->[M >: Message]( c: InputChannel[M]): Unit
    def disconnect: Unit
  }

  trait Actor { 
    protected val self = site(this)
    def input[Message]( buffer: Int = 1) = self.input[Message](buffer)
    def output[Message]() = self.output[Message]()
    def stop = self.stop
    def error = self.error
    def start = self.inject(act())

    type Action = self.Action
    protected def act(): Action
  }

  type Site <: SiteOps

  trait SiteOps {

    type Action 
    type InputAction <: Action with ActionCombinator

    trait ActionCombinator {
      def orElse( alt: InputAction): InputAction
    }

    trait InputReactor[+Message] {
      def apply( step: Message => Action ): InputAction
      def react( step: Message => Action ) = apply(step)
    }

    trait OutputReactor[-Message] {
      def apply( m: Message)( step: => Action ): Action 
    }

    // methods to create channels
    def input[Message]( buffer: Int = 1): InputReactor[Message] with InputChannel[Message]
    def output[Message](): OutputReactor[Message] with OutputChannel[Message]
    
    // ways to stop: the error channel and stop action
    type Reference
    def error: OutputReactor[(Reference, Throwable)] with OutputChannel[(Reference, Throwable)]
    def stop: Action

    // method to run this actor 
    def inject(step: => Action, instances: Int = 1): Unit
  }

  def site[R](ref: R): Site { type Reference = R }
  def site(): Site { type Reference = Site }
}

object Flow extends Flow with FlowImpl with FlowExecutor.ForkJoin with FlowTrace.Graphviz
