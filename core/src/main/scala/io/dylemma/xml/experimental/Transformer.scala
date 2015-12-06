package io.dylemma.xml.experimental

import io.dylemma.xml.Result
import io.dylemma.xml.Result.Error

trait Transformer[A, B] {
	def makeHandler(): Handler[A, B, TransformerState]
}

/** Specialization of Transformer that has no internal state, allowing it
	* to be its own handler. The `makeHandler` method for stateless transformers
	* simply returns a reference to itself.
	*/
trait StatelessTransformer[A, B] extends Transformer[A, B] with Handler[A, B, TransformerState] {
	def makeHandler() = this
}

object StatelessTransformer {
	def apply[A, B](
		onEvent: A => TransformerState[B],
		onEOF: () => TransformerState[B],
		onError: Throwable => TransformerState[B]
	): StatelessTransformer[A, B] = new StatelessTransformer[A, B] {
		def handleEvent(event: A): TransformerState[B] = onEvent(event)
		def handleEOF(): TransformerState[B] = onEOF()
		def handleError(err: Throwable): TransformerState[B] = onError(err)
	}
}

object Transformer {

	def collect[A, B](pf: PartialFunction[A, B]) = StatelessTransformer[A, B](
		(event) => if(pf isDefinedAt event) Emit(Result(pf(event))) else Working,
		() => Working,
		(err) => Done(Error(err))
	)
}