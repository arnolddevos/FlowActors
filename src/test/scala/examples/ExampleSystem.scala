package examples
import au.com.langdale.async._


import math._
import java.io.FileOutputStream

object ExampleSystem extends Processes with Builder with GraphDSL with Labels.Basic with FlowImpl with Executor.ForkJoin with Trace.Graphviz {
  
  def main(args: Array[String]) {  
    Console.withOut(new FileOutputStream("trace.dot")) {
      example
    }
  }
  
  def example {
    val data = label[Int]
    val results = label[Double]
    val logging = label[Any]

    object A extends Process {
      def description = "Convert Ints to Doubles"

      def action = loop

      def loop: Action = input(data) { i =>

        def converted = 
          if( i < 100) log10(100.0-i) 
          else throw new IllegalArgumentException("Value out of range: " + i)
        
        output(results, converted) {
          loop
        }
      }
    }
  
    object B extends Process {
      def description = "generate a stream of Ints"
     
      def action = loop(0)

      def loop(i: Int): Action = {
        println("// generating " + i)
        output(data, i) {
          if(i < 400)
            loop(i+1)
          else {
            stop
          }
        }
      }
    }
    
    object C extends Process {

      def description = "prints what is sent on logging"

      def action = loop

      def loop: Action = input(logging) {
        t => println("// " + t)
        loop
      }
    }

    val graph = 
      B :-data:-> A :-results/logging:-> C & 
      (A & B) :-errors/logging:-> C 
    
    println("digraph {")
    run(graph)
    Thread.sleep(3000l)
    println(executorStats())
    println("}")
  }
}
