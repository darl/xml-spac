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

	val xmlSrc = XmlEventPublisher(rawXml)

	val splitter = "stuff" / "cs" mapContext { _ => "yay" }

	val complexParser = (
		inContext[String] ~
		(* %? "bloop") ~
		(* % Text)
	) join { (context, attr, text) =>
		s"cs: context=$context attr=$attr, text=$text}"
	}

	val transformerFlow = splitter.asList(complexParser).asRawFlow

	val result = xmlSrc.via(transformerFlow).runForeach { println }

	Await.ready(result, 5.seconds)
	result.value.get match {
		case util.Success(list) => println(list)
		case util.Failure(err) => err.printStackTrace()
	}
	system.terminate()
}
