package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.StartElement

import io.dylemma.xml.Result
import io.dylemma.xml.Result.Success

import scala.language.implicitConversions

/**
	* Created by dylan on 2/6/2016.
	*/
object ParsingDSL extends ParserCombination {

	object Text {
		object asOption
		object asList
	}

	implicit class SplitterTextOps(splitter: Splitter[_]) {
		def %(t: Text.type) = splitter.textConcat
		def %(t: Text.asOption.type) = splitter.textOption
		def %(t: Text.asList.type) = splitter.textList
	}

	object Root extends ChainingContextMatcher[Unit] {
		protected def matchSegment(stack: List[StartElement]) = Success(() -> stack)
	}

	val * = SingleElementContextMatcher.predicate(_ => true)

	implicit def tag(qname: QName) = SingleElementContextMatcher.predicate(_.getName == qname)
	implicit def tag(name: String) = SingleElementContextMatcher.predicate(_.getName.getLocalPart == name)

	def attr(qname: QName) = SingleElementContextMatcher { elem => Option(elem getAttributeByName qname).map(_.getValue) }
	def attr(name: String): SingleElementContextMatcher[String] = attr(new QName(name))

	def inContext[C] = Parser.forContext[C]
}
