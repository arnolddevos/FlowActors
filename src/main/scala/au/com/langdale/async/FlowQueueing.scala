package au.com.langdale
package async

import scala.collection.immutable.{Queue, Map}
import scala.language.higherKinds    

object Dequeue {
  def unapply[T](q: Queue[T]) = if(q.isEmpty) None else Some(q.dequeue)
}

trait FlowQueueing { this: FlowTrace =>
 
  type MK[+Message] = Task => Message
  type KM[-Message] = Task => Message => Unit
  def consume[Message](mk: MK[Message])(implicit t: Task): Message = mk(t)
  def transfer[Message](km: KM[Message], m: Message)(implicit t: Task): Unit = km(t)(m)
  
  trait Connection[-Message] {
    def send(mk: MK[Message])(implicit t: Task): Unit
  }

  trait StateMap {

    type Key[_]
    type State[_]

    def initialState[M]: State[M]
    private type Mutation[M] = State[M] => State[M]

    final class Cell[M] extends (Mutation[M] => Unit) {
      private var state = initialState[M]
      def apply( mutation: Mutation[M]): Unit = state = mutation(state)
      def run(mutation: PartialFunction[State[M], State[M]]): Boolean =
        mutation.runWith( state = _ )(state)
    }

    private var underlying = Map[Key[_], Cell[_]]()
    type View[M] = Map[Key[M], Cell[M]]

    final def freshCell[M]: Key[M] => Cell[M] = { key =>
      val cell = new Cell[M]
      underlying = underlying.updated(key, cell)
      cell
    }

    def apply[M](key: Key[M])(mutation: Mutation[M]): Unit = {
      val cell = underlying.asInstanceOf[View[M]].applyOrElse(key, freshCell)
      cell(mutation)
    }

    def run[M](key: Key[M])(mutation: PartialFunction[State[M], State[M]]): Boolean = {
      val cell = underlying.asInstanceOf[View[M]].applyOrElse(key, freshCell)
      cell.run(mutation)
    }
  }
  
  trait Wiring {
    
    sealed trait State[Message]
    case class Disconnected[Message]() extends State[Message]
    case class Pending[Message](mks: List[MK[Message]]) extends State[Message]
    case class Connected[Message](c: Connection[Message]) extends State[Message]

    def initialState[Message] = Disconnected[Message]()

    type Transition[M] = State[M] => State[M]

    def transition[Message](mk: MK[Message])(implicit t: Task): Transition[Message] = {
      case Disconnected() => Pending(mk :: Nil)
      case s @ Connected(c) => c.send(mk); s
      case Pending(mks) => Pending( mk :: mks )    
    }

    def transition[Message](c: Connection[Message])(implicit t: Task): Transition[Message] = {
      case Pending(mks) => for( mk <- mks.reverse) c.send(mk); Connected(c)
      case _ => Connected(c)
    }
    
    def transition[Message]()(implicit t: Task): Transition[Message] = {
      case Connected(_) => Disconnected[Message]()
      case s => s 
    }
  }

  trait CancelRef

  trait Queueing {
    
    case class State[Message](qstate: QState[Message], depth: Int)

    sealed trait QState[Message] 
    case class Idle[Message]() extends QState[Message]
    case class Waiters[Message](kms: Map[CancelRef, KM[Message]]) extends QState[Message]
    case class Waiting[Message]( h: CancelRef, km: KM[Message]) extends QState[Message]
    case class Ready[Message]( m: Message) extends QState[Message]
    case class Backlog[Message]( p: Queue[Message], q: Queue[MK[Message]] ) extends QState[Message]

    def initialState[Message] = State(Idle[Message](), 1)

    type Transition[Message] = State[Message] => State[Message]

    def transition[Message](mk: MK[Message])(implicit t: Task): Transition[Message] = {
      case State(qs, d) => State(send(qs)(mk, d), d)
    }

    private def send[Message]( qs: QState[Message])(mk: MK[Message], n: Int)(implicit t: Task): QState[Message] = qs match {
      case Waiters(kms0) if ! kms0.isEmpty =>
        val ((_, km), kms) = (kms0.head, kms0.tail)
        transfer(km, consume(mk))
        if(kms.size == 1) Waiting(kms.head._1, kms.head._2) else Waiters(kms)
      case Waiting(_, km) =>
        transfer(km, consume(mk))
        Idle[Message]()
      case Idle() if n > 0 => 
        Ready(consume(mk))
      case Idle() => 
        Backlog(Queue.empty, Queue(mk))
      case Ready(m) if n > 1 => 
        Backlog(Queue(m, consume(mk)), Queue.empty)
      case Ready(m) => 
        Backlog(Queue(m), Queue(mk))
      case Backlog(p, q) if n > p.length && q.isEmpty => 
        Backlog(p enqueue consume(mk), q)
      case Backlog(p, q) => 
        Backlog(p, q enqueue mk)
      case s @ Waiters(_) =>
        trace("error", "inconsistent state", s); s
    }

    def transition[Message](h: CancelRef)(km: KM[Message])(implicit t: Task): Transition[Message] = {
      case State(qs, d) => State( recv(qs)(h, km), d)
    }

    private def recv[Message]( qs: QState[Message])(h: CancelRef, km: KM[Message])(implicit t: Task): QState[Message] = qs match {
      case Waiters(kms) => Waiters( kms + (h -> km))
      case Waiting(h0, km0) => Waiters(Map(h0 -> km0, h -> km))
      case Idle() => Waiting(h, km)
      case Ready(m) => 
        transfer(km, m)
        Idle[Message]()
      case Backlog(Dequeue(m, p), Dequeue(mk, q)) => 
        transfer(km, m)
        if( p.isEmpty && q.isEmpty) Ready(consume(mk)) else Backlog(p enqueue consume(mk), q)
      case Backlog(_, Dequeue(mk, q)) => 
        transfer(km, consume(mk))
        if(q.isEmpty) Idle[Message]() else Backlog(Queue.empty, q)
      case Backlog(Dequeue(m, p), _) =>
        transfer(km, m)
        if(p.length == 1) Ready(p.head) else Backlog(p, Queue.empty) 
      case s @ Backlog(_, _) => 
        trace("error", "inconsistent state", s); s
    }
          
    def cancel[Message](h: CancelRef)(implicit t: Task): Transition[Message] = {
      case State(qs, d) => State( cancel(qs)(h), d)
    }

    private def cancel[Message]( qs: QState[Message])(h: CancelRef)(implicit t: Task): QState[Message] = qs match {
      case Waiting(`h`, _) => Idle[Message]()
      case Waiters(kms0) => 
        val kms = kms0 - h
        if(kms.size == 1) Waiting(kms.head._1, kms.head._2) else Waiters(kms)
      case s => s
    }

    def transitionMaybe[Message](km: KM[Message])(implicit t: Task): PartialFunction[State[Message], State[Message]] = {
      case State(Ready(m), d) => 
        transfer(km, m)
        State(Idle[Message](), d)
    }
  
    def transition[Message](n: Int)(implicit t: Task): Transition[Message] = {
      case State(qs, _) =>
        def unroll(qs: QState[Message], n: Int): QState[Message] = qs match {
          case Backlog(p, Dequeue(mk, q)) if n > p.length =>
            unroll(Backlog(p enqueue consume(mk), q), n)
          case qs => qs
        }
        State(unroll(qs, n), n)
    }
  } 
}
