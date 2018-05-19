package io.dylemma.spac

import io.dylemma.spac.handlers._
import io.dylemma.spac.types.Stackable

import scala.util.Try

/** An immutable object that can be used to create a handler which wraps
	* an existing handler, possibly transforming inputs before passing them
	* along to the downstream handler.
	*/
trait Transformer[-In, +B] { self =>
	def makeHandler[Out](next: Handler[B, Out]): Handler[In, Out]

	def andThen[C](nextT: Transformer[B, C]): Transformer[In, C] = >>(nextT)
	def >>[C](nextT: Transformer[B, C]): Transformer[In, C] = new Transformer[In, C] {
		def makeHandler[Out](next: Handler[C, Out]): Handler[In, Out] = {
			self.makeHandler(nextT.makeHandler(next))
		}
		override def toString = s"$self >> $nextT"
	}

	def andThen[Out](end: Consumer[B, Out]): Consumer[In, Out] = >>(end)
	def >>[Out](end: Consumer[B, Out]): Consumer[In, Out] = new Consumer[In, Out] {
		def makeHandler(): Handler[In, Out] = {
			self.makeHandler(end.makeHandler())
		}
		override def toString = s"$self >> $end"
	}

	def take(n: Int): Transformer[In, B] = andThen(Transformer.take(n))
	def takeWhile(p: B => Boolean): Transformer[In, B] = andThen(Transformer.takeWhile(p))
	def drop(n: Int): Transformer[In, B] = andThen(Transformer.drop(n))
	def dropWhile(p: B => Boolean): Transformer[In, B] = andThen(Transformer.dropWhile(p))
	def map[C](f: B => C): Transformer[In, C] = andThen(Transformer.map(f))
	def collect[C](pf: PartialFunction[B, C]): Transformer[In, C] = andThen(Transformer.collect(pf))
	def scan[S](init: S)(f: (S, B) => S): Transformer[In, S] = andThen(Transformer.scan(init)(f))
	def filter(p: B => Boolean): Transformer[In, B] = andThen(Transformer.filter(p))
	def parallel[In2 <: In, B2 >: B](other: Transformer[In2, B2]): Transformer[In2, B2] = Transformer.parallel(this :: other :: Nil)
	def parallelEither[In2 <: In, C](other: Transformer[In2, C]): Transformer[In2, Either[B, C]] = Transformer.parallelEither(this, other)
	def withFilter(p: B => Boolean): Transformer[In, B] = andThen(Transformer.filter(p))
	def unwrapSafe[T](implicit ev: B <:< Try[T]): Transformer[In, T] = {
		asInstanceOf[Transformer[In, Try[T]]].andThen(Transformer.unwrapSafe[T])
	}
	def wrapSafe: Transformer[In, Try[B]] = andThen(Transformer.wrapSafe)
	def withSideEffect(effect: B => Any): Transformer[In, B] = andThen(Transformer.sideEffect(effect))

	def consumeToList: Consumer[In, List[B]] = andThen(Consumer.toList)
	def consumeFirst: Consumer[In, B] = andThen(Consumer.first)
	def consumeFirstOption: Consumer[In, Option[B]] = andThen(Consumer.firstOption)
	def consumeAsFold[R](init: R)(f: (R, B) => R): Consumer[In, R] = andThen(Consumer.fold(init, f))
	def consumeForEach(f: B => Any): Consumer[In, Unit] = andThen(Consumer.foreach(f))
}

object Transformer {
	def take[A](max: Int): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new TakeNHandler[A, Out](max, next)
		}
		override def toString = s"Take($max)"
	}

	def takeWhile[A](p: A => Boolean): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new TakeWhileHandler[A, Out](p, next)
		}
		override def toString = s"TakeWhile($p)"
	}

	def drop[A](numToDrop: Int): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new DropNHandler(numToDrop, next)
		}
		override def toString = s"Drop($numToDrop)"
	}

	def dropWhile[A](p: A => Boolean): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new DropWhileHandler(p, next)
		}
		override def toString = s"DropWhile($p)"
	}

	def filter[A](p: A => Boolean): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new FilteringHandler[A, Out](p, next)
		}
		override def toString = s"Filter($p)"
	}

	/** Transformer that feeds all inputs to all of the transformers in `ts`,
	  * passing all transformed inputs from each to a downstream handler.
	  * Each of the transformers should have the same transformed type `T`.
	  * If your transformers all have different types, consider mapping
	  * them to a sealed trait/coproduct/Either. If you have exactly two
	  * transformers to merge, use `MergeEither` instead.
	  *
	  * @param toMerge A list of Transformers to be run in parallel
	  * @tparam A The input type
	  * @tparam T The common "transformed" type
	  */
	def parallel[A, T](toMerge: List[Transformer[A, T]]): Transformer[A, T] = new Transformer[A, T] {
		def makeHandler[Out](next: Handler[T, Out]): Handler[A, Out] = {
			new ParallelTransformerHandler[A, T, Out](next, toMerge)
		}
	}

	/** Convenience version of `Parallel` for exactly two transformers of arbitrary types.
	  * Results from `t1` will be wrapped as `Left`, and results from `t2` will be wrapped as `Right`.
	  * The downstream handler will receive results of type `Either[T1, T2]`.
	  *
	  * @param left The "left" transformer
	  * @param right The "right" transformer
	  * @tparam A The input type
	  * @tparam L The "left" transformer's "transformed" type
	  * @tparam R The "right" transformer's "transformed" type
	  */
	def parallelEither[A, L, R](left: Transformer[A, L], right: Transformer[A, R]): Transformer[A, Either[L, R]] = new Transformer[A, Either[L, R]] {
		def makeHandler[Out](next: Handler[Either[L, R], Out]): Handler[A, Out] = {
			new ParallelTransformerHandler[A, Either[L, R], Out](next, List(
				left.map(Left(_)),
				right.map(Right(_))
			))
		}
	}

	def map[A, B](f: A => B): Transformer[A, B] = new Transformer[A, B] {
		def makeHandler[Out](next: Handler[B, Out]): Handler[A, Out] = {
			new MappedTransformerHandler(f, next)
		}
		override def toString = s"Map($f)"
	}

	def collect[A, B](pf: PartialFunction[A, B]): Transformer[A, B] = new Transformer[A, B] {
		def makeHandler[Out](next: Handler[B, Out]): Handler[A, Out] = {
			new CollectHandler(pf, next)
		}
	}

	def scan[S, A](init: S)(f: (S, A) => S): Transformer[A, S] = new Transformer[A, S] {
		def makeHandler[Out](next: Handler[S, Out]): Handler[A, Out] = {
			new ScanningHandler(init, f, next)
		}
	}

	def unwrapSafe[A]: Transformer[Try[A], A] = new Transformer[Try[A], A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[Try[A], Out] = {
			new UnwrapSafeTransformerHandler(next)
		}
		override def toString = "UnwrapSafe"
	}

	def wrapSafe[A]: Transformer[A, Try[A]] = new Transformer[A, Try[A]] {
		def makeHandler[Out](next: Handler[Try[A], Out]): Handler[A, Out] = {
			new WrapSafeTransformerHandler(next)
		}
		override def toString = "WrapSafe"
	}

	def sideEffect[A](effect: A => Any): Transformer[A, A] = new Transformer[A, A] {
		def makeHandler[Out](next: Handler[A, Out]): Handler[A, Out] = {
			new SideEffectHandler(effect, next)
		}
		override def toString = s"SideEffect($effect)"
	}

	def sequenced[In: Stackable, T1, T2](consumer: Consumer[In, T1], getTransformer: T1 => Transformer[In, T2]): Transformer[In, T2] = new Transformer[In, T2] {
		def makeHandler[Out](next: Handler[T2, Out]): Handler[In, Out] = {
			val handler1 = consumer.makeHandler()
			def getHandler2(h1Result: T1) = getTransformer(h1Result).makeHandler(next)
			new SequencedInStackHandler(handler1, getHandler2)
		}
		override def toString = s"Sequenced($consumer, $getTransformer)"
	}
}
