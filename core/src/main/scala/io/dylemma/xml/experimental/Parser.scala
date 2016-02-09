package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.XMLEvent

import akka.stream.{Attributes, Outlet, Inlet, FlowShape}
import akka.stream.scaladsl.{GraphDSL, Flow}
import akka.stream.stage._
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Success, Error, Empty}

import scala.collection.generic.CanBuildFrom

trait Parser[-Context, +T] { self =>
	def asFlow: Flow[XmlStackState[Context], Result[T], akka.NotUsed]

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

	// TODO: this functionality might be better of in FlowHelpers
	/** GraphStage that will pass each input through the `extract` function, pushing
		* the first `Some` value and ending immediately
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

	// TODO: this functionality might be better of in FlowHelpers
	class BuilderStage[Context, Out, Chunk]
		(extractChunk: XmlStackState[Context] => Option[Seq[Chunk]])
		(implicit cbf: CanBuildFrom[Nothing, Chunk, Out])
	extends ParserGraphStage[Context, Out] {
		def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){
			val builder = cbf()

			setHandler(in, new InHandler {
				def onPush() = {
					val e = grab(in)
					val chunkOpt = extractChunk(e)
					if(chunkOpt.isDefined) builder ++= chunkOpt.get
					pull(in)
				}
				override def onUpstreamFinish() = {
					val result = Result(builder.result())
					push(out, result)
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
	val forText: Parser[Any, String] = fromGraphStage{
		new BuilderStage[Any, String, Char]({ stackState =>
			val e = stackState.currentEvent
			if(e.isCharacters) Some(e.asCharacters.getData)
			else None
		})
	}
}