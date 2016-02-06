package io.dylemma.xml.experimental

import javax.xml.stream.events.XMLEvent

import akka.stream.scaladsl.Flow
import io.dylemma.xml.Result

/**
	* Created by dylan on 1/30/2016.
	*/
trait Transformer[A] {

	def asFlow: Flow[XMLEvent, Result[A], Unit]
}


trait Splitter[+Context] {
	def through[Out](parser: Parser[Context, Out]): Transformer[Out]


}

class XmlSplitterImpl[Context](contextMatcher: ContextMatcher[Context]) extends Splitter[Context] {
	def through[Out](parser: Parser[Context, Out]) = new XmlTransformerImpl(contextMatcher, parser)
}

class XmlTransformerImpl[Context, Out](
	contextMatcher: ContextMatcher[Context],
	innerParser: Parser[Context, Out]
) extends Transformer[Out] {
	import XmlTransformerImpl._

	def asFlow: Flow[XMLEvent, Result[Out], Unit] = {
		val stateScanner = XmlStackState.scanner(contextMatcher)
		val splitterFlow = groupByConsecutiveMatches[Context]
			.via(innerParser.asFlow)
			.concatSubstreams
		stateScanner via splitterFlow
	}
}

object XmlTransformerImpl {
	def groupByConsecutiveMatches[T] = Flow[XmlStackState[T]]
		.scan(SplitterState(false, false, XmlStackState.initial[T])){ (state, next) => state.advance(next, next.matchedContext.isSuccess) }
		.dropWhile(!_.currentMatches)
		.splitWhen(_.isNewlyMatched)
		.takeWhile(_.currentMatches)
		.map(_.current)
}

private[xml] case class SplitterState[T](previousDidMatch: Boolean, currentMatches: Boolean, current: T) {
	def advance(item: T, matches: Boolean) = SplitterState(currentMatches, matches, item)
	def isNewlyMatched = currentMatches && !previousDidMatch
}