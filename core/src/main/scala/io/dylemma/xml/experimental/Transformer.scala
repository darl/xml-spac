package io.dylemma.xml.experimental

trait Transformer[E, A] {
	def makeHandler(): TransformerHandler[E, A]
}

trait TransformerHandler[E, A] {
	def handle(event: E): TransformerState[A]
}