package io.dylemma.xml.experimental

import javax.xml.stream.XMLStreamException
import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.event.{Characters, StartElement}

import scala.collection.generic.CanBuildFrom
import scala.language.higherKinds

import io.dylemma.xml.{Chain => ~, MapRC, MapRC2, MapR, Result}
import io.dylemma.xml.Result._

trait ParserForContext[-In, -E, +A] {
	self =>
	def makeHandler(context: In): Handler[E, A, ParserState]

	def inContext(context: In): Parser[E, A] = new Parser[E, A] {
		def makeHandler(ignored: Any) = self.makeHandler(context)
	}

	def unmapContext[C](f: C => In): ParserForContext[C, E, A] = new ParserForContext[C, E, A] {
		def makeHandler(context: C) = self makeHandler f(context)
	}
}
object ParserForContext {

	implicit object ParserForContextMapRC extends MapRC2[ParserForContext] {
		def mapR[In, E, A, B](p: ParserForContext[In, E, A], f: Result[A] => Result[B]): ParserForContext[In, E, B] = {
			new ParserForContext[In, E, B] {
				def makeHandler(context: In) = new MappedParserHandler(p makeHandler context, f)
			}
		}
	}
}

trait ParserCombinerOps {
	implicit class ParserWithCombine[In1, E, A1](parser1: ParserForContext[In1, E, A1]) {
		def &[In2, A2, C](
			parser2: ParserForContext[In2, E, A2])(
			implicit ev: C >:< (In1, In2)
		): ParserForContext[C, E, A1 ~ A2] = new ParserForContext[C, E, A1 ~ A2] {
			def makeHandler(context: C) = {
				val (context1, context2) = ev(context)
				val handler1 = parser1 makeHandler context1
				val handler2 = parser2 makeHandler context2
				new CombinedParserHandler(handler1, handler2)
			}
		}
	}
}

trait Parser[-E, +A] extends ParserForContext[Any, E, A] {self =>
	def makeHandler(): Handler[E, A, ParserState] = makeHandler(())

	def &[E2 <: E, A2](parser2: Parser[E2, A2]): Parser[E2, A ~ A2] = new Parser[E2, A ~ A2] {
		def makeHandler(context: Any) = {
			val handler1 = self makeHandler context
			val handler2 = parser2 makeHandler context
			new CombinedParserHandler(handler1, handler2)
		}
	}

	def &[C, E2 <: E, A2](parser2: ParserForContext[C, E2, A2]): ParserForContext[C, E2, A ~ A2] = {
		new ParserForContext[C, E2, A ~ A2] {
			def makeHandler(context: C) = {
				val handler1 = self makeHandler context
				val handler2 = parser2 makeHandler context
				new CombinedParserHandler(handler1, handler2)
			}
		}
	}

	def withName(s: String): Parser[E, A] = new Parser[E, A] {
		def makeHandler(context: Any): Handler[E, A, ParserState] = self makeHandler context
		override def toString = s
	}
//	override def mapR[B](f: Result[A] => Result[B]): Parser[E, B] = new Parser[E, B] {
//		def makeHandler(context: Any) = new MappedParserHandler(self makeHandler context, f)
//	}
}

trait Handler[-E, +A, +S[+ _]] {
	def handleEvent(event: E): S[A]
	def handleError(err: Throwable): S[A]
	def handleEOF(): S[A]
}

private class MappedParserHandler[E, A, B](
	innerHandler: Handler[E, A, ParserState],
	f: Result[A] => Result[B]
) extends Handler[E, B, ParserState] {
	def handleEvent(event: E) = innerHandler.handleEvent(event).mapR(f)
	def handleEOF() = innerHandler.handleEOF().mapR(f)
	def handleError(err: Throwable) = innerHandler.handleError(err).mapR(f)
}

/** Specialization of Parser that has no internal state, allowing it
	* to be its own handler. The `makeHandler` method for stateless parsers
	* simply returns a reference to itself.
	*/
trait StatelessParser[E, A] extends Parser[E, A] with Handler[E, A, ParserState] {
	def makeHandler(context: Any) = this

	var name: Option[String] = None
	override def withName(s: String) = {
		name = Some(s)
		this
	}
	override def toString = name match {
		case None => super.toString
		case Some(s) => s
	}
}
object StatelessParser {
	def apply[E, A](
		onEvent: E => ParserState[A],
		onEOF: () => ParserState[A],
		onError: Throwable => ParserState[A]
	): StatelessParser[E, A] = new StatelessParser[E, A] {
		def handleEvent(event: E): ParserState[A] = onEvent(event)
		def handleEOF(): ParserState[A] = onEOF()
		def handleError(err: Throwable): ParserState[A] = onError(err)
	}

}

object Parser {

	implicit object ParserMapRC extends MapRC[Parser] {
		def mapR[E, A, B](p: Parser[E, A], f: Result[A] => Result[B]): Parser[E, B] = new Parser[E, B] {
			def makeHandler(context: Any) = new MappedParserHandler(p makeHandler context, f)
		}
	}

	def firstOrElse[E](alternate: => Result[E]) = StatelessParser[E, E](
		event => Done(Success(event)),
		() => Done(alternate),
		err => Done(Error(err))
	).withName("Parser.firstOrElse(..)")

	def firstOption[E] = StatelessParser[E, Option[E]](
		event => Done(Success(Some(event))),
		() => Done(Success(None)),
		err => Done(Error(err))
	).withName("Parser.firstOption")

	def first[E] = firstOrElse[E]{
		Error(new NoSuchElementException("EOF.first"))
	}.withName("Parser.first")

	def foreach[E](f: E => Unit) = StatelessParser[E, Unit](
		event => {
			f(event)
			Working
		},
		() => Done(Success()),
		err => Done(Error(err))
	).withName(s"Parser.foreach($f)")

	def foreachResult[E](thunk: Result[E] => Unit) = StatelessParser[E, Unit](
		event => {
			thunk(Success(event))
			Working
		},
		() => Done(Success()),
		err => {
			thunk(Error(err))
			Working
		}
	).withName(s"Parser.foreachResult($thunk)")

	def concat[A, B, That]()(implicit t: A => TraversableOnce[B], bf: CanBuildFrom[A, B, That]): Parser[A, That] = {
		new Parser[A, That] {
			def makeHandler(context: Any) = new Handler[A, That, ParserState]{
				val builder = bf()
				def handleEvent(event: A): ParserState[That] = {
					builder ++= event
					Working
				}
				def handleEOF(): ParserState[That] = {
					Done(Result(builder.result()))
				}
				def handleError(err: Throwable): ParserState[That] = {
					Done(Error(err))
				}
			}
		}
	}.withName(s"Parser.concat")

	def done[A](result: Result[A]) = StatelessParser[Any, A](
		event => Done(result),
		() => Done(result),
		err => Done(result)
	).withName(s"Parser.done($result)")

	def inContext[In, E]: ParserForContext[In, E, In] = new ParserForContext[In, E, In] {
		override def toString = "Parser.inContext"
		def makeHandler(context: In) = new Handler[E, In, ParserState] {
			// ignore all inputs, just return the result
			val state = Done(Success(context))
			def handleEvent(event: E) = state
			def handleEOF(): ParserState[In] = state
			def handleError(err: Throwable) = state
		}
	}

	def toList[E]: Parser[E, List[E]] = new Parser[E, List[E]] {
		override def toString = "Parser.toList"
		def makeHandler(context: Any) = new Handler[E, List[E], ParserState] {
			val lb = List.newBuilder[E]
			def handleEvent(event: E) = {
				lb += event
				Working
			}
			def handleEOF() = Done(Success(lb.result))
			def handleError(err: Throwable) = Done(Error(err))
		}
	}

	def parseMandatoryAttribute(attribute: String): Parser[XMLEvent, String] = StatelessParser[XMLEvent, String](
		{
			case e @ StartElement(_, attrs) => attrs.get(attribute) match {
				case None =>
					val msg = s"Expected a value for the '$attribute' attribute, but none was found"
					Done(Error(new XMLStreamException(msg, e.getLocation)))
				case Some(value) =>
					Done(Success(value))
			}
			case _ => Working
		},
		() => {
			Done(Empty)
		},
		err => Done(Error(err))
	).withName(s"Parser.parseMandatoryAttribute($attribute)")

	def parseOptionalAttribute(attribute: String): Parser[XMLEvent, Option[String]] = StatelessParser[XMLEvent, Option[String]](
		{
			case e @ StartElement(_, attrs) => Done(Success(attrs get attribute))
			case _ => Working
		},
		() => Done(Empty),
		err => Done(Error(err))
	).withName(s"Parser.parseOptionalAttribute($attribute)")

	def parseXmlText: Parser[XMLEvent, String] = new Parser[XMLEvent, String] {
		override def toString = "Parser.parseXmlText"
		def makeHandler(context: Any) = new Handler[XMLEvent, String, ParserState] {
			val sb = new StringBuilder
			var didGetFirstText = false
			def handleEvent(event: XMLEvent) = {
				event match {
					case Characters(text) =>
						sb append text
						didGetFirstText = true
					case _ =>
				}
				Working
			}
			def handleEOF(): ParserState[String] = {
				if(didGetFirstText) Done(Result(sb.result()))
				else Done(Empty)
			}
			def handleError(err: Throwable) = {
				Done(Error(err))
			}
		}
	}
}

private class CombinedParserHandler[E, A1, A2](
	handler1: Handler[E, A1, ParserState],
	handler2: Handler[E, A2, ParserState]
) extends Handler[E, A1 ~ A2, ParserState] {
	var state1: ParserState[A1] = Working
	var state2: ParserState[A2] = Working

	def combinedResult = (state1, state2) match {
		case (Done(r1), Done(r2)) => Done(for (a1 <- r1; a2 <- r2) yield new ~(a1, a2))
		case (Done(e: Error), _) => Done(e)
		case (_, Done(e: Error)) => Done(e)
		case (Done(Empty), _) | (_, Done(Empty)) => Done(Empty)
		case (Working, _) | (_, Working) => Working
	}
	def handleEvent(event: E): ParserState[A1 ~ A2] = {
		if(!state1.isDone) state1 = handler1 handleEvent event
		if(!state2.isDone) state2 = handler2 handleEvent event
		combinedResult
	}
	def handleEOF(): ParserState[A1 ~ A2] = {
		if(!state1.isDone) state1 = handler1.handleEOF()
		if(!state2.isDone) state2 = handler2.handleEOF()
		combinedResult
	}
	def handleError(err: Throwable): ParserState[A1 ~ A2] = {
		if(!state1.isDone) state1 = handler1.handleError(err)
		if(!state2.isDone) state2 = handler2.handleError(err)
		combinedResult
	}
}
