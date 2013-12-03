package au.com.langdale
package async

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.elidable

trait Trace {
  
  type Task 

  def task(major: Int, minor: Int): Task
  def trace(x: Any*)(implicit t: Task)
}

object Trace {
  
  trait Noop extends Trace {
    class Task
    def task(major: Int, minor: Int) = new Task
    @elidable(0)
    def trace(x: Any*)(implicit t: Task) {}
  }
  
  trait SimpleTaskId { this: Trace =>
    case class Task(majorId: Int, minorId: Int) 
    def task(major: Int, minor: Int) = Task(major, minor) 

  }
  
  trait Flat extends Trace with SimpleTaskId {
    def trace(x: Any*)(implicit t: Task) = println(t.toString + ": " + x.mkString(" "))
  }
  
  trait Graphviz extends Trace with SimpleTaskId {

    private val logNum = new AtomicInteger
    
    def trace(xs: Any*)(implicit t: Task) = {
      
      def node(t: Task) = t.majorId + "." + t.minorId
      
      val n0 = node(t)
      
      def note(atts: String = "", anchor: Boolean = true) = {
        val a = logNum.incrementAndGet
        (if(anchor) edge(n0, a.toString) + "; " else "") + 
        a + attr("label", xs.mkString(" ")) + attr("shape", "box") + atts + ";" +
        (if(a > 1) edge((a-1).toString, a.toString) + attr("style", "dashed") else "")
      }
      
      
      def attr(n: String, v: Any) = " [" + n + "=\"" + v + "\"]" 
      
      def edge(n1: String, n2: String) = n1 + " -> " + n2
      
      val m = xs match {
        case Seq("forks", t1: Task) if t.majorId == 0 => "// " + t1 + "external trigger"
        case Seq("forks", t1: Task) => edge(n0, node(t1))
        case Seq(s1, "=>", s2) if t.majorId == 0 => note(anchor=false) 
        case Seq(s1, "=>", s2)  => note() 
        case Seq("error", _ @_*) => note(atts=attr("fontcolor", "red"))
        case Seq(_, "background", _ @_*) => note()
        case _ => "// " + t + ": " + xs.mkString(" ")
      }
      
      println(m)
    }
  }
}
