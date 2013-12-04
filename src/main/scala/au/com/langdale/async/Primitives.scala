package au.com.langdale
package async

import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import scala.annotation.tailrec
import scala.util.control.NonFatal

trait Primitives { this: Trace with Executor =>
  
  class CheckedVar[V](v0: V) {
    private val vr = new AtomicReference[V](v0)
    
    def mutate(f: V => V): V = {
      @tailrec
      def attempt(): V = {
        val v1 = vr.get
        if( vr.compareAndSet(v1, f(v1))) v1 else attempt()
      }
      attempt()
    }
  }

  private val barrier = List((t: Task) => ())
  private [async] final val externalTask = task(0, 0)  
  private val actorCount = new AtomicInteger
  
  trait PrimitiveActor { actor =>
    private val taskCount = new AtomicInteger
    
    val actorId = actorCount.incrementAndGet
    override def toString() = "Site(" + actorId + ")"
    
    private val waiters = new CheckedVar[List[Task => Unit]](Nil)
    
    def enqueue(k: Task => Unit)(implicit t: Task): Unit = {
      
      def run(k: Task => Unit)(implicit t: Task) {
        try {
          k(t)
        } 
        catch {
          case NonFatal(e) => trace(e)
        }
      }
      
      def bar(k: Task => Unit) = waiters.mutate {
        case Nil => barrier
        case ks => k :: ks
      } == Nil
      
      def unbar = waiters.mutate {
        case b if b eq barrier => Nil
        case ks => barrier 
      }  
        
      @tailrec
      def service(n0: Int, ks0: List[Task => Unit])(implicit t: Task): Int = {
        val n = n0 + ks0.length -1
        for( k <- ks0.reverse )
          run(k)
        val ks = unbar
        if( ks ne barrier) 
          service(n, ks)  
        else
          n
      }
      
      if( bar(k) ) {
        run(k)
        val ks = unbar
        if( ks ne barrier)
          spawn { implicit t => 
            trace(actor, "background", "start")
            val n = service(0, ks) 
            trace(actor, "background", "end", n, "actions")
          }
      }
    }
    
    def request( k: Task => Unit ): Unit = enqueue(k)(externalTask)
    
    def spawn[W]( k: Task => Unit )(implicit t: Task): Task = { 
      val child = task(actorId, taskCount.incrementAndGet)
      trace("forks", child)
      execute( new Runnable {
        def run() {
          implicit def t = child
          trace("starts")
          k(t)
          trace("completed")
        }
      })
      child
    }
  }
}
