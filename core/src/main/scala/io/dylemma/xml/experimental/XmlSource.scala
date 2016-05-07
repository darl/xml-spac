package io.dylemma.xml.experimental

import javax.xml.stream.{XMLInputFactory, XMLEventReader}
import javax.xml.stream.events.XMLEvent

import akka.actor.Props
import akka.stream.actor.{ActorPublisherMessage, ActorPublisher}
import akka.stream.scaladsl.Source
import io.dylemma.xml.{XMLEventSource, AsInputStream}

import scala.annotation.tailrec
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

object XmlEventPublisher {
	def apply[In: AsInputStream](input: In, inputFactory: XMLInputFactory = XMLEventSource.defaultInputFactory) = {
		val props = Props{ new XmlEventActorPublisher(input, implicitly[AsInputStream[In]], inputFactory, 100) }
		Source.actorPublisher[XMLEvent](props)
	}
}

private object XmlEventActorPublisher {
	case object Continue
}

/** ActorPublisher implementation that publishes events obtained by reading some input
	* via the `AsInputStream` typeclass. Internal details heavily based on
	* https://github.com/akka/akka/blob/stream-http-2.0/akka-stream/src/main/scala/akka/stream/impl/io/FilePublisher.scala
	*/
private class XmlEventActorPublisher[In]
	(input: In, adapter: AsInputStream[In], inputFactory: XMLInputFactory, maxBuffer: Int)
	extends ActorPublisher[XMLEvent]
{
	import XmlEventActorPublisher._

	private var inputResource: adapter.Resource = _
	private var eventReader: XMLEventReader = _

	var availableEvents: Vector[XMLEvent] = Vector.empty
	var eofReached = false

	override def preStart() = {
		try {
			inputResource = adapter.openResource(input)
			val stream = adapter.resourceToStream(inputResource)
			eventReader = inputFactory.createXMLEventReader(stream)
		} catch {
			case NonFatal(err) => onErrorThenStop(err)
		}

		super.preStart()
	}

	def receive = {
		case ActorPublisherMessage.Request(elements) => readAndSignal(maxBuffer)
		case Continue => readAndSignal(maxBuffer)
		case ActorPublisherMessage.Cancel => context.stop(self)
	}

	def readAndSignal(maxReadAhead: Int) = {
		if(isActive){
			// Write previously buffered, read into buffer, write newly buffered
			availableEvents = signalOnNexts(readAhead(maxReadAhead, signalOnNexts(availableEvents)))
			if(totalDemand > 0 && isActive) self ! Continue
		}
	}

	@tailrec private def signalOnNexts(events: Vector[XMLEvent]): Vector[XMLEvent] =
		if(events.nonEmpty && totalDemand > 0){
			onNext(events.head)
			signalOnNexts(events.tail)
		} else {
			if(events.isEmpty && eofReached) onCompleteThenStop()
			events
		}

	/** BLOCKING I/O READ */
	@tailrec final def readAhead(maxEvents: Int, events: Vector[XMLEvent]): Vector[XMLEvent] = {
		if(events.size <= maxEvents && isActive){
			val hasNext = try eventReader.hasNext catch { case NonFatal(err) => onErrorThenStop(err); false }
			if(hasNext){
				Try(eventReader.nextEvent) match {
					case Success(event) =>
						val newEvents = events :+ event
						readAhead(maxEvents, newEvents)
					case Failure(err) =>
						onErrorThenStop(err)
						Vector.empty
				}
			} else {
				// hasNext is false when we hit the EOF
				eofReached = true
				events
			}
		} else {
			events
		}
	}

	override def postStop() = {
		super.postStop()

		try {
			if(inputResource != null) adapter.closeResource(inputResource)
		} catch {
			case err: Exception =>
				// TODO:
				???
		}
	}
}
