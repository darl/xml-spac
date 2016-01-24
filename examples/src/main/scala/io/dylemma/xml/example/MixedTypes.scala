package io.dylemma.xml.example

import io.dylemma.xml._
import ParsingDSL._
import play.api.libs.iteratee.Execution.Implicits.trampoline

/**
 * Created by dylan on 12/1/2015.
 */
object MixedTypes extends App {
	var rawXml = """<Content>
	| Hello world <Replace key="stuff"/>
	| Here's some more text
	| <span>And here's a span with text in it</span>
	|</Content>""".stripMargin

	val contentParser = Root / "Content" % Text.asList

	val result = contentParser parse rawXml
	println(result.value.get.get)
}
