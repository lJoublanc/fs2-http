package spinoco.fs2.http.internal

import fs2._
import cats.effect.IO
import spinoco.fs2.http
import spinoco.fs2.http.HttpRequest
import spinoco.protocol.http.Uri


object HttpClientApp extends App {

  import spinoco.fs2.http.Resources._



  http.client[IO]().flatMap { httpClient =>

    httpClient.request(HttpRequest.get(Uri.https("www.google.cz", "/"))).flatMap { resp =>
      Stream.eval(resp.bodyAsString)
    }.runLog.map {
      println
    }

  }.unsafeRunSync()
}
