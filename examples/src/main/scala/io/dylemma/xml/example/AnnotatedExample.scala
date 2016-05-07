package io.dylemma.xml.example

import javax.xml.stream.events.XMLEvent

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import io.dylemma.xml.Result
import io.dylemma.xml.experimental.ParsingDSL._
import io.dylemma.xml.experimental.{XmlEventPublisher, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object AnnotatedExample extends App {

	// create an ActorSystem and ActorMaterializer for interacting with akka-streams
	implicit val actorSystem = ActorSystem("example-system")
	implicit val actorMaterializer = ActorMaterializer()

	// we need to shut down the actorSystem at the end, even if an exception is thrown,
	// or else the program will hang when the main thread ends
	try {
		val rawXml = s"""<blog title="Cool Beans">
			| <post id="1">
			|  <comment user="bob">Hello there</comment>
			|  <comment user="alice">Oh, hi</comment>
			| </post>
			| <post id="2">
			|  <comment user="carla">Test comment!</comment>
			|  <comment user="dylan">I'm testing too!</comment>
			| </post>
			|</blog>""".stripMargin

		/** This creates an akka-streams Source of XMLEvents from the `rawXml` String.
			* The `XmlEventPublisher` constructor uses the `AsInputStream` typeclass to
			* accept arguments of various types, including Strings, Files, and InputStreams.
			*/
		val xmlSource: Source[XMLEvent, ActorRef] = XmlEventPublisher(rawXml)

		/** This class represents the 'context' that our parser *must* be in,
			* in order to generate results. We'll be taking the `blogTitle` from
			* the "blog" element's "title" attribute, and the `postId` from the
			* "post" element's "id" attribute.
			*/
		case class PostContext(blogTitle: String, postId: Int)

		/** A splitter divides the XMLEvent stream into substreams, where each
			* substream has some defined context. You attach a parser to a splitter
			* in order to transform a stream of XMLEvents to a stream of results.
			*
			* There's a lot of syntax sugar happening in this expression:
			*
			* The strings like "blog", "post", and "comment" are being implicitly
			* transformed to `Matcher`s which filter on elements with those tags.
			*
			* The `attr("title")` and `attr("id")` calls return `Matcher`s which
			* extract their respective attributes from the element they are attached to.
			*
			* Individual matchers are combined with `&`. They are then combined with `/`
			* to create an overall matcher that applies to the entire tag stack.
			*
			* When multiple matchers which extract results are combined, their results
			* are combined into a `Chain`. The `joinContext` call at the end combines
			* each result from the extracted chain by passing them into a function -
			* in this case - `PostContext.apply`.
			*/
		val splitter: Splitter[PostContext] = (
			Root /
			("blog" & attr("title")) /
			("post" & attr("id").mapContext(_.toInt)) /
			"comment"
		).joinContext(PostContext)

		/** This class is what we are parsing.
			* The `context` argument will come from the `blog[title]` and `post[id]`
			* attributes, which are being extracted by the `splitter`.
			* The `user` argument will come from the `comment[user]` attribute.
			* The `text` argument will come from the text inside the `comment` element.
			*/
		case class Comment(context: PostContext, user: String, text: String)

		/** We define the parser for `Comments`.
			* We're actually defining 3 separate parsers, combining them into a chain via `~`,
			* then `join`-ing the chain together with the `Comment.apply` function.
			*/
		val commentParser: Parser[PostContext, Comment] = (
			/* This parser simply returns the context that gets passed in,
			 * but it also adds the requirement that there *be* a conext.
			 */
			inContext[PostContext] ~

			/* This parser extracts the "user" attribute from the top-level element
			 * from the xml stream. Since this will be called on substreams where
			 * "comment" is the top-level element, it'll find the `comment[user]` attribute.
			 */
			(* % "user") ~

			/* This parser extracts the text from the top-level element of the xml stream.
			 * Again, this parser will be called on substreams where "comment" is the
			 * top-level element, so it'll find the comment's content.
			 */
			(* % Text)

		/* Before calling `join`, we have a `Parser[PostContext, Chain[Chain[PostContext, String], String]]]`.
		 * The `join` function will take a `(PostContent, String, String) => Result`.
		 * Here, we use `Comment.apply`
		 */
		).join(Comment)

		/* The `commentParser` can't be used by itself because it requires a context
		 * to be passed into it. You can use the `inContext` method to give it context,
		 * or you can attach it to the `splitter`, which will pass the appropriate
		 * context for each substream it finds.
		 *
		 * We'll attach it to the `splitter`, which gives us a `Transformer`.
		 */
		val commentTransformer = splitter.through(commentParser)

		/*
		 * Under the hood, a transformer is an Flow[XMLEvent, Result], meaning
		 * it transforms a stream of xml events into a stream of results.
		 * You can use the usual `Source.via(Flow).runWith(Sink)` combinations
		 * to consume the stream.
		 */
		val commentFlow: Flow[XMLEvent, Result[Comment], akka.NotUsed] = commentTransformer.asFlow
		val transformedSource: Source[Result[Comment], ActorRef] = xmlSource.via(commentFlow)
		val doneParsing: Future[akka.Done] = transformedSource.runForeach{ result => println(s"Stream result: $result") }
		Await.ready(doneParsing, 5.seconds)

		/* ...BUT you don't *have* to.
		 * The transformer has some convenience methods for collecting the elements it
		 * emits into some further value (e.g. a collection or an option), resulting in
		 * a parser for that value. Since the transformer already had a context attached,
		 * the resulting parser will also already have a context attached.
		 */
		val firstCommentParser: Parser[Any, Comment] = commentTransformer.parseFirst
		val commentsListParser: Parser[Any, List[Comment]] = commentTransformer.parseToList

		/*
		As long as a Parser has been attached to a Context (i.e. its first type argument is `Any`),
		It can be treated as a `Sink[XMLEvent, Future[Result]]`, which allows you to get a value
		from an XML Source.

		Parsers created via a Transformer's `parseXYZ` method will always be attached to a context.
		You can also call `myParser.inContext(myContext)` to explicitly attach it to a context.
		 */
		val allComments: Future[Result[List[Comment]]] = xmlSource.runWith(commentsListParser.asSink)
		val allCommentsResult = Await.result(allComments, 5.seconds)
		println(s"allComments finished: $allCommentsResult")

		/* For convenience, you could just call `parse` instead, avoiding having to
		 * manually create Sources and Sinks. The `parse` method works on the same
		 * typeclass as the XMLEventPublisher, meaning you can pass in files and streams
		 * instead of Strings.
		 */
		val allComments2: Future[Result[List[Comment]]] = commentsListParser.parse(rawXml)
		val allComments2Result = Await.result(allComments2, 5.seconds)
		assert(allCommentsResult == allComments2Result)

	} finally {
		actorSystem.terminate()
	}
}
