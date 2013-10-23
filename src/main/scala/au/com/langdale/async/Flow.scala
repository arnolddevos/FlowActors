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

  type Actor <: ActorOps

  trait ActorOps {

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
    def error: OutputReactor[(Actor, Throwable)] with OutputChannel[(Actor, Throwable)]
    def stop: Action

    // method to run this actor 
    def run(step: => Action, instances: Int = 1): Unit
  }

  def actor(): Actor
}

object Flow extends Flow with FlowImpl with FlowExecutor.ForkJoin with FlowTrace.Graphviz
