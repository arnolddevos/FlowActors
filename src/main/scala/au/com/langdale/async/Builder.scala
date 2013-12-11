package au.com.langdale
package async

/**
 * Given a graph of Processes construct a graph of Sites 
 * and run each process at its corresponding site.
 */
trait Builder extends Flow with Processes with Graphs {
  type Node = Process

  type Arc = Map[Process, Site] => Map[Process, Site]

  def arc[Message](node1: Process, port1: OutputPort[Message], port2: InputPort[Message], node2: Process): Arc = {
    sites0 => 
      val (sites1, site1) = update(sites0)(node1, createSite(node1))
      val (sites2, site2) = update(sites1)(node2, createSite(node2))
      site1.connect(port1, site2, port2, site1.fanout(port1))
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

  def run(graph: Graph, supervisor: Process = defaultSupervisor): Map[Process, Site] = {
    val sites0 = Map[Process, Site]()
    val sites1 = graph.arcs.foldLeft(sites0)((sitesn, arcn) => arcn(sitesn))
    val superSite = createSite(supervisor)
    for( site1 <- sites1.values ) {
      site1.connect(errors, superSite, errors)
      site1.run()
    }
    superSite.run()
    sites1 updated (supervisor, superSite)
  }
}
