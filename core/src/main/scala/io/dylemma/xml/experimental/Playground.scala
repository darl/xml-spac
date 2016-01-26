package io.dylemma.xml.experimental

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.concurrent.Await
import scala.concurrent.duration._

object Playground extends App {

	implicit val system = ActorSystem("reactive-playground")
	implicit val materializer = ActorMaterializer()

	val rawXml = """<?xml version="1.0" encoding="UTF-8"?>
		|<stuff>
		|    <a foo="doo" bar="flar">This is my A</a>
		|    <b>This is my B</b>
		|    <cs>
		|        <c>This is the first C</c>
		|        <c>This is the second C</c>
		|    </cs>
		|</stuff>""".stripMargin

	val xmlSrc = XmlEventPublisher(rawXml)
	val result = xmlSrc.runForeach(println)

	Await.ready(result, 5.seconds)
	system.shutdown()
}
