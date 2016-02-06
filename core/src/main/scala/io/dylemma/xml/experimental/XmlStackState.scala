package io.dylemma.xml.experimental

import javax.xml.stream.events.{StartElement, XMLEvent}

import akka.stream.scaladsl.Flow
import io.dylemma.xml.Result
import io.dylemma.xml.Result.Empty

/** Intermediate state for handling an XML stream.
  *
  * @param currentEvent The XML Event currently being handled
  * @param stack A list of start element events (possibly including the current event), which denote the
  *              current contextual location of the current event within the XML stream. The latest start
  *              element event will be at the end of the list.
  * @param matchedContext A `Result` value denoting a "context" derived from the current stack. Contexts
  *                       are used to represent an element and its children, i.e. a context may begin as
  *                       the stack grows, and may end as the stack shrinks. Over the course of an entire
  *                       XML stream, many contexts may be encountered.
  * @param matchDepth The stack size at which the current context began. If the current context is `Empty`,
  *                   the matchDepth will be `-1`. Note that client code will generally not be interested
  *                   in this value - it is used as a hint to decide whether the `matchedContext` should
  *                   end when encountering an end element event.
  * @tparam T The type of values held by `matchedContext` when it is a `Success`.
  */
case class XmlStackState[+T](currentEvent: XMLEvent, stack: List[StartElement], matchedContext: Result[T], matchDepth: Int)

object XmlStackState {

	def initial[T] = XmlStackState[T](null, Nil, Empty, -1)

	/** Creates a `Flow` that accepts XML Events as input, yielding a new `XmlStackState` as output.
	  *
	  * @param matchContext A function that finds a "context" value from a given "stack" of StartElement events.
	  *                     The context returned by this function will be used to create the `matchedContext`
	  *                     value in the XmlStackState values output by the resulting `Flow`.
	  * @tparam T The type of context matched by the `matchContext` function
	  * @return A `Flow` that scans over XMLEvents, outputting XmlStackState events for each input
	  */
	def scanner[T](matchContext: List[StartElement] => Result[T]) = {

		Flow[XMLEvent].scan(initial[T]) { (state, event) =>
			// If the event is a StartElement, the context may be growing, so we should check for context matches.
			// If the event is an EndElement, we should check if the stack shrank below the matchDepth, implying the context no longer matches.
			// Other events don't affect the stack, and therefore will not affect the matched context

			if (event.isStartElement) {

				// update the stack, and possibly the match state
				val start = event.asStartElement
				val newStack = state.stack :+ start
				if (state.matchedContext.isEmpty) {
					val newMatch = matchContext(newStack)
					val newDepth = if (newMatch.isSuccess) newStack.size else state.matchDepth
					XmlStackState(event, newStack, newMatch, newDepth)
				} else {
					XmlStackState(event, newStack, state.matchedContext, state.matchDepth)
				}
			} else if (event.isEndElement) {

				// update the stack, possibly clearing the match state
				val newStack = state.stack dropRight 1
				val newDepth = newStack.length
				if (newDepth < state.matchDepth) {
					XmlStackState(event, newStack, Empty, -1)
				} else {
					XmlStackState(event, newStack, state.matchedContext, state.matchDepth)
				}
			} else {

				// just update the currentEvent, not touching the stack or match states
				state.copy(currentEvent = event)
			}
		}.drop(1) // drop the 'initial' state to avoid accidentally passing a `null` through a stream
	}
}