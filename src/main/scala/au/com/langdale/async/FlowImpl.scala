package au.com.langdale
package async

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

trait FlowImpl extends FlowPrimitives with FlowQueueing { this: Flow with FlowTrace with FlowExecutor =>

  trait InputChannel[-Message] extends InputOps[Message] with Connection[Message]
  trait OutputChannel[+Message] extends OutputOps[Message]

  trait Actor extends ActorOps with PrimitiveActor { actor =>
    
    val channelCount = new AtomicInteger
    
    trait Action {
      private[async] def dispatch()(implicit t: Task): Unit
    }
    
    trait InputAction extends Action with ActionCombinator {
      def orElse( other: InputAction ): InputAction = new Alternatives(this, other)
      private[async] def dispatch()(implicit t: Task) = dispatchAndCancel(_ => ())
      private[async] def dispatchAndCancel(bubble: Task => Unit)(implicit t: Task): Unit
      private[async] def cancel()(implicit t: Task): Unit
    }
    
    private class Alternatives(a: InputAction, b: InputAction) extends InputAction {
      private[async] def dispatchAndCancel(bubble: Task => Unit)(implicit t: Task) = {
        a dispatchAndCancel { implicit t => b.cancel(); bubble(t) }
        b dispatchAndCancel { implicit t => a.cancel(); bubble(t) }
      }
      private[async] def cancel()(implicit t: Task) = { a.cancel(); b.cancel() }
    }
    
    private case class Stop() extends Action {
      private[async] def dispatch()(implicit t: Task) { trace(actor, "=>", "Stop")}
    }

    class Input[Message](d: Int) extends InputReactor[Message] with InputChannel[Message] with Queueing[Message] {
      val channelId = channelCount.incrementAndGet
      override def toString = "Input(" + actorId + "." + channelId + ")"
      
      private var depth = d
      private var _state: State = Idle
      def state_=(s: State)(implicit t: Task) { _state = s; trace(this, "=>", s)}
      def state = _state
      
      def !(m: Message): Unit = request { implicit t => 
        state = state.transition(t => m, depth) 
      }
      
      private[async] def send(mk: MK[Message])(implicit t: Task) = enqueue { implicit t =>
        state = state.transition(mk, depth)
      }
      
      def apply( f: Message => Action): InputAction = new InputAction with CancelRef {
        override def toString = "Action on " + Input.this
        
        private[async] def dispatchAndCancel(bubble: Task => Unit)(implicit t: Task) = {
          state = state.transition(this){ implicit t => m =>
            enqueue { implicit t =>
              bubble(t)  
              runStep { f(m) }
            }
          } 
        }
        
        private[async] def cancel()(implicit t: Task) = {
          state = state.cancel(this)
        }
      }
      
      def buffer(d: Int) = request { implicit t =>
        depth = d
        state = state.transition(d)
      }
    } 
    
    class Output[Message] extends OutputReactor[Message] with OutputChannel[Message] with Wiring[Message] {
      val channelId = channelCount.incrementAndGet
      override def toString = "Output(" + actorId + "." + channelId + ")"
      
      private var _state: State = Disconnected
      def state_=(s: State)(implicit t: Task) { _state = s; trace(this, "=>", s)}
      def state = _state
      
      def apply( m: Message)( k: => Action): Action = new Action {
        private[async] def dispatch()(implicit t: Task) = {
          state = state.transition(implicit t => { runStep(k); m })
        }
      }
      
      def -->[M >: Message]( c: InputChannel[M]): Unit = request { implicit t =>
        state = state.transition(c)
      }
        
      def disconnect: Unit = request { implicit t =>
        state = state.transition()
      }
    }
        
    def Input[Message]( buffer: Int ): InputReactor[Message] with InputChannel[Message] = new Input[Message](buffer)
    def Output[Message](): OutputReactor[Message] with OutputChannel[Message] = new Output[Message]
    val error = Output[(Actor, Throwable)]()
    
    def stop: Action = Stop()
    
    private def runStep( step: => Action )(implicit t: Task) = spawn { implicit t => 
      val a = try { 
        trace(actor, "=>", "Step")
        step 
      }
      catch { 
        case NonFatal(e) => 
          trace("error", actor, "uncaught exception", e)
          error(actor, e) { stop }
      }
      
      enqueue(implicit t => a.dispatch())
    } 
    
    def run(step: => Action, instances: Int) = request { implicit t => 
      trace(actor, "=>", "Run")
      for( i <- 1 to instances) runStep { step }
    }
  }
}
