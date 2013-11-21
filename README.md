# Flowlib: Another Scala Dataflow Library

Important Caveats:
: This an experimental library for now.   I hope to have something well-proven soon.  
: This page is in draft form until I test my examples and post more complete example code.
: The source is [on github](https://github.com/arnolddevos/FlowActors) but the repository will likely be renamed to remove the reference to actors.

_Flowlib_ is a compact library for asynchronous programming within a single JVM.

* Compared to futures and promises, flowlib has a message passing model and allows arbitrary message processing graphs. There is a DSL to describe these graphs and operators to split and join message flows in sum or product style.  The degree of concurrency is tune-able at each processing node.

* Compared to Akka actors, message flows are statically typed and many of the details of flow control that would be manually programmed are automatic. On the other hand, flowlib is confined to a single JVM instance.

* Compared to Spark, Storm or Akka clusters, flowlib is for smaller asynchronous systems.  There are many of these! 

* Compared to Functional Reactive Programming, there is no global synchronization of messages flows (signals and behaviours in FRP) nor any other glitch suppression strategy.  

## Without Further Ado: A Graph

Here is a toy processing graph in the flowlib DSL:

```scala
import au.com.langdale.async.Flow._

trait SampleGraph extends SampleDecls { 

  val N = 4
  val M = 2

  def graph =
    InitialData :- urls :-> Fetcher*N :- raw :-> Splitter -: ( 
  	  urls ->: Dedup -: urls ->: Fetcher*N & 
  	  text ->: Filer*M -: metrics ->: Reporter ) &
    (Fetcher & Splitter & Dedup & Filer & Reporter) :- supervisor :-> Supervisor
}
```

Uppercase identifiers such as `Fetcher` by convention are processes.  Lowercase identifiers such as `raw` are labels for message flows.

The connecting operators `:-` and `:->` respectively attach a flow label to a process forming a _projection_ and then connect it to a target process. 

These have right associative equivalents `->:` and `-:` which are useful to express fan-out as opposed to fan-in sub-graphs. 

The `&` operator combines graphs or projections. More about the representation of graphs and projections later.

The `*` operator multiplies a process. It indicates how many parallel instances should be executed.

We left out the declarations of the identifiers.  Let's put them in a separate trait without committing to the types just yet.

```scala
trait SampleDecls { 
  type Text
  type Address
  type Metric 
  val raw, text = label[Text]
  val urls = label[Address]
  val metrics = label[Metric]
  val Fetcher, Splitter, Dedup, Filer, Reporter, Supervisor: Process
}
```

## Processes

On to the definition of processes. Lets take `Dedup` as an example:

```scala
trait SampleProcesses  { 
  def dedup[Message](flow: Label[Message]) = new Process {

    def description = "remove duplicate messages"
  
    def action = loop(Set.empty)
  
    private def loop(seen: Set[Message]): Action =
      input(flow) { message => 
      	if(seen contains message) loop(seen)
      	else output(flow, message) { loop(seen + message) }
      }
  }

  // more process definitions here ...
}
```

This defines a `dedup` method that will create a process with a given message type and flow label. 
The parameters make `dedup` potentially usable in different positions within a graph or in different graphs.

The process is constructed in continuation passing style as follows:

* The `action` member is the process entry point.  

* The `input` method returns an `Action` that will
be dispatched when a message is available on the 
port labelled `flow`. 

* The function `message => ...` is a continuation 
that is invoked when the `input` action is dispatched. 
It returns a new `Action` to be dispatched.

* The `output` method returns an `Action` that will be 
dispatched when the `message` can be delivered on the
output port labelled `flow`.

* The passed block is a continuation that is 
invoked when the `output` action is dispatched.

## A Note About Process State

The dedup process must keep track of the messages already seen
which it does using an immutable `Set[Message]`.  
Successive versions of this set are passed from continuation
to continuation.

It might be tempting to use a mutable set here and make it
a member of `Process`.  That would be a common actor 
programming style but it is not suitable for processes.

The library assumes `Process` is immutable - it may not
have `var` members or mutable members.  Among other things, 
this enables the `*` operator and allows a given graph to 
be run more than once.

## Putting it Together

To complete the example we need to bring the graph and
the process definitions together:

```scala
object Sample extends SampleGraph with SampleProcesses  {

  type Address = java.net.URI
  val Dedup = dedup(urls)

  // commit the remaining types and processes here ...

  val procmap = run(graph)

  println(s"Started ${procmap.size} processes!")
}
```

This fills in the `Address` type and creates a `Dedup` process
whose input and output will be given the `urls` label. 
(The remaining types and processes are omitted for brevity.)

The `run(graph)` method puts everything in motion.  First, a network of _sites_ 
connected by communication channels is created that mirrors the passed graph. 
Then the corresponding process from the graph is executed at each site.  

The run method returns a map of processes to the sites at which they are executing.   
