package au.com.langdale
package async

import Flow._
import math._
import java.io.FileOutputStream
import java.util.concurrent.ForkJoinPool

object FlowExamples {
    
  def main(args: Array[String]) {  
    Console.withOut(new FileOutputStream("trace.dot")) {
      example
    }
  }
  
  def example {
    object A extends Actor {
      
      val data = Input[Int]()
      val results = Output[Double]()
      
      def main: Action = data react { i =>
        
        results(if( i < 100) log10(100.0-i) else throw new IllegalArgumentException("Value out of range: " + i)) {
          main
        }
      }
      
      run(main, 1)
    }
  
    object B extends Actor {
      
      val data = Output[Int]()
      
      def main(i: Int): Action = {
        println("// generating " + i)
        data(i) {
          if(i < 400)
            main(i+1)
          else {
            stop
          }
        }
      }
      
      run(main(0), 1)
    }
    
    object C extends Actor {
      val data = Input[Any]()
      
      def main: Action = data react {
        t => println("// " + t)
        main
      }
      
      run(main)
    }
    
    println("digraph {")
  
    // B.data --> Logger.data
    B.data --> A.data
    Thread.sleep(5)
    
    B.error --> C.data
    A.error --> C.data
    A.results --> C.data
    Thread.sleep(3000l)
    
    println(executorStats())
    println("}")
  }
}
