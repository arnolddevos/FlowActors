package au.com.langdale
package async

/**
 * Define the Process type and provive Process contruction utilities.
 */
trait Processes extends Flow {

  /** Combinators for processes */
  implicit class ProcessOps( underlying: Process ) {

    def andThen( other: Process) = 
      new Process {
        def description = underlying.description
        def action = underlying.action
        override def followedBy = {
          def rebuild(op: Option[Process]): Process = op match {
            case Some(p) => 
              new Process {
                def description = p.description
                def action = p.action
                override def followedBy = Some(rebuild(p.followedBy))
              }
            case None => other
          }
          Some(rebuild(underlying.followedBy))
        }
      }

    def !:( d: String ) = 
      new Process {
        def description = d
        def action = underlying.action
        override def followedBy = underlying.followedBy
      }

    def *(factor: Int) = 
      new Process {
        def description = s"${underlying.description} * $factor"
        def action = fork(underlying, factor)(stop)
        override def followedBy = underlying.followedBy
      }
  }

  def process(step: => Action) = new Process {
    def description = "anonymous"
    def action = step
  }

  case class DeadLetter[X](value: X, port: InputPort[X], cause: Throwable) extends Exception(cause)

  object DeadLetters {
    type Given = (Any, InputPort[_])

    def unapply(t: Throwable): Option[(Seq[Given], Throwable)] = {
    
      def unwind(gs: List[Given], t: Throwable): (List[Given], Throwable) = t match {
        case DeadLetter(v, p, c) => unwind((v, p) :: gs, c)
        case t => (gs, t)
      }
    
      Some(unwind(Nil, t))
    }
  }

  val defaultSupervisor = new Process {
    def description = "default supervisor"

    def action = {
      def loop: Action = input(errors) {
        case (s, p, DeadLetters(gs, t)) => 
          Console.err.println(s"$p failed with $t")
          if(! gs.isEmpty)
            Console.err.println(gs.mkString("\t", "\n\t", ""))
          loop
      }
      loop
    }
  }

  def balancer[Message](label: Label[Message]) = new Process {
    def description = s"balancer for $label"

    def transfer(i: Int) = new Process {
      def description = s"balancer for $label($i)"
      def action: Action = input(label) { m => 
        output(label, m, i) { action }
      }
    }

    def action = fanout(label) { n => 
      def loop(i: Int): Action = 
        if(i < n) fork(transfer(i)) { loop(i+1) }
        else stop

      loop(0)
    }
  }

  def repeater[Message](label: Label[Message]) = new Process {
    def description = s"repeater for $label"

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
