package au.com.langdale
package async

import scala.collection.immutable.{Queue, Map}
    
object Dequeue {
  def unapply[T](q: Queue[T]) = if(q.isEmpty) None else Some(q.dequeue)
}

trait FlowQueueing { this: FlowTrace =>
 
  type MK[+Message] = Task => Message
  type KM[-Message] = Task => Message => Unit
  def consume[Message](mk: MK[Message])(implicit t: Task): Message = mk(t)
  def transfer[Message](km: KM[Message], m: Message)(implicit t: Task): Unit = km(t)(m)
  
  trait Connection[-Message] {
    private[async] def send(mk: MK[Message])(implicit t: Task): Unit
  }
  
  trait Wiring[Message] {
    
    sealed trait State { s =>
      
      def transition(mk: MK[Message])(implicit t: Task): State = s match {
        case Disconnected => Pending(mk :: Nil)
        case Connected(c) => c.send(mk); s
        case Pending(mks) => Pending( mk :: mks )    
      }

      def transition(c: Connection[Message])(implicit t: Task): State = s match {
        case Pending(mks) => for( mk <- mks.reverse) c.send(mk); Connected(c)
        case _ => Connected(c)
      }
      
      def transition()(implicit t: Task): State = s match {
        case Connected(_) => Disconnected
        case s => s 
      }
    }

    case object Disconnected extends State
    case class Pending(mks: List[MK[Message]]) extends State
    case class Connected(c: Connection[Message]) extends State
  }

  trait Queueing[Message] {
    trait CancelRef
    
    sealed trait State { s =>

      def transition(mk: MK[Message], n: Int)(implicit t: Task): State = s match {
        case Waiters(kms0) if ! kms0.isEmpty =>
          val ((_, km), kms) = (kms0.head, kms0.tail)
          transfer(km, consume(mk))
          if(kms.size == 1) Waiting.tupled(kms.head) else Waiters(kms)
        case Waiting(_, km) =>
          transfer(km, consume(mk))
          Idle
        case Idle if n > 0 => 
          Ready(consume(mk))
        case Idle => 
          Backlog(Queue.empty, Queue(mk))
        case Ready(m) if n > 1 => 
          Backlog(Queue(m, consume(mk)), Queue.empty)
        case Ready(m) => 
          Backlog(Queue(m), Queue(mk))
        case Backlog(p, q) if n > p.length && q.isEmpty => 
          Backlog(p enqueue consume(mk), q)
        case Backlog(p, q) => 
          Backlog(p, q enqueue mk)
        case Waiters(_) =>
          trace("error", "inconsistent state", s); s
      }
        
      def transition(h: CancelRef)(km: KM[Message])(implicit t: Task): State = s match {
        case Waiters(kms) => Waiters( kms + (h -> km))
        case Waiting(h0, km0) => Waiters(Map(h0 -> km0, h -> km))
        case Idle => Waiting(h, km)
        case Ready(m) => 
          transfer(km, m)
          Idle
        case Backlog(Dequeue(m, p), Dequeue(mk, q)) => 
          transfer(km, m)
          if( p.isEmpty && q.isEmpty) Ready(consume(mk)) else Backlog(p enqueue consume(mk), q)
        case Backlog(_, Dequeue(mk, q)) => 
          transfer(km, consume(mk))
          if(q.isEmpty) Idle else Backlog(Queue.empty, q)
        case Backlog(Dequeue(m, p), _) =>
          transfer(km, m)
          if(p.length == 1) Ready(p.head) else Backlog(p, Queue.empty) 
        case Backlog(_, _) => 
          trace("error", "inconsistent state", s); s
      }
      
      def cancel(h: CancelRef)(implicit t: Task): State = s match {
        case Waiting(`h`, _) => Idle
        case Waiters(kms0) => 
          val kms = kms0 - h
          if(kms.size == 1) Waiting.tupled(kms.head) else Waiters(kms)
          
        case s => s
      }
    
      def transition(n: Int)(implicit t: Task): State = {
        def unroll(s: State, n: Int): State = s match {
          case Backlog(p, Dequeue(mk, q)) if n > p.length =>
            unroll(Backlog(p enqueue consume(mk), q), n)
          case s => s
        }
        unroll(this, n)
      }
    }

    case object Idle extends State
    case class Waiters(kms: Map[CancelRef, KM[Message]]) extends State
    case class Waiting( h: CancelRef, km: KM[Message]) extends State
    case class Ready( m: Message) extends State
    case class Backlog( p: Queue[Message], q: Queue[MK[Message]] ) extends State
  } 
}
