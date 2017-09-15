package spinoco.fs2.http.routing

import fs2._
import cats.effect.Sync
import shapeless.ops.function.FnToProduct
import shapeless.ops.hlist.Prepend
import shapeless.{::, HList, HNil}
import spinoco.fs2.http.HttpResponse
import spinoco.fs2.http.routing.MatchResult.{Failed, Success}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}


sealed trait Matcher[F[_], A] { self =>
  import MatchResult._
  import Matcher._


  /** transforms this matcher with supplied `f` **/
  def map[B](f: A => B): Matcher[F, B] =
    Bind[F, A, B](self, r => Matcher.ofResult(r.map(f)) )

  /** defined ad map { _ => b} **/
  def *>[B](b: B): Matcher[F, B] =
    self.map { _ => b }

  /** like `map` but allows to evaluate `F` **/
  def evalMap[B](f: A => F[B]): Matcher[F, B] =
    self.flatMap { a => Eval(f(a)) }

  /** transforms this matcher to another matcher with supplied `f` **/
  def flatMap[B](f: A => Matcher[F, B]):  Matcher[F, B]  =
    Bind[F, A, B](self.covaryValue[A], {
      case success:Success[A]  => f(success.result).covaryValue[B]
      case failed:Failed[F] => Matcher.respond[F](failed.response).covaryValue[B]
    })

  /** allias for flatMap **/
  def >>=[B](f: A => Matcher[F, B]):  Matcher[F, B]  =
    flatMap(f)

  /** defined as flatMap { _ => fb } **/
  def >>[B](fb: Matcher[F, B]):  Matcher[F, B]  =
    flatMap(_ => fb)

  /** defined as flatMap { a => fb map { _ => a} } **/
  def <<[B](fb: Matcher[F, B]):  Matcher[F, A]  =
    flatMap(a => fb map { _ => a})

  /** defined as advance.flatMap(f) **/
  def />>=[B](f: A => Matcher[F, B]):  Matcher[F, B]  =
    self.advance.flatMap(f)

  /** advances path by one segment, after this matches **/
  def advance: Matcher[F, A] =
    Advance(self)

  /** like flatMap, but allows to apply `f` when match failed **/
  def flatMapR[B](f: MatchResult[F,A] => Matcher[F, B]):  Matcher[F, B]  =
    Bind[F, A, B](self, f andThen (_.covaryValue[B]))

  /** applies `f` only when matcher fails to match **/
  def recover[F0[x] >: F[x], A0 >: A](f: HttpResponse[F0] => Matcher[F0, A0]):  Matcher[F0, A0] =
    Bind[F0, A, A0](self.covary[F0], {
      case success: Success[A]  => Matcher.success(success.result).covaryAll[F0,A0]
      case failed: Failed[F0] =>  f(failed.response).covaryAll[F0,A0]
    })

  /** matches and consumes current path segment throwing away `A` **/
  def / [B](other : Matcher[F, B]): Matcher[F, B] =
    self.advance.flatMap { _ => other }

  /** matches and consumes current path segment throwing away `B` **/
  def </[B](other:  Matcher[F, B]):  Matcher[F, A] =
    self.advance.flatMap { a => other.map { _ => a } }

  /** matches this or alternative **/
  def or[A0 >: A](alt : => Matcher[F, A0]): Matcher[F, A0] =
    Bind[F, A, A0](self.asInstanceOf[Matcher[F, A]], {
      case success: Success[A] => Matcher.ofResult(success)
      case failed: Failed[F] => alt
    })

  /** matches this or yields to None **/
  def ? : Matcher[F, Option[A]] =
    self.map(Some(_)) or Matcher.success(None: Option[A]).covary[F]

  /** Safely covaries the effect type, to turn a pure Matcher into an effectful one*/
  protected[routing] def covary[F0[x] >: F[x]] : Matcher[F0,A] = self.asInstanceOf[Matcher[F0,A]]

  /** Safely covaries the value type */
  protected[routing] def covaryValue[B >: A] : Matcher[F,B] = self.asInstanceOf[Matcher[F,B]]

  /** Safely covaries the effect and return type, to turn a pure Matcher into an effectful one*/
  protected[routing] def covaryAll[F0[x] >: F[x],B >: A] : Matcher[F0,B] = self.asInstanceOf[Matcher[F0,B]]
}


object Matcher {

  case class Match[F[_], A](f:(HttpRequestHeader, Stream[F, Byte]) => MatchResult[F, A]) extends Matcher[F, A]
  case class Bind[F[_], A, B](m: Matcher[F, A], f: MatchResult[F,A] => Matcher[F, B]) extends Matcher[F, B]
  case class Advance[F[_], A](m: Matcher[F, A]) extends Matcher[F, A]
  case class Eval[F[_], A](f: F[A]) extends Matcher[F, A]


  /** matcher that always succeeds **/
  def success[A](a: A): Matcher[Nothing, A] =
    Match[Nothing,A] { (_,_) => MatchResult.Success[A](a) }

  /** matcher that always responds (fails) with supplied response **/
  def respond[F[_]](response: HttpResponse[F]): Matcher[F, Nothing] =
    Match[F, Nothing] { (_, _) => MatchResult.Failed[F](response) }

  /** matcher that always responds with supplied status code **/
  def respondWith(code: HttpStatusCode): Matcher[Nothing, Nothing] =
    respond[Nothing](HttpResponse[Nothing](code))

  /** Matcher that always results in result supplied**/
  def ofResult[F[_], A](result:MatchResult[F,A]): Matcher[F, A] =
    Match[F, A] { (_, _) => result }

  /**
    * Interprets matcher to obtain the result.
    */
  def run[F[_], A](matcher: Matcher[F, A])(header: HttpRequestHeader, body: Stream[F, Byte])(implicit F: Sync[F]): F[MatchResult[F, A]] = {
    def go[B](current:Matcher[F,B], path: Uri.Path):F[(MatchResult[F, B], Uri.Path)] = {
      current match {
        case m: Match[F,B] => F.map(F.pure(m.f(header.copy(path = path), body))) { _ -> path }
        case m: Eval[F, B] => F.map(m.f)(b => Success(b) -> path)
        case m: Bind[F, _, B] => F.flatMap(F.suspend(go(m.m, path))){ case (r, path0) =>
          if (r.isSuccess)  go(m.f(r), path0)
          else go(m.f(r), path)
        }
        case m: Advance[F, B] => F.map(F.suspend(go(m.m, path))){ case (r, path0) =>
          if (r.isSuccess) {
            if (path0.segments.nonEmpty) r -> path0.copy(segments = path0.segments.tail)
            else if (path0.trailingSlash) r -> path0.copy(trailingSlash = false)
            else r -> path0 // no op
          }
          else r -> path
        }
      }
    }
    F.map(go(matcher, header.path)) { _._1 }
  }


  implicit class RequestMatcherHListSyntax[F[_], L <: HList](val self: Matcher[F, L]) extends AnyVal {
    /** combines two matcher'r result to resulting hlist **/
    def ::[B](other: Matcher[F, B]): Matcher[F, B :: L] =
      other.flatMap { b => self.map { l => b :: l } }

    /** combines this matcher with other matcher appending result of other matcher at the end **/
    def :+[B](other: Matcher[F, B])(implicit P : Prepend[L, B :: HNil]): Matcher[F, P.Out] =
      self.flatMap { l => other.map { b => l :+ b } }

    /** prepends result of other matcher before the result of this matcher **/
    def :::[L2 <: HList, HL <: HList](other: Matcher[F, L2])(implicit P: Prepend.Aux[L2, L, HL]): Matcher[F, HL] =
      other.flatMap { l2 => self.map { l => l2 ::: l } }

    /** combines two matcher'r result to resulting hlist, and advances path between them  **/
    def :/:[B](other : Matcher[F, B]): Matcher[F, B :: L] =
      other.advance.flatMap { b => self.map { l => b :: l } }

    /** like `map` but instead (L:HList) => B, takes ordinary function **/
    def mapH[FF, B](f: FF)(implicit F2P: FnToProduct.Aux[FF, L => B]): Matcher[F, B] =
      self.map { l => F2P(f)(l) }


  }


  implicit class RequestMatcherSyntax[F[_], A](val self: Matcher[F, A]) extends AnyVal {
    /** applies this matcher and if it is is successful then applies `other` returning result in HList B :: A :: HNil */
    def :: [B](other : Matcher[F, B]): Matcher[F, B :: A :: HNil] =
      other.flatMap { b => self.map { a => b :: a :: HNil } }

    def :/:[B](other : Matcher[F, B]): Matcher[F, B :: A :: HNil] =
      other.advance.flatMap { b => self.map { a => b :: a :: HNil } }



  }




}
