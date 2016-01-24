package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.{XMLEvent, StartElement}

import io.dylemma.xml.Result.Success
import io.dylemma.xml.{Result, MapROps}

import language.implicitConversions

/**
	* Created by dylan on 12/30/2015.
	*/
object ParsingDSL extends ParserCombinerOps with ExpChainParserOps with MapROps {

	object Text {
		object asList
		object asOption
	}

	implicit class XmlSplitterTextSyntax[In](splitter: XmlSplitter[In]) {
		def %(t: Text.type) = splitter.textConcat
		def %(t: Text.asOption.type) = splitter.textOption
		def %(t: Text.asList.type) = splitter.textList
	}
	implicit class XmlSplitterFromStringTextSyntax(tagName: String) {
		def %(t: Text.type) = tag(tagName).textConcat
		def %(t: Text.asOption.type) = tag(tagName).textOption
		def %(t: Text.asList.type) = tag(tagName).textList
	}

	object Root extends XmlContextSplitter.ByStack[Unit] {
		def matchStack(stack: List[StartElement]): Option[(Result[Unit], List[StartElement])] = {
			Some(Success(()) -> stack)
		}
	}

	val * = XmlContextSplitter.ByTag.predicate(_ => true)

	def tag(qname: QName) = XmlContextSplitter.ByTag.predicate(_.getName == qname)
	def tag(name: String) = XmlContextSplitter.ByTag.predicate(_.getName.getLocalPart == name)
	implicit def stringToTagMatcher(name: String) = tag(name)

	def attr(qname: QName): XmlContextSplitter.ByTag[String] = XmlContextSplitter.ByTag { tag =>
		val a = tag.getAttributeByName(qname)
		if (a == null) None else Some(a.getValue)
	}
	def attr(name: String): XmlContextSplitter.ByTag[String] = attr(new QName(name))

	def inContext[C] = Parser.inContext[C, XMLEvent]

	val foo = attr("hello").map(_.toInt)
}
