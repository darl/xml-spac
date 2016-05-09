package io.dylemma.xml.example

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.dylemma.xml.experimental.ParsingDSL._
import io.dylemma.xml.Result

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Created by dylan on 10/11/2015.
 */
object ExampleSimple {

	val libraryXml = """<library>
		| <book>Don Quixote</book>
		| <book>A Tale of Two Cities</book>
		| <book>The Lord of the Rings</book>
		| <book>The Little Prince</book>
		| <book>Harry Potter and the Sorcerer's Stone</book>
		| <book>And Then There Were None</book>
		|</library>""".stripMargin

	val parser = (Root / "library" / "book" % Text.asList)

	def main(args: Array[String]): Unit = {
		implicit val actorSystem = ActorSystem("example-system")
		implicit val actorMaterializer = ActorMaterializer()

		try {

			// Parsers can parse anything belonging to the `AsInputStream` type class,
			// e.g. Strings, InputStreams, and Files
			val parseResult = Await.result(parser parse libraryXml, 5.seconds)

			// The `parseResult` is a Parser.Result containing the list of titles
			parseResult match {
				case Result.Error(cause) => cause.printStackTrace()
				case Result.Empty => println("no results")
				case Result.Success(titles) =>
					for (title <- titles) println(s"book: $title")
			}

			println("\n---\n")

			// you can also use `foreach` with Results
			for {
				titles <- parseResult
				title <- titles
			} println(s"book foreach: $title")

		} finally {
			actorSystem.terminate()
		}
	}
}
