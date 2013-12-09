package au.com.langdale
package async

/**
 * Define the Process type and provive Process contruction utilities.
 */
trait Processes extends Flow {

  /** A base trait for a process to execute at a Site */
  trait Process { underlying =>
    override def toString = s"Process($description)"

    def description: String
    def action: Action 

    def *(n: Int) = Parallel(underlying, n)

    def !:( d: String ) = 
      new Process {
        def description = d
        def action = underlying.action
      }
  }

  def process(step: => Action) = new Process {
    def description = "anon"
    def action = step
  }

  def action(process: Process) = process.action

  case class Parallel(underlying: Process, factor: Int) extends Process {
    def description = s"${underlying.description} * $factor"

    def action = {
      def loop(n: Int): Action = 
        if(n > 0) fork(underlying.action) { loop(n-1) }
        else stop
      loop(factor)  
    }
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
