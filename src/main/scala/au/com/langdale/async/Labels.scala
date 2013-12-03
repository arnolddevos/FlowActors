package au.com.langdale
package async

import scala.language.higherKinds

/**
 * Supply Labels which can be used to identify an input port
 * or an output port of an execution Site, Process or graph Node.
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
}

object Labels {
  trait Basic extends Labels {
    trait OutputPort[+Message]
    trait InputPort[-Message]
    class Label[Message] extends OutputPort[Message] with InputPort[Message]
    def label[Message] = new Label[Message]
  }
}
