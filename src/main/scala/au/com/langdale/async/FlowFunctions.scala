package au.com.langdale
package async

trait FlowFunctions { this: FlowGraph =>
 
  def lift[X](e: PortExpr[X])(x: X) = new Process {
    def description = e.description
    def action: Action = e.lift(x, action)
  }

  def option[Y](expr: OutputExpr[Y]) = new OutputExpr[Option[Y]] {
    def description = s"option(${expr.description})"
    def lift(cont: => Action): Option[Y] => Action = {
      case Some(y) => expr.lift(cont)(y)
      case None => cont
    }
  }

  def seq[Y](expr: OutputExpr[Y]) = new OutputExpr[Seq[Y]] {
    def description = s"seq(${expr.description})"
    def lift(cont: => Action): Seq[Y] => Action = {
      def loop( ys: Seq[Y]): Action = ys.headOption match {
        case Some(y) => expr.lift(loop(ys.tail))(y)
        case None => cont
      }
      loop _
    }
  }

  def eitherOutput[Y1, Y2](expr1: OutputExpr[Y1], expr2: OutputExpr[Y2]) = new OutputExpr[Either[Y1, Y2]] {
    def description = s"eitherOutput(${expr1.description},${expr2.description})"
    def lift(cont: => Action): Either[Y1, Y2] => Action = {
      case Left(y1) => expr1.lift(cont)(y1)
      case Right(y2) => expr2.lift(cont)(y2)
    }
  }

  def bothOutputs[Y1, Y2](expr1: OutputExpr[Y1], expr2: OutputExpr[Y2]) = new OutputExpr[(Y1, Y2)] {
    def description = s"bothOutputs(${expr1.description},${expr2.description})"
    def lift(cont: => Action): ((Y1, Y2)) => Action = {
      case (y1, y2) => expr1.lift(expr2.lift(cont)(y2))(y1)
    }
  }

  def either[X1, X2](expr1: InputExpr[X1], expr2: InputExpr[X2]) = new InputExpr[Either[X1, X2]] {
    def description = s"either(${expr1.description},${expr2.description})"
    def lift(cont: Either[X1, X2] => Action) = expr1.lift(x1 => cont(Left(x1))) orElse expr2.lift(x2 => cont(Right(x2)))
  }

  def both[X1, X2](expr1: InputExpr[X1], expr2: InputExpr[X2]) = new InputExpr[(X1, X2)] {
    def description = s"either(${expr1.description},${expr2.description})"
    def lift(cont: ((X1, X2)) => Action) = expr1.lift(x1 => expr2.lift(x2 => cont((x1, x2))))
  }

  trait PortExpr[F] { right =>
    def description: String
    def lift(f: F, cont: => Action): Action

    def =>:[X](left: InputExpr[X]) = new PortExpr[X => F] {
      def description = s"${left.description} => ${right.description}"
      def lift(f: X => F, cont: => Action) = left.lift(x => right.lift(f(x), cont))
    }
  }

  trait InputExpr[X] {
    def description: String
    def lift(cont: X => Action): InputAction
  }

  trait OutputExpr[Y] { right =>
    def description: String
    def lift(cont: => Action): Y => Action

    def =>:[X](left: InputExpr[X]) = new PortExpr[X => Y] {
      def description = s"${left.description} => ${right.description}"
      def lift(f: X => Y, cont: => Action) = left.lift(f andThen right.lift(cont))
    }
  }

  implicit class SingleInputExpr[X]( port: InputPort[X]) extends InputExpr[X] {
    def description = port.toString
    def lift(cont: X => Action) = input(port)(cont)
  }

  implicit class SingleOutputExpr[Y]( port: OutputPort[Y]) extends OutputExpr[Y] {
    def description = port.toString
    def lift(cont: => Action) = (y: Y) => output(port, y)(cont)
  }

  def balancer[Message](label: Label[Message]) = new Process {
    def description = s"balancer for $label"

    def action = fanout(label) { n => 
      
      def transfer(i: Int): Action = input(label) { m => 
        output(label, m, i) { transfer(i)}
      }
      
      def loop(i: Int): Action = 
        if(i < n-1) fork(transfer(i)) { loop(i+1) }
        else transfer(i)

      if(n > 0) loop(0)
      else stop
    }
  }

  def repeater[Message](label: Label[Message]) = new Process {
    def description = s"balancer for $label"

    def action = fanout(label) { n => 
     
      def receive: Action = input(label) { m =>
        send(0, m)
      }

      def send(i: Int, m: Message): Action =
        if(i < n) output(label, m, i)(send(i+1, m))
        else receive

      if(n > 0) receive
      else stop
    }
  }
}
