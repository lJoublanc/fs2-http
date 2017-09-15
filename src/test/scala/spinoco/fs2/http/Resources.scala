package spinoco.fs2.http

import cats.effect.{Effect,IO}

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import fs2.Scheduler


object Resources {

  val ES = Executors.newCachedThreadPool()

  implicit val Sch = Scheduler.fromScheduledExecutorService(Executors.newScheduledThreadPool(4))

  implicit val AG = AsynchronousChannelGroup.withThreadPool(ES)

  implicit val effect = Effect[IO]

  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global
}
