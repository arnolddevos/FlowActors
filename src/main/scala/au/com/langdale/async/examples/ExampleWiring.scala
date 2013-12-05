package au.com.langdale
package async
package examples

trait ExampleWiring { this: GraphDSL =>

  type Raw
  type MeterData
  type Canonical	
  type Error
  type ProcError <: Error

  def node: Node
  def label[Message]: Label[Message]

  val meterleech, meterproc, receiver, convertor, 
      logger, processor, dlq, alternative, errorFilter = node

  val raw    = label[Raw]       
  val canon  = label[Canonical] 
  val errors = label[ProcError] 
  val meters = label[MeterData]
  val deadLetters = label[Error]

  val graph = (

      (
        meterleech :- meters :-> meterproc &
        receiver :- raw :-> (convertor & alternative)
      ) 
      :- canon :-> 
      (
        logger & 
        processor -: 
        ( 
          errors ->: errorFilter -: errors/deadLetters ->: dlq & 
          errors ->: logger
        )
      )
  )


}

trait GraphPrint extends GraphDSL {
  import scala.language.existentials

  var nodeCount = 0
  case class Node(i: Int)
  def node = Node({nodeCount += 1; nodeCount})

  var outputCount = 0
  trait OutputPort[+Message]
  case class Output[+Message](i: Int) extends OutputPort[Message]
  def output[Message] = Output[Message]({outputCount += 1; outputCount})

  var inputCount = 0
  trait InputPort[-Message]
  case class Input[-Message](i: Int) extends InputPort[Message]
  def input[Message] = Input[Message]({inputCount += 1; inputCount})

  var wireCount = 0
  case class Label[Message](i: Int) extends OutputPort[Message] with InputPort[Message]
  def label[Message] = Label[Message]({wireCount += 1; wireCount})
  def label[Message](descr: String) = label[Message]

  case class Arc(node1: Node, port1: OutputPort[_], port2: InputPort[_], node2: Node)
  def arc[Message](node1: Node, port1: OutputPort[Message], port2: InputPort[Message], node2: Node) = Arc(node1, port1, port2, node2)

  def show(graph: Graph) = 
  	for( a <- graph.arcs.toSeq.map(_.toString).sorted )
  		println(a)
}

object ExamplePrint extends App with ExampleWiring with GraphPrint {
  show(graph)
}
