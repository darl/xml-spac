package io.dylemma.spac.handlers

import javax.xml.stream.events.XMLEvent

import io.dylemma.spac.Handler

import scala.util.Try

class TextCollectorHandler
	extends Handler[XMLEvent, String]
	with ManualFinish
	with FinishOnError
{
	override def toString = "XMLText"
	private val sb = new StringBuilder
	def handleEnd(): String = finishWith(sb.result())
	def handleInput(input: XMLEvent) = {
		if(input.isCharacters) {
			sb append input.asCharacters.getData
		}
		None
	}
}
