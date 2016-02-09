package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.{StartElement, XMLEvent}

import akka.stream.scaladsl.Flow
import io.dylemma.xml.Result

import scala.collection.generic.CanBuildFrom

/**
	* Created by dylan on 1/30/2016.
	*/
trait Transformer[A] { self =>

	def asFlow: Flow[XMLEvent, Result[A], akka.NotUsed]

	def parseWith[B](parserFlow: Flow[Result[A], Result[B], akka.NotUsed]): Parser[Any, B] = Parser.fromFlow {
		Flow[XmlStackState[Any]].map(_.currentEvent).via(self.asFlow).via(parserFlow)
	}

	def transformWith[B](transformerFlow: Flow[Result[A], Result[B], akka.NotUsed]): Transformer[B] = {
		new Transformer[B] {
			def asFlow = self.asFlow via transformerFlow
		}
	}

	@inline def parseFirst = parseWith(FlowHelpers.first)
	@inline def parseFirstOption = parseWith(FlowHelpers.firstOption)
	@inline def parseToList = parseWith(FlowHelpers.toList)
	@inline def parseConcat[B, That]()(implicit t: A => TraversableOnce[B], bf: CanBuildFrom[A, B, That]) = {
		parseWith(FlowHelpers.concat)
	}
	@inline def takeUntilFirstError = transformWith(FlowHelpers.takeUntilNthError(1))
	@inline def takeUntilNthError(n: Int) = transformWith(FlowHelpers.takeUntilNthError(n))
	@inline def takeThroughFirstError = transformWith(FlowHelpers.takeThroughNthError(1))
	@inline def takeThroughNthError(n: Int) = transformWith(FlowHelpers.takeThroughNthError(n))
}

object Transformer {
	def fromFlow[A](flow: Flow[XMLEvent, Result[A], akka.NotUsed]): Transformer[A] = {
		new Transformer[A]{ def asFlow = flow }
	}

	def fromSplitterAndJoiner[Context, A](splitter: ContextMatcher[Context], joiner: Parser[Context, A]): Transformer[A] = {
		val stateScanner = XmlStackState.scanner(splitter.apply)
		val splitterFlow = groupByConsecutiveMatches[Context].via(joiner.asFlow).concatSubstreams
		fromFlow(stateScanner via splitterFlow)
	}

	private case class SplitterState[T](previousDidMatch: Boolean, currentMatches: Boolean, current: T) {
		def advance(item: T, matches: Boolean) = SplitterState(currentMatches, matches, item)
		def isNewlyMatched = currentMatches && !previousDidMatch
	}

	private def groupByConsecutiveMatches[T] = Flow[XmlStackState[T]]
		.scan(SplitterState(false, false, XmlStackState.initial[T])){ (state, next) => state.advance(next, next.matchedContext.isSuccess) }
		.dropWhile(!_.currentMatches)
		.splitWhen(_.isNewlyMatched)
		.takeWhile(_.currentMatches)
		.map(_.current)
}

trait Splitter[+Context] {

	def through[Out](parser: Parser[Context, Out]): Transformer[Out]

	// methods for feeding results through an 'inner' parser to obtain results
	@inline def as[Out](implicit parser: Parser[Context, Out]) = through(parser).parseFirst
	@inline def asOptional[Out](implicit parser: Parser[Context, Out]) = through(parser).parseFirstOption
	@inline def asList[Out](implicit parser: Parser[Context, Out]) = through(parser).parseToList

	// methods for obtaining the values of attributes from incoming elements
	@inline def attr(name: String) = through(Parser forAttribute name).parseFirst
	@inline def %(name: String) = attr(name)
	@inline def attr(qname: QName) = through(Parser forAttribute qname).parseFirst
	@inline def %(qname: QName) = attr(qname)

	// methods for obtaining the values of optional attributes from incoming elements
	@inline def attrOpt(name: String) = through(Parser forOptionalAttribute name).parseFirst
	@inline def %?(name: String) = attrOpt(name)
	@inline def attrOpt(qname: QName) = through(Parser forOptionalAttribute qname).parseFirst
	@inline def %?(qname: QName) = attrOpt(qname)

	// methods for obtaining the text content from incoming elements
	@inline def text = through(Parser.forText)
	@inline def textConcat = text.parseConcat()
	@inline def textOption = text.parseFirstOption
	@inline def textList = text.parseToList
}
