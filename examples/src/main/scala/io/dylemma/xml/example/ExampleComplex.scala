package io.dylemma.xml.example

import javax.xml.stream.events.XMLEvent

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import io.dylemma.xml.Result.Success
import io.dylemma.xml.experimental.{XmlStackState, XmlEventPublisher, Parser}
import io.dylemma.xml.experimental.ParsingDSL._

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Created by dylan on 10/11/2015.
 */
object ExampleComplex {

	case class LocRange(start: String, end: Option[String])
	implicit val LocRangeParser: Parser[Any, LocRange] = (
		(* % "start") ~
		(* %? "end")
	).join(LocRange)

	case class Location(path: String, line: Option[LocRange], col: Option[LocRange])
	implicit val LocationParser: Parser[Any, Location] = (
		(* % "path") ~
		(* / "line").asOptional[LocRange] ~
		(* / "column").asOptional[LocRange]
	).join(Location)

	case class Comment(user: String, body: String)
	implicit val CommentParser: Parser[Any, Comment] = (
		(* % "user") ~
		(* % Text).map(_.trim)
	).join(Comment)

	case class Finding(severity: String, status: String, loc: Location , comments: List[Comment])
	implicit val FindingParser: Parser[Any, Finding] = (
		(* % "severity") ~
		(* % "status") ~
		(* / "location").as[Location] ~
		(* / "comments" / "comment").asList[Comment]
	).join(Finding)

	def main (args: Array[String]) {
		implicit val actorSystem = ActorSystem("example-system")
		implicit val materializer = ActorMaterializer()

		// TODO: fix parsers that can succeed when there are no results, i.e. optional/toList
		// which are currently failing and causing empty results. Add tests to prevent regression
		try {
			val findingFlow = (Root / "findings" / "finding").through(FindingParser).asFlow
			val xmlInputStream = getClass.getResourceAsStream("/example-complex.xml")
			val xmlSource = XmlEventPublisher(xmlInputStream)
			val doneFuture = xmlSource via findingFlow runForeach { r => println(s"RESULT: $r") }
			Await.ready(doneFuture, 5.seconds)
		} finally {
			actorSystem.terminate()
		}
	}
}
