package examples.fileprocessor
import au.com.langdale.async.Flow._

trait Problem {
  type Line
  type Document

  implicit val lines = label[Line]
  implicit val docs  = label[Document]
  val reader, parser, writer: Process

  val graph = reader :- lines :-> parser :- docs :-> writer
}

trait Solution extends Problem {

  import scala.io.Source
  import java.io.{File, PrintWriter}
  val inputFileName: String

  type Line = Option[String]
  case class Document(name: String, content: Offer[Line])

  val reader = produce {
    def packetize( it: Iterator[String]) = it.map(Some(_)) ++ Iterator(None)
    for(l <- packetize(Source.fromFile(inputFileName).getLines))
    yield l
  } 

  val writer = transform[Document => Process] { doc =>
    val o = new PrintWriter(new File(doc.name))

    consumeFrom(doc.content.port) {
      case Some(l) => o.println(l)
      case None    => complete(doc.content); o.close
    }
  }

  val parser = "line by line parser" !: process {

    val Header  = "".r
    val Trailer = "".r

    def start: Action = input(lines) {
      case Some(Header(name)) => 
        propose[Line] { offer =>
          val doc = Document(name, offer)
          output(docs, doc) { body(doc, 0) }
        }
      case None => stop
    }

    def body(doc: Document, count: Int): Action = input(lines) {
      case Some(Trailer(n)) if n.toInt == count => output(doc.content.port, None) { start }
      case line @ Some(_) => output(doc.content.port, line) { body(doc, count+1) }
    }

    start
  }
}

object Main {
  def main(args: Array[String]) {
    new Solution {
      val inputFileName = args.headOption getOrElse sys.error("an input file name is required")
      run(graph)
    }
  }
}
