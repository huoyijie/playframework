package play.api.libs.iteratee

import play.api.libs.concurrent._

object Iteratee {

  def flatten[E, A](i: Promise[Iteratee[E, A]]): Iteratee[E, A] = new Iteratee[E, A] {

    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = i.flatMap(_.fold(done, cont, error))
  }

  def fold[E, A](state: A)(f: (A, E) => A): Iteratee[E, A] = {
    def step(s: A)(i: Input[E]): Iteratee[E, A] = i match {

      case Input.EOF => Done(s, Input.EOF)
      case Input.Empty => Cont[E, A](i => step(s)(i))
      case Input.El(e) => { val s1 = f(s, e); Cont[E, A](i => step(s1)(i)) }
    }
    (Cont[E, A](i => step(state)(i)))
  }

  def consume: Iteratee[Array[Byte], Array[Byte]] = {
    import scala.collection.mutable._
    fold[Array[Byte], ArrayBuffer[Byte]](ArrayBuffer[Byte]())(_ ++= _).mapDone(_.toArray)
  }

  def mapChunk_[E](f: E => Unit): Iteratee[E, Unit] = fold[E, Unit](())((_, e) => f(e))

}

trait Input[+E] {
  def map[U](f: (E => U)): Input[U] = this match {
    case Input.El(e) => Input.El(f(e))
    case Input.Empty => Input.Empty
    case Input.EOF => Input.EOF
  }
}

object Input {

  case class El[E](e: E) extends Input[E]
  case object Empty extends Input[Nothing]
  case object EOF extends Input[Nothing]

}

trait Iteratee[E, +A] {
  self =>
  def run[AA >: A]: Promise[AA] = fold((a, _) => Promise.pure(a),
    k => k(Input.EOF).fold((a1, _) => Promise.pure(a1),
      _ => error("diverging iteratee after Input.EOF"),
      (msg, e) => error(msg)),
    (msg, e) => error(msg))

  def feed[AA >: A](in: Input[E]): Promise[Iteratee[E, AA]] = {
    this <<: Enumerator.enumInput(in)
  }

  def fold[B](done: (A, Input[E]) => Promise[B],
    cont: (Input[E] => Iteratee[E, A]) => Promise[B],
    error: (String, Input[E]) => Promise[B]): Promise[B]

  def pureFold[B](done: (A, Input[E]) => B,
    cont: (Input[E] => Iteratee[E, A]) => B,
    error: (String, Input[E]) => B): Promise[B] =
    fold[B](
      (a, e) => Promise.pure(done(a, e)),
      k => Promise.pure(cont(k)),
      (msg, e) => Promise.pure(error(msg, e)))

  def pureFlatFold[B, C](done: (A, Input[E]) => Iteratee[B, C],
    cont: (Input[E] => Iteratee[E, A]) => Iteratee[B, C],
    error: (String, Input[E]) => Iteratee[B, C]): Iteratee[B, C] =
    Iteratee.flatten(pureFold(done, cont, error))

  def flatFold[B, C](done: (A, Input[E]) => Promise[Iteratee[B, C]],
    cont: (Input[E] => Iteratee[E, A]) => Promise[Iteratee[B, C]],
    error: (String, Input[E]) => Promise[Iteratee[B, C]]): Iteratee[B, C] = Iteratee.flatten(fold(done, cont, error))

  def mapDone[B](f: A => B): Iteratee[E, B] =
    Iteratee.flatten(this.fold((a, e) => Promise.pure(Done(f(a), e)),
      k => Promise.pure(Cont((in: Input[E]) => k(in).mapDone(f))),
      (err, e) => Promise.pure[Iteratee[E, B]](Error(err, e))))

  def flatMap[B](f: A => Iteratee[E, B]): Iteratee[E, B] = new Iteratee[E, B] {

    def fold[C](done: (B, Input[E]) => Promise[C],
      cont: (Input[E] => Iteratee[E, B]) => Promise[C],
      error: (String, Input[E]) => Promise[C]) =

      self.fold({
        case (a, Input.Empty) => f(a).fold(done, cont, error)
        case (a, e) => f(a).fold(
          (a, e) => done(a, e),
          k => k(e).fold(done, cont, error),
          error)
      },
        ((k) => cont(e => (k(e).flatMap(f)))),
        error)

  }

}

object Done {
  def apply[E, A](a: A, e: Input[E]): Iteratee[E, A] = new Iteratee[E, A] {
    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = done(a, e)

  }

}

object Cont {
  def apply[E, A](k: Input[E] => Iteratee[E, A]): Iteratee[E, A] = new Iteratee[E, A] {
    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = cont(k)

  }
}
object Error {
  def apply[E](msg: String, e: Input[E]): Iteratee[E, Nothing] = new Iteratee[E, Nothing] {
    def fold[B](done: (Nothing, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, Nothing]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = error(msg, e)

  }
}

trait Enumerator[+E] {

  parent =>
  def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]]
  def <<:[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] = apply(i)

  def andThen[F >: E](e: Enumerator[F]): Enumerator[F] = new Enumerator[F] {
    def apply[A, FF >: F](i: Iteratee[FF, A]): Promise[Iteratee[FF, A]] = parent.apply(i).flatMap(e.apply) //bad implementation, should remove Input.EOF in the end of first
  }

  def >>>[F >: E](e: Enumerator[F]): Enumerator[F] = andThen(e)

  def map[U](f: Input[E] => Input[U]) = new Enumerator[U] {
    def apply[A, UU >: U](it: Iteratee[UU, A]) = {

      case object OuterEOF extends Input[Nothing]
      type R = Iteratee[E, Iteratee[UU, A]]

      def step(ri: Iteratee[UU, A])(in: Input[E]): R =

        in match {
          case OuterEOF => Done(ri, Input.EOF)
          case any =>
            Iteratee.flatten(
              ri.fold((a, _) => Promise.pure(Done(ri, any)),
                k => {
                  val next = k(f(any))
                  next.fold((a, _) => Promise.pure(Done(next, in)),
                    _ => Promise.pure(Cont(step(next))),
                    (msg, _) => Promise.pure[R](Error(msg, in)))
                },
                (msg, _) => Promise.pure[R](Error(msg, any))))
        }

      parent.apply(Cont(step(it)))
        .flatMap(_.fold((a, _) => Promise.pure(a),
          k => k(OuterEOF).fold(
            (a1, _) => Promise.pure(a1),
            _ => error("diverging iteratee after Input.EOF"),
            (msg, e) => error(msg)),
          (msg, e) => error(msg)))
    }
  }

}

trait Enumeratee[In, Out] {
  def apply[A](inner: Iteratee[In, A]): Iteratee[Out, Iteratee[In, A]]
}
object Enumeratee {

  def breakE[E](p: E => Boolean) = new Enumeratee[E, E] {
    def apply[A](inner: Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = {
      def step(inner: Iteratee[E, A])(in: Input[E]): Iteratee[E, Iteratee[E, A]] = {
        in match {
          case Input.El(e) if (p(e)) => Done(inner, in)
          case _ =>
            inner.flatFold((_, _) => Promise.pure(Done(inner, in)),
              k => Promise.pure(Cont(step(k(in)))),
              (_, _) => Promise.pure(Done(inner, in)))
        }

      }
      Cont(step(inner))

    }

  }
}
object Enumerator {

  def enumInput[E](e: Input[E]) = new Enumerator[E] {
    def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] =
      i.fold((a, e) => Promise.pure(i),
        k => Promise.pure(k(e)),
        (_, _) => Promise.pure(i))

  }

  def empty[A] = enumInput[A](Input.EOF)

  def apply[E](in: E*): Enumerator[E] = new Enumerator[E] {

    def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] = enumerate(in, i)

  }
  def enumerate[E, A]: (Seq[E], Iteratee[E, A]) => Promise[Iteratee[E, A]] = { (l, i) =>
    l.foldLeft(Promise.pure(i))((i, e) =>
      i.flatMap(_.fold((_, _) => i,
        k => Promise.pure(k(Input.El(e))),
        (_, _) => i)))
  }
}

class CallbackEnumerator[E](
  onComplete: => Unit = () => (),
  onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) extends Enumerator[E] {

  var iteratee: Iteratee[E, _] = _
  var promise: Promise[Iteratee[E, _]] with Redeemable[Iteratee[E, _]] = _

  def apply[A, EE >: E](it: Iteratee[EE, A]): Promise[Iteratee[EE, A]] = {
    iteratee = it.asInstanceOf[Iteratee[E, _]]
    val newPromise = new STMPromise[Iteratee[EE, A]]()
    promise = newPromise.asInstanceOf[Promise[Iteratee[E, _]] with Redeemable[Iteratee[E, _]]]
    newPromise
  }

  def close() {
    if (iteratee == null) {
      iteratee.feed(Input.EOF).map { result =>
        promise.redeem(result)
      }
      iteratee = null
      promise = null
    }
  }

  def push(item: E): Boolean = {
    if (iteratee != null) {
      iteratee = iteratee.pureFlatFold[E, Any](

        // DONE
        (a, in) => {
          onComplete
          Done(a, in)
        },

        // CONTINUE
        k => {
          val next = k(Input.El(item))
          next.pureFlatFold(
            (a, in) => {
              onComplete
              next
            },
            _ => next,
            (_, _) => next)
        },

        // ERROR
        (e, in) => {
          onError(e, in)
          Error(e, in)
        })
      true
    } else {
      false
    }
  }

}

object Parsing {

  trait MatchInfo[A] { def content: A }
  case class Matched[A](val content: A) extends MatchInfo[A]
  case class Unmatched[A](val content: A) extends MatchInfo[A]

  def search(needle: Array[Byte]): Enumeratee[MatchInfo[Array[Byte]], Array[Byte]] = new Enumeratee[MatchInfo[Array[Byte]], Array[Byte]] {
    val needleSize = needle.size
    val fullJump = needleSize
    val jumpBadCharecter: (Byte => Int) = {
      val map = Map(needle.dropRight(1).reverse.zipWithIndex: _*) //remove the last
      byte => map.get(byte).map(_ + 1).getOrElse(fullJump)
    }

    def apply[A](inner: Iteratee[MatchInfo[Array[Byte]], A]): Iteratee[Array[Byte], Iteratee[MatchInfo[Array[Byte]], A]] = {

      Iteratee.flatten(inner.fold((a, e) => Promise.pure(Done(Done(a, e), Input.Empty: Input[Array[Byte]])),
        k => Promise.pure(Cont(step(Array[Byte](), Cont(k)))),
        (err, r) => throw new Exception()))

    }
    def scan(previousMatches: List[MatchInfo[Array[Byte]]], piece: Array[Byte], startScan: Int): (List[MatchInfo[Array[Byte]]], Array[Byte]) = {
      val fullMatch = Range(needleSize - 1, -1, -1).forall(scan => needle(scan) == piece(scan + startScan))
      if (fullMatch) {
        val (prefix, then) = piece.splitAt(startScan)
        val (matched, left) = then.splitAt(needleSize)
        val newResults = previousMatches ++ List(Unmatched(prefix), Matched(matched)) filter (!_.content.isEmpty)

        if (left.length < needleSize) (newResults, left) else scan(newResults, left, 0)

      } else {
        val jump = jumpBadCharecter(piece(startScan + needleSize - 1))
        val isFullJump = jump == fullJump
        val newScan = startScan + jump;
        if (newScan + needleSize - 1 > piece.length - 1) {
          if (isFullJump) (previousMatches ++ List(Unmatched(piece)), Array[Byte]())
          else {
            val (prefix, suffix) = (piece.splitAt(startScan))
            (previousMatches ++ List(Unmatched(prefix)), suffix)
          }
        } else scan(previousMatches, piece, newScan)
      }
    }

    def step[A](rest: Array[Byte], inner: Iteratee[MatchInfo[Array[Byte]], A])(in: Input[Array[Byte]]): Iteratee[Array[Byte], Iteratee[MatchInfo[Array[Byte]], A]] = {

      in match {
        case Input.Empty => Cont(step(rest, inner)) //here should rather pass Input.Empty along

        case Input.EOF => Done(inner, Input.El(rest))

        case Input.El(chunk) =>

          val all = rest ++ chunk
          def inputOrEmpty(a: Array[Byte]) = if (a.isEmpty) Input.Empty else Input.El(a)

          Iteratee.flatten(inner.fold((a, e) => Promise.pure(Done(Done(a, e), inputOrEmpty(rest))),
            k => {
              val (result, suffix) = scan(Nil, all, 0)
              val fed = result.filter(!_.content.isEmpty).foldLeft(Promise.pure(Array[Byte](), Cont(k))) { (p, m) =>
                p.flatMap(i => i._2.fold((a, e) => Promise.pure((i._1 ++ m.content, Done(a, e))),
                  k => Promise.pure((i._1, k(Input.El(m)))),
                  (err, e) => throw new Exception()))
              }
              fed.flatMap {
                case (ss, i) => i.fold((a, e) => Promise.pure(Done(Done(a, e), inputOrEmpty(ss ++ suffix))),
                  k => Promise.pure(Cont[Array[Byte], Iteratee[MatchInfo[Array[Byte]], A]]((in: Input[Array[Byte]]) => in match {
                    case Input.EOF => Done(k(Input.El(Unmatched(suffix))), Input.EOF) //suffix maybe empty
                    case other => step(ss ++ suffix, Cont(k))(other)
                  })),
                  (err, e) => throw new Exception())
              }
            },
            (err, e) => throw new Exception()))
      }
    }
  }
}
