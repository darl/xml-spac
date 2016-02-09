package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.XMLEvent

import akka.stream.scaladsl.{GraphDSL, Flow}
import akka.stream.stage.{SyncDirective, Context, PushPullStage}
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Success, Error, Empty}

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

	trait XmlHandlingStage[C, T] extends PushPullStage[XmlStackState[C], Result[T]]

	trait PushEmptyOnFinish[C, T] extends XmlHandlingStage[C, T]{
		def onPull(ctx: Context[Result[T]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(Empty)
			else ctx.pull()
		}
		override def onUpstreamFinish(ctx: Context[Result[T]]) = ctx.absorbTermination()
	}

	def fromFlow[C, T](flow: Flow[XmlStackState[C], Result[T], akka.NotUsed]): Parser[C, T] = {
		new Parser[C, T]{ def asFlow = flow }
	}

	/** Convenience function to create Parsers based on a PushPullStage factory function.
	  *
	  * @param makeStage a function that returns a new PushPullStage instance
	  * @tparam C The parser's context type
	  * @tparam T The parser's output type
	  * @return A new parser that will use the stage returned by `makeStage`
	  */
	def fromHandlingStage[C, T](makeStage: () => XmlHandlingStage[C, T]): Parser[C, T] = {
		new Parser[C, T] {
			def asFlow = Flow[XmlStackState[C]].transform(makeStage)
		}
	}

	/** Creates a new parser which will immediately return the `matchedContext` from
	  * the first event passed to it, then end.
	  * @tparam C The parser's context type, as well as its output type
	  * @return A parser that yields its context
	  */
	def forContext[C] = fromHandlingStage{ () =>
		new XmlHandlingStage[C, C] with PushEmptyOnFinish[C, C]{
			def onPush(elem: XmlStackState[C], ctx: Context[Result[C]]) = ctx.pushAndFinish(elem.matchedContext)
		}
	}

	def forAttribute(name: String): Parser[Any, String] = forAttribute(new QName(name))
	def forAttribute(name: QName): Parser[Any, String] = fromHandlingStage{ () =>
		new XmlHandlingStage[Any, String] {
			def onPush(s: XmlStackState[Any], ctx: Context[Result[String]]) = {
				// the first start element *must* have the specified attribute
				if(s.currentEvent.isStartElement){
					val elem = s.currentEvent.asStartElement
					val attr = elem.getAttributeByName(name)
					if(attr == null){
						val msg = s"$elem missing mandatory '$name' attribute"
						ctx.pushAndFinish(Error(new IllegalArgumentException(msg)))
					} else {
						ctx.pushAndFinish(Result(attr.getValue))
					}
				} else {
					ctx.pull()
				}
			}
			def onPull(ctx: Context[Result[String]]) = {
				if(ctx.isFinishing) ctx.pushAndFinish(Empty)
				else ctx.pull()
			}
			override def onUpstreamFinish(ctx: Context[Result[String]]) = ctx.absorbTermination()
		}
	}

	def forOptionalAttribute(name: String): Parser[Any, Option[String]] = forOptionalAttribute(new QName(name))
	def forOptionalAttribute(name: QName): Parser[Any, Option[String]] = fromHandlingStage{ () =>
		new XmlHandlingStage[Any, Option[String]] {
			def onPush(s: XmlStackState[Any], ctx: Context[Result[Option[String]]]) = {
				if(s.currentEvent.isStartElement){
					val attr = s.currentEvent.asStartElement.getAttributeByName(name)
					if(attr == null){
						ctx.pushAndFinish(Success(None))
					} else {
						ctx.pushAndFinish(Result(Some(attr.getValue)))
					}
				} else {
					ctx.pull()
				}
			}

			def onPull(ctx: Context[Result[Option[String]]]): SyncDirective = {
				if(ctx.isFinishing) ctx.pushAndFinish(Empty)
				else ctx.pull()
			}

			override def onUpstreamFinish(ctx: Context[Result[Option[String]]]) = ctx.absorbTermination()
		}
	}

	/** A parser that will collect the text from all `Characters` events,
	  * emitting the concatenation of those strings when the stream ends.
	  */
	val forText: Parser[Any, String] = fromHandlingStage{ () =>
		new XmlHandlingStage[Any, String] {
			var sb = new StringBuilder
			def onPush(s: XmlStackState[Any], ctx: Context[Result[String]]) = {
				if(s.currentEvent.isCharacters){
					sb append s.currentEvent.asCharacters.getData
				}
				ctx.pull()
			}
			override def onUpstreamFinish(ctx: Context[Result[String]]) = ctx.absorbTermination()
			def onPull(ctx: Context[Result[String]]) = {
				if(ctx.isFinishing) ctx.pushAndFinish(Result(sb.result))
				else ctx.pull()
			}
		}
	}
}