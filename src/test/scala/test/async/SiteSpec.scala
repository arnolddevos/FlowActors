import org.scalatest._
import au.com.langdale.async.Flow.Debug._
import au.com.langdale.async.Flow.{Debug => Flow}

abstract class Testing extends FlatSpec with Matchers with concurrent.AsyncAssertions {

  def runProcess[U](step: => Action[U]) = {
    val s = createSite
    s.run(process(step))
    s
  }

  def done( w: Waiter) = {  
    w.dismiss
    stop(())
  }
}

class SitesSpec extends Testing {

  "A site" should "run the action of a process" in {
    val w = new Waiter
    runProcess { done(w) }
    w.await
  }

  "The fork operation" should "run two continuations" in {
    val w = new Waiter
    runProcess {
      fork(process(done(w)))(done(w))
    }
    w.await(dismissals(2))
  }

  "The connect, output and input operations" should "conduct a message between sites" in {
    val w = new Waiter
    val channel = label[String]
    val message = "hello, can you hear me?"
    
    val a = runProcess {
      output(channel, message) { done(w) }
    }

    val b = runProcess {
      input(channel) { s => 
        w { s shouldBe message }
        done(w)
      }
    }

    a.connect(channel, b, channel)
    w.await(dismissals(2))
  }

  it should "preserve message order w.r.t the sender" in {
    val w = new Waiter
    val channel = label[String]
    val m1 = "hello, can you hear me?"
    val m2 = "are you still there?"
    val m3 = "the last word"
    
    val a = runProcess {
      output(channel, m1) { 
        output(channel, m2) {
          output(channel, m3) {
            done(w) 
          }
        }
      }
    }

    val b = runProcess {
      input(channel) { s1 => 
        input(channel) { s2 =>
          input(channel) { s3 =>
            w { s1 shouldBe m1 }
            w { s2 shouldBe m2 }
            w { s3 shouldBe m3 }
            done(w)
          }
        }
      }
    }

    a.connect(channel, b, channel)
    w.await(dismissals(2))
  }

  "The orElse combinator" should "combine messages in sum style" in {
    val w = new Waiter
    val chan1, chan2 = label[String]
    val m1 = "hello, can you hear me?"
    val m2 = "what about now?"
    
    val a = runProcess {
     output(chan2, m2) { 
        output(chan1, m1) {
          output(chan1, m1) {
            output(chan2, m2) { 
              done(w) 
            }
          }
        }
      }
    }

    val b = runProcess {
      def loop: Action[Nothing] = 
        input(chan1) { s => 
          w { s shouldBe m1 }
          w.dismiss
          loop
        } orElse
        input(chan2) { s => 
          w { s shouldBe m2 }
          w.dismiss
          loop
        }
      loop
    }

    a.connect(chan1, b, chan1)
    a.connect(chan2, b, chan2)

    w.await(dismissals(4))
  }

  "Nested input operations on different channels" should "combine messages in product style" in {
    val w = new Waiter
    val chan1, chan2 = label[String]
    val m1 = "hello, can you hear me?"
    val m2 = "what about now?"
    
    val a = runProcess {
     output(chan2, m2) { 
        output(chan1, m1) {
          output(chan1, m1) {
            output(chan2, m2) { 
              done(w) 
            }
          }
        }
      }
    }

    val b = runProcess {
      def loop: Action[Nothing] = 
        input(chan1) { s1 => 
          input(chan2) { s2 => 
            w { s1 shouldBe m1 }
            w { s2 shouldBe m2 }
            w.dismiss
            loop
          }
        }
      loop
    }

    a.connect(chan1, b, chan1)
    a.connect(chan2, b, chan2)
    w.await(dismissals(3))
  }

  "The after operator" should "delay a continuation" in {
    val w = new Waiter
    val delay = 75l // milliseconds
    import System.currentTimeMillis

    runProcess {
      val t0 = currentTimeMillis
      Flow.after(delay) {
        w {currentTimeMillis - t0 shouldBe delay +- 20l}
        done(w)      
      }
    }

    w.await
  }

  it should "timeout an input operation" in {
    val w = new Waiter
    val delay = 75l // milliseconds
    import System.currentTimeMillis
    val channel = label[String]

    runProcess {
      val t0 = currentTimeMillis
      input(channel) { s => 
        w { fail("unexpected message received") }
        stop(())
      } orElse
      Flow.after(delay) {
        w {currentTimeMillis - t0 shouldBe delay +- 20l}
        done(w)      
      }
    }

    w.await
  }

  "The fanout operation" should "count the number of connections to an output port" in {
    val w = new Waiter
    val channel = label[String]
    val N = 3
    
    val a, b = createSite

    for( i <- 0 until N )
      a.connect(channel, b, channel, i)

    a.fanout(channel) shouldBe N

    a run process {
      fanout(channel) { n => 
        w { n shouldBe N }
        done(w) 
      }
    }
    
    w.await
  }

}
