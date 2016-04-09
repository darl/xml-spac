package io.dylemma.xml.experimental

import javax.xml.stream.events.StartElement

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.dylemma.xml.Result.{Empty, Error, Success}
import io.dylemma.xml.experimental.ParserCombination._

import scala.concurrent.Await
import scala.concurrent.duration._
import ParsingDSL._

object Playground extends App {

	implicit val system = ActorSystem("reactive-playground")
	implicit val materializer = ActorMaterializer()

	val rawXml = """<?xml version="1.0" encoding="UTF-8"?>
		|<stuff>
		|    <a foo="doo" bar="flar">This is my A</a>
		|    <b>This is my B</b>
		|    <cs bloop="bleep">
		|        <c>This is the first C</c>
		|        <c>This is the second C</c>
		|    </cs>
		|    <cs>
		|        This is my jam!
		|        <c>Whoops</c>
		|    </cs>
		|</stuff>""".stripMargin

	val demuxXml =
		"""<foo>
			|  <a>This is text in an A</a>
			|  <b>This is text in a B!</b>
			|  <a>Another &lt;A/&gt;</a>
			|  <c>This is a C...</c>
			|</foo>
		""".stripMargin

	sealed trait Thing
	case class ThingA(s: String) extends Thing
	case class ThingB(s: String) extends Thing
	case class ThingC(s: String) extends Thing

	try {

		val parserA = (* % Text) map ThingA
		var parserB = (* % Text) map ThingB
		var parserC = (* % Text) map ThingC
		val demuxABC: Parser[String, Thing] = Parser.demultiplexed[String](
			parserA -> { _ == "a"},
			parserB -> { _ == "b" },
			parserC -> { _ == "c" }
		)
		val demuxSplitter = "foo" / ("a" | "b" | "c").extractName

		import Parser.demuxSyntax._
		val demuxABC2: Parser[String, Thing] = for {
			context <- Demux[String]
			a <- parserA if context === "a"
			b <- parserB if context === "b"
			c <- parserC if context === "c"
		} yield a | b | c

		val demuxResult = XmlEventPublisher(demuxXml).via(demuxSplitter.through(demuxABC2).asFlow).runForeach(println)
		Await.ready(demuxResult, 5.seconds)

//		val xmlSrc = XmlEventPublisher(rawXml)
//
//		val splitter = "stuff" / "cs" mapContext { _ => "yay" }
//
//		val complexParser = (
//			inContext[String] ~
//				(* % "bloop") ~
//				(* % Text)
//			) join { (context, attr, text) =>
//			s"cs: context=$context attr=$attr, text=$text}"
//		}
//
//		val transformerFlow = splitter.through(complexParser).asFlow
//
//		val result = xmlSrc.via(transformerFlow).runForeach {println}
//
//		Await.ready(result, 5.seconds)
//		result.value foreach {
//			case util.Success(list) => println(list)
//			case util.Failure(err) => err.printStackTrace()
//		}
	} finally {
		system.terminate()
	}
}
