package io.dylemma.xml.experimental

import io.dylemma.xml.Result
import io.dylemma.xml.Result.Error

import scala.collection.generic.CanBuildFrom

trait Transformer[A, B] { self =>
	def makeHandler(): Handler[A, B, TransformerState]

	def mapR[C](f: Result[B] => Result[C]): Transformer[A, C] = new Transformer[A, C] {
		def makeHandler() = new MappedTransformerHandler(self.makeHandler(), f)
	}

	def parseWith[C](parser: Parser[B, C]): Parser[A, C] = {
		new ParserWithTransformedInput(self, parser)
	}

	def transformWith[C](transformer: Transformer[B, C]): Transformer[A, C] = {
		new TransformedTransformer(self, transformer)
	}

	@inline def parseSingle: Parser[A, B] = parseWith { Parser.first[B] }
	@inline def parseOptional: Parser[A, Option[B]] = parseWith { Parser.firstOption[B] }
	@inline def parseList: Parser[A, List[B]] = parseWith { Parser.toList[B] }
	@inline def foreach(f: B => Unit): Parser[A, Unit] = parseWith { Parser foreach f }
	@inline def foreachResult(f: Result[B] => Unit): Parser[A, Unit] = parseWith { Parser foreachResult f }

	@inline def parseConcat[Bt, That]()(implicit t: B => TraversableOnce[Bt], bf: CanBuildFrom[B, Bt, That]): Parser[A, That] = {
		parseWith { Parser.concat() }
	}

}

private class MappedTransformerHandler[E, A, B](
	innerHandler: Handler[E, A, TransformerState],
	f: Result[A] => Result[B]
) extends Handler[E, B, TransformerState] {
	def handleEvent(event: E) = innerHandler.handleEvent(event).mapR(f)
	def handleEOF() = innerHandler.handleEOF().mapR(f)
	def handleError(err: Throwable) = innerHandler.handleError(err).mapR(f)
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