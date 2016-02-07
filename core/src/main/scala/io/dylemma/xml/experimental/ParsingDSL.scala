package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.StartElement

import io.dylemma.xml.Result
import io.dylemma.xml.Result.Success

import scala.language.implicitConversions

/**
	* Created by dylan on 2/6/2016.
	*/
object ParsingDSL {

	object Text {
		object asOption
		object asList
	}

	implicit class SplitterTextOps(splitter: Splitter[_]) {
		def %(t: Text.type) = splitter.textConcat
		def %(t: Text.asOption.type) = splitter.textOption
		def %(t: Text.asList.type) = splitter.textList
	}

	object Root extends ContextMatcher[Unit] {
		def apply(v1: List[StartElement]): Result[Unit] = Success.unit
	}

	val * = SingleElementContextMatcher.predicate(_ => true)

	implicit def tag(qname: QName) = SingleElementContextMatcher.predicate(_.getName == qname)
	implicit def tag(name: String) = SingleElementContextMatcher.predicate(_.getName.getLocalPart == name)

	def attr(qname: QName) = SingleElementContextMatcher { elem => Option(elem getAttributeByName qname).map(_.getValue) }
	def attr(name: String): SingleElementContextMatcher[String] = attr(new QName(name))

	def inContext[C] = Parser.forContext[C]
}
