package examples
import au.com.langdale.async._
import Flow._

object ContinuationsExample {

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

object AccumulatorMonadic {

  val increment, accumulation = label[Int]
  val accumulator = processC(loop(0))

  def loop(value: Int): Conclusion[Nothing] =
    for {
      _      <- outputC[Nothing, Int](value)(accumulation)
      amount <- inputC[Nothing, Int](increment)
      action <- loop(value + amount)
    }
    yield action
}
