package au.com.langdale
package async

import java.util.concurrent.ForkJoinPool

trait FlowExecutor {
  def execute(work: Runnable)
}

object FlowExecutor {
  trait ForkJoin extends FlowExecutor {
    def execute(work: Runnable) = executor.execute(work)
  
    def concurrentIO = 4
    def hyperthreadsPerProcessor = 2
    def parallelism =  Runtime.getRuntime.availableProcessors * hyperthreadsPerProcessor + concurrentIO
    import ForkJoinPool.defaultForkJoinWorkerThreadFactory
  
    object uncaughtHandler extends Thread.UncaughtExceptionHandler {
      def uncaughtException(t: Thread, e: Throwable) {
        println("Exception in FJP: " + e)
      }
    }
  
    object executor extends ForkJoinPool(
      parallelism, 
      defaultForkJoinWorkerThreadFactory, 
      uncaughtHandler, 
      false)
      
    def executorStats(): String = {
      import executor._
      
      val items = 
        "async mode" -> getAsyncMode ::
        "parallelism" -> getParallelism ::
        "pool size" -> getPoolSize ::
        "active threads" -> getActiveThreadCount ::
        "running threads" -> getRunningThreadCount ::
        "queued submissions" -> getQueuedSubmissionCount ::
        "queued tasks" -> getQueuedTaskCount ::
        "steals" -> getStealCount ::
        "quiescent" -> isQuiescent ::
        "shutdown" -> isShutdown ::
        "terminating" -> isTerminating ::
        "terminated" -> isTerminated ::
        Nil
      
      val lines = for((desc, value) <- items)
        yield desc + ": " + value
        
      lines.mkString("//", "\n//", "")  
    }

  }
}