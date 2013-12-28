package examples
import au.com.langdale.async._

object ContinuationsExample {
  import Flow._

  implicit val strings = label[String]

  def passOneMessage[U](u: U) = for {
    s <- inputC[U, String]
    _ <- outputC[U, String](s)
  }
  yield stop(u)

  def passOneMessageAndThen = for {
    s <- inputC[Int, String]
    _ <- outputC[Int, String](s)
  }
  yield s.length

  def passTwoMessages = for {
    l1 <- passOneMessageAndThen
    l2 <- passOneMessageAndThen
  }
  yield stop(l1+l2) // exit value is the sum of the message lengths

  val p1 = processC(passOneMessage(()))
  val p2 = processC(passTwoMessages)

}
