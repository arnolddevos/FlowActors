package au.com.langdale
package async

/**
 * A simple continuation monad.
 */
trait Continuation[R, +A] { self =>

  def run(k: A => R): R

  def map[B](f: A => B) = new Continuation[R, B] {
    def run(k: B => R) = self.run(k compose f)
  }

  def flatMap[B](f: A => Continuation[R, B]) = new Continuation[R, B] {
    def run(k: B => R) = self.run(a => f(a).run(k))
  }

  def apply[B](f: A => Continuation[R, B]) = flatMap(f)
}

object Continuation {

  def continuation[R, A](g: (A => R) => R) = new Continuation[R, A] {
    def run(f: A => R) = g(f)
  }

  def constant[R, A](a: A) =  new Continuation[R, A] {
    def run(k: A => R) = k(a)
  }
}

/**
 * Use Flow the monadic way.  Example:

  implicit val strings = label[String]

  def passOneMessage[U](u: U) = for {
    s <- inputC[U, String]
    _ <- outputC[U, String](s)
  }
  yield stop(u)

  val p = processC(passOneMessage(()))


 */
trait Continuations { this: Flow with Processes =>
  import Continuation._

  def inputC[U, Message](implicit port: InputPort[Message]): Continuation[Action[U], Message] = {
    val f: (Message => Action[U]) => Action[U] = input(port) _
    continuation(f)
  }

  def outputC[U, Message](m: Message)(implicit port: OutputPort[Message]): Continuation[Action[U], Unit] = {
    val f: (Unit => Action[U]) => Action[U] = step => output(port, m)(step(()))
    continuation(f)
  }

  def afterC[U](millis: Long):  Continuation[Action[U], Unit] = {
    val f: (Unit => Action[U]) => Action[U] = step => after(millis)(step(()))
    continuation(f)
  }

  def processC[U]( c: Continuation[Action[U], Action[U]]) = process {
    c.run(identity)
  }
}
