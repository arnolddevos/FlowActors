package au.com.langdale
package async

import scala.util.control.NonFatal

/**
 *  Operations to lift functions into processes and attach port labels. 
 *  This involves a transformation from direct style to continuation passing style.
 *
 *  The eligable port labels should be marked as implicit to be picked up by the
 *  lifting functions and typeclasses. 
 *
 *  Alternatively, a small DSL is used to express the function signature 
 *  explicitly in terms of port labels.
 *
 *  Example usage: Given these declarations
 *
 *  implicit val xs: Label[X]
 *  implicit val ys: Label[Y]
 *  val f: X => Y
 *
 *  then a process that receives Xs and emits Ys can be created by either of these:-
 *
 *  val p1 = transform[X => Y](f)      // use implicit port labels for the resulting process
 *  val p2 = transformAs(xs =>: ys)(f) // use explicit port labels
 *
 *  The transform operation can handle curried functions of any number of arguments.
 *  Eligable argument and result types must have implicit labels, 
 *  or may be Either, Tuple2, Iterable, Iterator or Option contructions of eligable types.
 */
trait Actions { this: Flow with Processes =>

  def transform[F](f: F)(implicit e: Expr[F]) = new Process {
    def description = e.description
    def action = {
      def loop: Action = e.lift(f, loop)
      loop
    }
  }

  def produce[Y](y: Y)(implicit e: OutputExpr[Y]) = new Process { 
    def description = s"produce(${e.description})"
    def action = e.lift(stop)(y)
  }

  def consume[X](f: X => Unit)(implicit e: InputExpr[X]) = new Process {
    def description = s"consume(${e.description})"
    def action = {
      def loop: Action = e.lift { x => f(x); loop }
      loop
    }
  }

  trait Zero[S] { def zero: S }
  def zero[S](s: S) = new Zero[S] { def zero = s }

  def machine[F](f: F)(implicit e: Machine[F]) = new Process {
    def description = e.description
    def action = e.lift(f)
  }

  trait Machine[F] {
    def description: String
    def lift(f: F): Action
  }

  implicit def stateMachine[X,Y,S](implicit e1: InputExpr[X], e2: OutputExpr[Y], e3: Zero[S]) = new Machine[(S, X) => (S, Y)] {
    def description = s"integrate ${e1.description} producing ${e2.description}"
    def lift(f: (S, X) => (S, Y)) = {
      def loop(s0: S): Action = e1.lift { x => 
        val (s1, y) = f(s0, x)
        e2.lift(loop(s1))(y)
      }
      loop(e3.zero)
    }
  }

  implicit def accumulator[X,S](implicit e: Machine[(S, X) => (S, S)] ) = new Machine[(S, X) => S] {
    def description = e.description
    def lift(f: (S, X) => S) = e.lift { (s0, x) => val s1 = f(s0, x); (s1, s1) }
  }

  def transformAs[F](e: Expr[F])(f: F) = transform(f)(e)
  def produceAs[Y](e: OutputExpr[Y])(y: Y) = produce(y)(e)
  def consumeFrom[X](e: InputExpr[X])(f: X => Unit) = consume(f)(e)

  implicit def option[Y](implicit expr: OutputExpr[Y]) = new OutputExpr[Option[Y]] {
    def description = s"option(${expr.description})"
    def lift(cont: => Action): Option[Y] => Action = {
      case Some(y) => expr.lift(cont)(y)
      case None => cont
    }
  }

  implicit def traverse[Y](implicit expr: OutputExpr[Y]) = new OutputExpr[TraversableOnce[Y]] {
    def description = s"seq(${expr.description})"
    def lift(cont: => Action): TraversableOnce[Y] => Action = { ys =>
      val yi = ys.toIterator
      def loop: Action = 
        if(yi.hasNext) { val y = yi.next; expr.lift(loop)(y) }
        else cont
      loop
    }
  }

  implicit def eitherOutput[Y1, Y2](implicit expr1: OutputExpr[Y1], expr2: OutputExpr[Y2]) = new OutputExpr[Either[Y1, Y2]] {
    def description = s"eitherOutput(${expr1.description},${expr2.description})"
    def lift(cont: => Action): Either[Y1, Y2] => Action = {
      case Left(y1) => expr1.lift(cont)(y1)
      case Right(y2) => expr2.lift(cont)(y2)
    }
  }

  implicit def bothOutputs[Y1, Y2](implicit expr1: OutputExpr[Y1], expr2: OutputExpr[Y2]) = new OutputExpr[(Y1, Y2)] {
    def description = s"bothOutputs(${expr1.description},${expr2.description})"
    def lift(cont: => Action): ((Y1, Y2)) => Action = {
      case (y1, y2) => expr1.lift(expr2.lift(cont)(y2))(y1)
    }
  }

  implicit def either[X1, X2](implicit expr1: InputExpr[X1], expr2: InputExpr[X2]) = new InputExpr[Either[X1, X2]] {
    def description = s"either(${expr1.description},${expr2.description})"
    def lift(cont: Either[X1, X2] => Action) = expr1.lift(x1 => cont(Left(x1))) orElse expr2.lift(x2 => cont(Right(x2)))
  }

  implicit def both[X1, X2](implicit expr1: InputExpr[X1], expr2: InputExpr[X2]) = new InputExpr[(X1, X2)] {
    def description = s"both(${expr1.description},${expr2.description})"
    def lift(cont: ((X1, X2)) => Action) = expr1.lift(x1 => expr2.lift(x2 => cont((x1, x2))))
  }

  implicit def function[X, Y](implicit left: InputExpr[X], right: OutputExpr[Y]) = new Expr[X => Y] {
    def description = s"${left.description} => ${right.description}"
    def lift(f: X => Y, cont: => Action) = left.lift(f andThen right.lift(cont))
  }

  implicit def higherFunction[X, F](implicit left: InputExpr[X], right: Expr[F]) = new Expr[X => F] {
    def description = s"${left.description} => ${right.description}"
    def lift(f: X => F, cont: => Action) = left.lift(x => right.lift(f(x), cont))
  }

  implicit def forkedProcess = new OutputExpr[Process] {
    def description = "fork a process"
    def lift( cont: => Action) = p => fork(p.action)(cont)
  }

  trait Expr[F] { right =>
    def description: String
    def lift(f: F, cont: => Action): Action
    def =>:[X](left: InputExpr[X]) = higherFunction(left, right)
  }

  trait InputExpr[X] {
    def description: String
    def lift(cont: X => Action): InputAction
  }

  trait OutputExpr[Y] { right =>
    def description: String
    def lift(cont: => Action): Y => Action
    def =>:[X](left: InputExpr[X]) = function(left, right)
  }

  implicit def inputPort[X](implicit port: InputPort[X]) = SingleInputExpr(port)

  implicit class SingleInputExpr[X]( port: InputPort[X]) extends InputExpr[X] {
    def description = port.toString
    def lift(cont: X => Action) = input(port) { x =>
      try {
        cont(x)
      }
      catch {
        case NonFatal(e) => throw DeadLetter(x, port, e)
      }
    }
  }

  implicit def outputPort[Y](implicit port: OutputPort[Y]) = SingleOutputExpr(port)

  implicit class SingleOutputExpr[Y]( port: OutputPort[Y]) extends OutputExpr[Y] {
    def description = port.toString
    def lift(cont: => Action) = (y: Y) => output(port, y)(cont)
  }
}
