package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent

import _root_.io.dylemma.xml.experimental.Parser.FirstResultStage
import io.dylemma.xml.Result.{Empty, Success}
import io.dylemma.xml.{AsInputStream, Result, XMLEventSource}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._

import scala.concurrent.{Future, Promise}

trait Parser[-Context, +T] { self =>
	def asFlow: Flow[XmlStackState[Context], Result[T], akka.NotUsed]

	def parse[In](
		input: In,
		inputFactory: XMLInputFactory = XMLEventSource.defaultInputFactory
	)(
		implicit asInput: AsInputStream[In],
		anyContext: Any <:< Context,
		materializer: Materializer
	): Future[Result[T]] = {
		val pub = XmlEventPublisher(input, inputFactory)
		val rawFlow = asRawFlow(anyContext)
		val headResultSink = Sink.fromGraph(new FirstResultStage[T])
		pub.via(rawFlow).runWith(headResultSink)
	}

	def asRawFlow(implicit ev: Any <:< Context): Flow[XMLEvent, Result[T], akka.NotUsed] = {
		XmlStackState.scanner(_ => Success(ev(()))) via asFlow
	}

	/** INTERNAL API.
	  * Used by ParserCombination classes after verifying a MostSpecificType
	  * between this parser's `Context` and another parser's `Context.` Since
	  * we know that `C <: Context` if there is a `MostSpecificType[C, Context, _]`
	  * and because `Context` is contravariant, we can just typecast this parser
	  * instance rather than having to perform an extra runtime mapping step.
	  */
	private[xml] def unsafeCastContext[C] = this.asInstanceOf[Parser[C, T]]

	/** Create a new Parser that feeds results from this parser through a
	  * transformation function (`f`).
	  *
	  * @param f A function to transform parser results
	  * @return A new parser whose results have been transformed by `f`
	  */
	def map[U](f: T => U) = Parser.fromFlow(asFlow map (_.map(f)))

	/** Low-level version of `map`, where the raw `Result` values are
	  * passed through `f`.
	  *
	  * @param f A function to transform raw parser results
	  * @return A new parser whose results have been transformed by `f`
	  */
	def mapResult[U](f: Result[T] => Result[U]) = Parser.fromFlow(asFlow map f)

	/** Create a new parser that transforms incoming "context" values with
	  * the given transformation function `f`.
	  *
	  * @param f A function to transform context inputs
	  * @tparam C The type of the context supported by the returned parser
	  * @return A parser that accepts events with context type `C` by passing
	  *         their context values through `f` before feeding them to this parser.
	  */
	def adaptContext[C](f: C => Context): Parser[C, T] = Parser.fromFlow {
		Flow[XmlStackState[C]] map { stackState =>
			val context = stackState.matchedContext.map(f)
			stackState.copy(matchedContext = context)
		} via asFlow
	}

	/** Explicitly provide a Context value to this parser.
	  * The returned parser will ignore any actual context values, using the
	  * provided `context` instead. The returned parser can then be attached
	  * to any context.
	  *
	  * @param context The provided Context value
	  * @return A parser that ignores input contests, using the provided
	  *         `context` instead
	  */
	def inContext(context: Context): Parser[Any, T] = adaptContext(_ => context)
}

object Parser {

	def fromFlow[C, T](flow: Flow[XmlStackState[C], Result[T], akka.NotUsed]): Parser[C, T] = {
		new Parser[C, T]{ def asFlow = flow }
	}

	trait ParserGraphStage[Context, A] extends GraphStage[FlowShape[XmlStackState[Context], Result[A]]] {
		val in: Inlet[XmlStackState[Context]] = Inlet("ParserIn")
		val out: Outlet[Result[A]] = Outlet("ParserOut")
		override val shape = FlowShape(in, out)
	}
	def fromGraphStage[Context, A](stage: ParserGraphStage[Context, A]): Parser[Context, A] = {
		fromFlow(Flow.fromGraph(stage))
	}

	class FirstResultStage[T] extends GraphStageWithMaterializedValue[SinkShape[Result[T]], Future[Result[T]]] {
		val in = Inlet[Result[T]]("firstResult.in")
		val shape: SinkShape[Result[T]] = SinkShape.of(in)
		def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Result[T]]) = {
			val p = Promise[Result[T]]
			val logic = new GraphStageLogic(shape) {
				override def preStart() = pull(in)
				setHandler(in, new InHandler {
					def onPush(): Unit = {
						p.trySuccess(grab(in))
						completeStage()
					}
					override def onUpstreamFinish() = {
						p.trySuccess(Empty)
						completeStage()
					}
					override def onUpstreamFailure(ex: Throwable) = {
						p.tryFailure(ex)
						failStage(ex)
					}
				})
			}
			logic -> p.future
		}
	}

	// TODO: this functionality might be better of in FlowHelpers
	/** GraphStage that will pass each input through the `extract` function, pushing
		* the first `Some` value and ending immediately
		*
		* @param extract
		* @tparam Context
		* @tparam Out
		*/
	class TakeFirstParsingStage[Context, Out](extract: XmlStackState[Context] => Option[Result[Out]])
		extends ParserGraphStage[Context, Out] {

		def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){

			setHandler(in, new InHandler {
				def onPush() = {
					val e = grab(in)
					val value = extract(e)
					if(value.isDefined){
						push(out, value.get)
						completeStage()
					} else {
						pull(in)
					}
				}
				override def onUpstreamFinish() = {
					push(out, Empty)
					completeStage()
				}
			})

			setHandler(out, new OutHandler {
				def onPull() = pull(in)
			})

		}
	}

	/** Creates a new parser which will immediately return the `matchedContext` from
	  * the first event passed to it, then end.
		*
		* @tparam C The parser's context type, as well as its output type
	  * @return A parser that yields its context
	  */
	def forContext[C] = fromGraphStage{
		new TakeFirstParsingStage[C, C]({ stackState => Some(stackState.matchedContext) })
	}

	def forAttribute(name: String): Parser[Any, String] = forAttribute(new QName(name))
	def forAttribute(name: QName): Parser[Any, String] = fromGraphStage{
		new TakeFirstParsingStage[Any, String]({ stackState =>
			val e = stackState.currentEvent
			if(e.isStartElement){
				val elem = e.asStartElement
				val attr = elem.getAttributeByName(name)
				if(attr == null){
					val msg = s"$elem missing mandatory '$name' attribute"
					Some(Result.Error(new IllegalArgumentException(msg)))
				} else {
					Some(Result(attr.getValue))
				}
			} else {
				None
			}
		})
	}

	def forOptionalAttribute(name: String): Parser[Any, Option[String]] = forOptionalAttribute(new QName(name))
	def forOptionalAttribute(name: QName): Parser[Any, Option[String]] = fromGraphStage{
		new TakeFirstParsingStage[Any, Option[String]]({ stackState =>
			val e = stackState.currentEvent
			if(e.isStartElement){
				val elem = e.asStartElement
				val attr = elem.getAttributeByName(name)
				if(attr == null) Some(Success.none)
				else Some(Result(Some(attr.getValue)))
			} else {
				None
			}
		})
	}

	/** A parser that will collect the text from all `Characters` events,
	  * emitting the concatenation of those strings when the stream ends.
	  */
	val forText: Parser[Any, String] = fromGraphStage {
		new ParserGraphStage[Any, String] {
			def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
				val sb = new StringBuilder
				setHandler(in, new InHandler {
					def onPush() = {
						val e = grab(in).currentEvent
						if(e.isCharacters) sb append e.asCharacters.getData
						pull(in)
					}
					override def onUpstreamFinish(): Unit = {
						push(out, Result(sb.result()))
						super.onUpstreamFinish()
					}
				})
				setHandler(out, new OutHandler {
					def onPull() = pull(in)
				})
			}
		}
	}

	def demultiplexed[Context] = new DemultiplexedApply[Context]

	class DemultiplexedApply[Context]{
		def apply[Out](outputs: (Parser[Context, Out], Context => Boolean)*): Parser[Context, Out] = {
			val demuxShape = GraphDSL.create() { implicit b =>
				import GraphDSL.Implicits._

				// Each 'output' will be assigned as an output to the partitioner.
				// Additionally, an extra output will be set up to ignore results
				// that don't match any particular output's context predicate.
				val numOutputs = outputs.size

				// Set up the partition, which will pick the index of the first output
				// whose context predicate matches the current context
				val p = b.add(Partition[XmlStackState[Context]](numOutputs + 1, { state =>
					state.matchedContext match {
						case Success(context) =>
							// Send data to the first output whose context predicate passes.
							// If none pass, send it to the last output, i.e. "ignored".
							val idx = outputs.indexWhere{ o => o._2(context) }
							if(idx == -1) numOutputs else idx
						case _ =>
							// Send non-matched contexts to the last output, i.e. "ignored".
							numOutputs
					}
				}))

				// we still want a single outlet, so fan in the partitioned results
				val fanIn = b.add(Merge[Result[Out]](numOutputs))

				// attach the 'outputs' to the partitioner and the fanIn step
				val outputFlows = for{ (parser, pred) <- outputs } p ~> parser.asFlow ~> fanIn

				// the final 'output' just ignores everything
				p ~> Sink.ignore

				FlowShape(p.in, fanIn.out)
			}

			fromFlow(Flow.fromGraph(demuxShape))
		}
	}

	/** Opt-in DSL for constructing Demultiplexed Parsers.
		*
		* Example:
		* {{{
		*   Parser.demultiplexed[String](
		*     parserA -> { _ == "a" },
		*     parserB -> { _ == "b" },
		*     parserC -> { _ == "c" }
		*   )
		* }}}
		* will be equivalent to
		* {{{
		*   import demuxSyntax._
		*   for {
		*     context <- Demux[String]
		*     a <- parserA if context === "a"
		*     b <- parserB if context === "b"
		*     c <- parserC if context === "c"
		*   } yield a | b | c
		* }}}
		*/
	object demuxSyntax {

		/** DSL starting point.
			* When using the demuxSyntax DSL, always start by using
			* {{{
			*   for {
			*     context <- Demux[MyContextType]
			*     ...
			*   } yield ...
			* }}}
			*/
		object Demux {
			def apply[Context] = new Demux[Context]
		}
		class Demux[Context] {
			def flatMap[Out](f: DemuxContext[Context] => AsPossibleOutputs[Context, Out]) = {
				val outputs = f(new DemuxContext[Context]).asPossibleOutputs.map { po =>
					po.from -> po.cond.p
				}
				demultiplexed[Context](outputs: _*)
			}
		}

		/** DSL starting point for creating DemuxConditions. */
		class DemuxContext[C] {
			/** Creates a new DemuxCondition that compares the context to the given `value`.
				*
				* @param value The value to compare against the context
				* @return A new DemuxCondition that compares the context to the given `value`
				*/
			def ===(value: C) = DemuxCondition[C](_ == value)

			/** Used to create arbitrary `DemuxCondition`s in case `===` is
				* insufficient to express the appropriate conditional.
				*
				* @param p A function that checks if the context meets the condition
				* @return A new DemuxCondition wrapping `p`
				*/
			def check(p: C => Boolean) = DemuxCondition(p)
		}

		/** Wrapper for a `C => Boolean` function that will be called on context
			* values by a generated DemultiplexedParser.
			*
			* @param p The context match function
			* @tparam C The context type
			*/
		case class DemuxCondition[-C](p: C => Boolean)

		// collection wrapper that accumulates outputs for the DSL
		sealed trait AsPossibleOutputs[-Context, +A] {
			def asPossibleOutputs: Seq[PossibleOutput[Context, A]]
			def |[C <: Context, B >: A](next: PossibleOutput[C, B]) = PossibleOutputs(asPossibleOutputs :+ next)
		}
		case class PossibleOutput[-Context, +Out](
			from: Parser[Context, Out],
			cond: DemuxCondition[Context]
		) extends AsPossibleOutputs[Context, Out] {
			def asPossibleOutputs = Seq(this)
		}
		case class PossibleOutputs[-Context, +Out](
			asPossibleOutputs: Seq[PossibleOutput[Context, Out]]
		) extends AsPossibleOutputs[Context, Out]

		// wrapper to handle `... <- someParser if context === someValue`
		case class ParserWithCondition[Context, +A](
			parser: Parser[Context, A],
			condition: DemuxCondition[Context]
		){
			def flatMap[B >: A](f: PossibleOutput[Context, A] => AsPossibleOutputs[Context, B]) = {
				f(PossibleOutput(parser, condition))
			}
			def map[B >: A](f: PossibleOutput[Context, A] => AsPossibleOutputs[Context, B]) = {
				f(PossibleOutput(parser, condition))
			}
		}

		// implicit upgrade to add `withFilter` to Parsers, allowing them to participate in the DSL
		implicit class RichParser[In, Out](parser: Parser[In, Out]) {
			def withFilter[Context <: In](f: Any => DemuxCondition[Context]) = ParserWithCondition(parser, f(null))
		}
	}
}