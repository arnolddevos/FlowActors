package au.com.langdale
package async

import scala.language.higherKinds

/**
 * Labels are used to identify an input or output port
 * at an execution Site, on a Process or in a graph.
 * 
 * When marked implicit, a label identifies a parameter
 * or result of a function lifted into a process.
 *
 * Each label is distinct and carries a type parameter.
 */
trait Labels {

  /** A label for an input port */
  type InputPort[-Message]

  /** A label for an output port */
  type OutputPort[+Message]

  /** A generic label */
  type Label[Message] <: OutputPort[Message] with InputPort[Message]

  /** create a fresh label with the given type */
  def label[Message]: Label[Message]

  /** create a fresh label and attach a description to it */
  def label[Message]( descr: String ): Label[Message]
}

object Labels {
  trait Basic extends Labels {
    trait OutputPort[+Message]
    trait InputPort[-Message]
    class Label[Message] extends OutputPort[Message] with InputPort[Message]
    def label[Message] = new Label[Message]
    def label[Message]( descr: String ) = new Label[Message] { override def toString = descr }
  }
}
