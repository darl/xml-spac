package io.dylemma.xml.example

import io.dylemma.spac._
import io.dylemma.spac.xml._

object Example2a_BrokenContext extends App {

	// note the <post id="D"> - we are calling `_.toInt` on this context value,
	// which will throw an exception, which will end up wrapped in Error results
	// by the parser
	val rawXml = s"""<blog>
		| <post id="D">
		|  <comment user="bob">Hello there</comment>
		|  <comment user="alice">Oh, hi</comment>
		| </post>
		| <post id="2">
		|  <comment user="carla">Test comment!</comment>
		|  <comment user="dylan">I'm testing too!</comment>
		| </post>
		|</blog>""".stripMargin

	case class Comment(postId: Int, user: String, text: String)

	def CommentParser(idFromContext: Int): Parser[Comment] = (
		Parser.constant(idFromContext) and
		Parser.forMandatoryAttribute("user") and
		Parser.forText
	).as(Comment)

	val contextMatcher = "blog" \ ("post" & attr("id").map(_.toInt)) \ "comment"

	val consumer = Splitter(contextMatcher)
		.through(CommentParser) // parse the substreams created by the Splitter, using the implicit CommentParser
		.wrapSafe // wrap inputs and errors as `scala.util.Try` so we don't throw during the foreach
		.consumeForEach(println) // println each of the results

	consumer consume rawXml
}
