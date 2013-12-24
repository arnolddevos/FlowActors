package examples.fileprocessor
import au.com.langdale.async.Flow._

trait Problem {
  type Line
  type Document

  implicit val lines = label[Line]
  implicit val docs  = label[Document]
  val reader, parser, balance, writer: Process

  val N: Int // ratio of read speed to write speed

  def graph = reader :- lines :-> parser :- docs :-> balance :- docs :-> writer * N
}

trait Solution extends Problem {

  import scala.io.Source
  import java.io.{File, PrintWriter}
  val inputFileName: String

  type Line = Option[String]
  case class Document(name: String, content: Seq[String])

  val reader = "raw text reader" !: produce {
    def packetize( it: Iterator[String]) = it.map(Some(_)) ++ Iterator(None)

    for(l <- packetize(Source.fromFile(inputFileName).getLines))
      yield l
  } 

  val writer = "document writer" !: consume { doc: Document =>
    val o = new PrintWriter(new File(doc.name))
    o.println(doc.content.mkString("\n"))
    o.close
  }

  val parser = "line by line parser" !: process {

    val Header  = """BEGIN (\w+)""".r
    val Trailer = """END (\d+)""".r

    def start: Action[Unit] = input(lines) {
      case Some(Header(name)) => 
        body(Document(name, Seq()))

      case None => stop(())
    }

    def body(doc: Document): Action[Unit] = input(lines) {
      case Some(Trailer(n)) if n.toInt == doc.content.size => 
        output(docs, doc) { start }

      case Some(text) => 
        body(doc.copy(content=doc.content:+ text))
    }

    start
  }

  val N = 3
  val balance = "output load balancer" !: balancer(docs)
}

object Main {
  def main(args: Array[String]) {
    new Solution {
      val inputFileName = args.headOption getOrElse sys.error("an input file name is required")
      run(graph)
    }
  }
}
