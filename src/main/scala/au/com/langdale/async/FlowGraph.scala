package au.com.langdale
package async

import scala.language.higherKinds

trait GraphRepr {

  type Node 

  /** A label for an input port */
  type InputPort[-Message]

  /** A label for an output port */
  type OutputPort[+Message]

  type Label[Message] <: OutputPort[Message] with InputPort[Message]

  type Arc
  def arc[Message](node1: Node, port1: OutputPort[Message], port2: InputPort[Message], node2: Node): Arc

  case class Graph( arcs: Set[Arc], heads: Set[Node], tails: Set[Node]) 
  case class LeftProject( arms: List[(Graph, Wiring)] ) 
  case class RightProject( arms: List[(Wiring, Graph)] ) 

  sealed trait Wiring
  sealed trait SingleWiring extends Wiring
  case class Direct[Message](label: Label[Message] ) extends SingleWiring 
  case class Splice[Message]( output: OutputPort[Message], input: InputPort[Message]) extends SingleWiring
  case class Bundle( wirings: Set[SingleWiring]) extends Wiring

  def decompose( wiring: Wiring): Set[SingleWiring] = wiring match {
    case Bundle( wirings ) => wirings
    case single: SingleWiring => Set(single)
  }

  def merge(graph: Graph, other: Graph): Graph = { import graph._
    Graph( arcs ++ other.arcs, heads ++ other.heads, tails ++ other.tails )
  }

  def connect(graph: Graph, wiring: Wiring, target: Graph): Graph = { 
    val arcs = for {
      single <- decompose(wiring)
      node1 <- graph.tails
      node2 <- target.heads
    }
    yield single match {
      case Direct( label ) => arc(node1, label, label, node2)
      case Splice( output, input ) => arc(node1, output, input, node2)
    }
    Graph( graph.arcs ++ target.arcs ++ arcs, graph.heads, target.tails)
  }

  def connectLeft( project: LeftProject, target: Graph): Graph = {
    val graphs = 
      for { (graph, wiring) <- project.arms }
        yield connect(graph, wiring, target)
    graphs.reduce(merge)
  }

  def connectRight( graph: Graph, project: RightProject): Graph = {
    val graphs = 
      for { (wiring, target) <- project.arms }
        yield connect(graph, wiring, target)
    graphs.reduce(merge)
  }
}

trait GraphDSL extends GraphRepr {

  implicit class GraphOps( val graph: Graph) {
    def :-( rhs: WiringOps) = LeftProject(List((graph, rhs.wiring)))
    def ->:( lhs: WiringOps ) =  RightProject( List((lhs.wiring, graph)) )
    def &( other: GraphOps): Graph = merge(graph, other.graph)
  }

  implicit class NodeOps( node: Node ) 
    extends GraphOps(Graph( Set(), Set(node), Set(node)))

  implicit class LeftProjectOps( project: LeftProject) {  
    def :->(rhs: GraphOps): Graph = connectLeft(project, rhs.graph)
    def &( other: LeftProject) = LeftProject( project.arms ++ other.arms )
  }

  implicit class RightProjectOps( project: RightProject) {  
    def -:( lhs: GraphOps) = connectRight(lhs.graph, project)
    def &( other: RightProject) = RightProject( project.arms ++ other.arms )
  }

  implicit class WiringOps( val wiring: Wiring) {
    def +( other: WiringOps ) = Bundle( decompose(wiring) ++ decompose(other.wiring) )
  }

  implicit class LabelOps[Message](label: Label[Message]) 
    extends WiringOps(Direct(label))

  implicit class OutputOps[Message](output: OutputPort[Message]) {
    def /( input: InputPort[Message]) = Splice( output, input)
  }
}

trait FlowGraph extends Flow with GraphDSL {
  type Node = Process

  /** A base trait for a process to execute at a Site */
  trait Process {
    override def toString = "Process("+description+")"
    def description: String
    def action: Action 
  }

  def action(process: Process) = process.action
  val supervisor = label[(Site, Throwable)]
  def label[Message]: Label[Message]
  def run(graph: Graph): Map[Process, Site]
}

trait FlowGraphImpl { this: FlowGraph =>

  trait OutputPort[+Message]
  trait InputPort[-Message]
  class Label[Message] extends OutputPort[Message] with InputPort[Message]
  type Arc = Map[Process, Site] => Map[Process, Site]

  def label[Message] = new Label[Message]

  def arc[Message](node1: Process, port1: OutputPort[Message], port2: InputPort[Message], node2: Process): Arc = {
    sites0 => 
      val (sites1, site1) = update(sites0)(node1, createSite(node1))
      val (sites2, site2) = update(sites1)(node2, createSite(node2))
      site1.connect(port1, site2, port2)
      sites2
  }

  private def update[K, V]( underlying: Map[K, V])(k :K, v: => V): (Map[K, V], V) = {
    if( underlying contains k)
      (underlying, underlying(k))
    else {
      val v1 = v
      (underlying updated (k, v1), v1)
    }
  }

  def run(graph: Graph): Map[Process, Site] = {
    val sites0 = Map[Process, Site]()
    val sites1 = graph.arcs.foldLeft(sites0)((sitesn, arcn) => arcn(sitesn))
    for( site1 <- sites1.values ) site1.run()
    sites1
  }
}
