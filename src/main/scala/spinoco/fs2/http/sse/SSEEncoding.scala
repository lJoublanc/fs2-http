package spinoco.fs2.http.sse

import fs2._
import scodec.Attempt
import scodec.bits.ByteVector
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.fs2.http.util.chunk2ByteVector

import scala.util.Try
import scala.concurrent.duration._


object SSEEncoding {



  /**
    * Encodes supplied stream of (messageTag, messageContent) to SSE Stream
    */
  def encode[F[_]]: Pipe[F, SSEMessage, Byte] = {
    _.flatMap {
      case SSEMessage.SSEData(data, event, id) =>
        val eventBytes = event.map { s => s"event: $s" }.toSeq
        val dataBytes = data.map { s => s"data: $s" }
        val idBytes = id.map { s => s"id: $s" }.toSeq
        Stream.chunk(ByteVectorChunk(ByteVector.view((
          eventBytes ++ dataBytes ++ idBytes).mkString("", "\n", "\n\n").getBytes
        )))

      case SSEMessage.SSERetry(duration) =>
        Stream.chunk(ByteVectorChunk(ByteVector.view(
          s"retry: ${duration.toMillis}\n\n".getBytes
        )))
    }
  }

  /** encodes stream of `A` as SSE Stream **/
  def encodeA[F[_], A](implicit E: SSEEncoder[A]): Pipe[F, A, Byte] = {
    _ flatMap { a => E.encode(a) match {
      case Attempt.Successful(msg) => Stream.emit(msg)
      case Attempt.Failure(err) => Stream.fail(new Throwable(s"Failed to encode $a : $err"))
    }} through encode
  }

  private val StartBom = ByteVector.fromValidHex("feff")

  /**
    * Decodes stream of bytes to SSE Messages
    */
  def decode[F[_]]: Pipe[F, Byte, SSEMessage] = {

    /** Drops initial Byte Order Mark, if present */
    def dropInitial(buff:ByteVector): Pipe[F, Byte, Byte] = {
      _.pull.unconsN(n=2,allowFewer=false).flatMap{
        case None => Pull.fail(new Throwable("SSE Socket did not contain any data"))
        case Some((firstTwoBytes, next)) =>
          val firstChunk = firstTwoBytes.toChunk
          if (chunk2ByteVector(firstChunk) == StartBom) next.pull.echo
          else Pull.output(firstChunk) >> next.pull.echo
      }.stream
    }

    /** Makes lines out of incoming bytes. Lines are utf-8 decoded * separated by \r\n or \n or \r */
    def mkLines: Pipe[F, Byte, String] =
      _ through text.utf8Decode[F] through text.lines[F]


    /** Makes lines for single event
      * Removes all the comments and splits by empty lines
      * Outgoing vectors are guaranteed to be nonEmpty
      * Note that this splits by empty lines.
      * The last event is emitted only if it is terminated by empty line
      */
    def mkEvents: Pipe[F, String, Seq[String]] = {
      _.filter(! _.startsWith(":")).split(_.isEmpty).map(_.toChunk.toList)
    }



    /** Constructs SSE Message
      * If message contains "retry" separate retry event is emitted
      * If message contains multiple "event" or "id" values, only last one is used.
      */
    def mkMessage: Pipe[F, Seq[String], SSEMessage] = {
       _.flatMap { lines =>
         val data =
           lines.map { line =>
             val idx = line.indexOf(':')
             if (idx < 0) line -> ""
             else {
               val (tag, data) = line.splitAt(idx)
               val dataNoColon = data.drop(1)
               val dataOut = if (dataNoColon.startsWith(" ")) dataNoColon.drop(1) else dataNoColon
               tag -> dataOut
             }
           }

         val (mData, mEvent, mId, mRetry) =
           data.foldLeft((Vector.empty[String], Option.empty[String], Option.empty[String], Option.empty[FiniteDuration])) {
             case ((d, event, id, retry), next) => next match {
               case ("data", v) => (d :+ v, event, id, retry)
               case ("event", v) => (d, Some(v), id, retry)
               case ("id", v) => (d, event, Some(v), retry)
               case ("retry", v) => (d, event, Some(v), Try { v.trim.toInt.millis }.toOption)
               case _ => (d, event, id, retry)
             }
           }


         Stream.emit(SSEMessage.SSEData(mData, mEvent, mId))
         .filter(m => m.data.nonEmpty || m.event.nonEmpty || m.id.nonEmpty) ++
         Stream.emits(mRetry.toSeq.map(SSEMessage.SSERetry.apply))

       }
    }

    _ through dropInitial(ByteVector.empty) through mkLines through mkEvents through mkMessage
  }

  /** decodes stream of sse messages to `A`, given supplied decoder **/
  def decodeA[F[_], A](implicit D: SSEDecoder[A]): Pipe[F, Byte, A] = {
    _ through decode flatMap { msg =>
      D.decode(msg) match {
        case Attempt.Successful(a) => Stream.emit(a)
        case Attempt.Failure(err) => Stream.fail(new Throwable(s"Failed do decode: $msg : $err"))
      }
    }
  }

}
