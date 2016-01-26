package io.dylemma.xml.experimental.extra

import io.dylemma.xml.experimental.{Working, ParserState, Handler}

trait NTrans[A, B] {
	def makeHandler[C](downstream: Handler[B, C, ParserState]): Handler[A, C, ParserState]
}

object NewTransformer {
	def apply[E] = new NewTransformer[E]
}
class NewTransformer[A] {

	def map[B](f: A => B) = new NTrans[A, B]{
		def makeHandler[C](downstream: Handler[B, C, ParserState]) = new Handler[A, C, ParserState]{
			def handleEvent(event: A): ParserState[C] = downstream handleEvent f(event)
			def handleEOF(): ParserState[C] = downstream.handleEOF()
			def handleError(err: Throwable): ParserState[C] = downstream handleError err
		}
	}

//	private trait HandlerHelper[A, C] extends Handler[A, C, ParserState] {
//		var _previousState: ParserState[C] = Working
//		var isEOF = false
//		var didFeedEof = false
//
//		@inline def previousState = _previousState
//		@inline private def previousState_=(s: ParserState[C]) = {
//			_previousState = s
//			_previousState
//		}
//
//		abstract override def handleEvent(event: A): ParserState[C] = {
//			if(isEOF){
//				if(didFeedEof) _previousState
//				else previousState = super.handleEOF()
//			} else {
//				previousState = super.handleEvent(event)
//			}
//		}
//		def handleEOF(): ParserState[C] = ???
//		def handleError(err: Throwable): ParserState[C] = ???
//	}

//	private trait PreviousStateHandling[A, C] extends Handler[A, C, ParserState] { self: Handler[A, C, ParserState] =>
//		private var _previousState: ParserState[C] = Working
//		@inline protected def previousState = _previousState
//
//		abstract override def handleEvent(event: A): ParserState[C] = {
//			_previousState = super.handleEvent(event)
//			_previousState
//		}
//		abstract override def handleEOF(): ParserState[C] = {
//			_previousState = super.handleEOF()
//			_previousState
//		}
//		abstract override def handleError(err: Throwable): ParserState[C] = {
//			_previousState = super.handleError(err)
//			_previousState
//		}
//	}

//	private trait EndHandling[A, C] extends Handler[A, C, ParserState] with PreviousStateHandling[A, C] {
//		private var isEnded = false
//		private var didFeedEOF = false
//
//		def endNow = {
//			if(isEnded){
//				if(didFeedEOF) previousState
//				else {
//					didFeedEOF = true
//					super.handleEOF()
//				}
//			} else {
//				isEnded = true
//				didFeedEOF = true
//				super.handleEOF()
//			}
//		}
//
//		abstract override def handleEvent(event: A) = {
//			// if isEnded, no-op and return the previous state
//			// otherwise: feed event to downstream
//			// if downstream returned a 'done' state, set the isEnded flag
//		}
//	}

	trait TransformerHandler[A, B] extends Handler[A, B, ParserState] {
		// TODO:
		// Maintain a 'previousState' which will be returned when/if the transformer needs to no-op.
		// Maintain an 'isEnded' flag which when set will prevent all events from being passed downstream.
		// If the downstream handler returns a 'done' state, make sure to set isEnded=true.
		// If the transformer logic wants to end prematurely, feed an EOF downstream

		private var _previousState: ParserState[B] = Working
		protected def previousState = _previousState

		private var _isEnded = false
		protected def isEnded = _isEnded

		@inline def checkEndAndUpdate(work: => ParserState[B]) = {
			if(_isEnded) previousState
			else {
				val newState = work
				if(newState.isDone) _isEnded = true
				newState
			}
		}

//		abstract override def handleEvent(event: A): ParserState[B] = {
//			if(_isEnded) _previousState
//			else {
//				val newState = super.handleEvent(event)
//				_previousState = newState
//				if(newState.isDone) _isEnded = true
//				newState
//			}
//		}
//
//		abstract override def handleEOF(): ParserState[B] = {
//			if(_isEnded) _previousState
//			else {
//				val newState = super.handleEOF()
//				_previousState = newState
//				_isEnded = true
//				newState
//			}
//		}
//
//		abstract override def handleError(err: Throwable): ParserState[B] = {
//			if(_isEnded) _previousState
//			else {
//				val newState = super.handleError(err)
//				_previousState = newState
//				if(newState.isDone) _isEnded = true
//				newState
//			}
//		}

	}

	def collect[B](pf: PartialFunction[A, B]) = new NTrans[A, B] {
		def makeHandler[C](downstream: Handler[B, C, ParserState]) = new TransformerHandler[A, C] {

			override def handleEvent(event: A) = checkEndAndUpdate {
				if(pf isDefinedAt event) downstream handleEvent pf(event)
				else previousState
			}
			override def handleEOF() = checkEndAndUpdate { downstream.handleEOF() }
			override def handleError(err: Throwable) = checkEndAndUpdate{ downstream.handleError(err) }
		}
	}

	def takeUntilNthError(n: Int) = new NTrans[A, A] {
		def makeHandler[C](downstream: Handler[A, C, ParserState]) = new TransformerHandler[A, C] {
			var errorCount = 0
			def handleEvent(event: A) = checkEndAndUpdate{ downstream.handleEvent(event) }
			def handleEOF() = checkEndAndUpdate{ downstream.handleEOF() }
			def handleError(err: Throwable) = checkEndAndUpdate {
				errorCount += 1
				if(errorCount >= n) handleEOF()
				else downstream.handleError(err)
			}
		}
	}

	def takeThroughNthError(n: Int) = new NTrans[A, A] {
		def makeHandler[C](downstream: Handler[A, C, ParserState]) = new Handler[A, C, ParserState] {
			var errorCount = 0
			def handleEvent(event: A) = downstream.handleEvent(event)
			def handleEOF(): ParserState[C] = downstream.handleEOF()
			def handleError(err: Throwable) = {
				// if this is at most the n'th error, feed the error to the downstream handler.
				// if that didn't cause a 'Done' state, and this is at least the n'th error, feed an EOF
				if(errorCount <= n){
					downstream.handleError(err)
				} else {
					handleEOF()
				}
			}
		}
	}


//	trait Foo {
//		def foo(): Unit
//		def bar(): Unit
//	}
//	trait FooMixin extends Foo {
//		abstract override def foo() = {
//			println("About to foo:")
//			super.foo()
//			println("I foo'd!")
//		}
//		abstract override def bar() = {
//			println("About to bar:")
//			super.bar()
//			println("I bar'd")
//		}
//	}
//
//	trait FooImpl extends FooMixin {
//		var didFoo = false
//		override def foo() = {
//			if(didFoo) bar()
//			else {
//				didFoo = true
//				println("FOO!")
//			}
//		}
//		override def bar() = {
//			println("BAR!")
//		}
//	}
//
//	class FooRealImpl extends FooImpl with FooMixin
}
