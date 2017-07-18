package io.buoyant.router.h2

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.buoyant.h2.{Frame, H2ReqRep, H2ResponseClassifier, Request, Response, Stream, param => h2param}
import com.twitter.finagle.buoyant.h2.StreamProxy._
import com.twitter.finagle.service.ResponseClass.Successful
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

object StreamStatsFilter {
  val role = Stack.Role("StreamStatsFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module2[param.Stats,
                      h2param.H2ResponseClassifier,
                      ServiceFactory[Request, Response]] {
      override def role = StreamStatsFilter.role
      override def description = "Record stats on h2 streams"
      override def make(statsP: param.Stats,
                        classifierP: h2param.H2ResponseClassifier,
                        next: ServiceFactory[Request, Response]) = {
        val param.Stats(stats) = statsP
        val h2param.H2ResponseClassifier(classifier) = classifierP
        new StreamStatsFilter(stats, classifier).andThen(next)
      }
    }
}

class StreamStatsFilter(statsReceiver: StatsReceiver, classifier: H2ResponseClassifier)
  extends SimpleFilter[Request, Response] {

  class StreamStats(stats: StatsReceiver, durationName: Option[String] = None) {

    private[this] val durationMs =
      stats.stat(s"${durationName.getOrElse("stream_duration")}_ms")
    private[this] val successes = stats.counter(s"stream_successes")
    private[this] val failures = stats.counter(s"stream_failures")

    @inline def apply(startT: Stopwatch.Elapsed)(result: Try[_]): Unit = {
      durationMs.add(startT().inMillis)
      result match {
        case Return(_) => successes.incr()
        case Throw(_) => failures.incr()
      }
    }

  }

  //   total number of requests received
  private[this] val reqCount = statsReceiver.counter("requests")

  private[this] val reqStreamStats =
    new StreamStats(statsReceiver.scope("request", "stream"))
  private[this] val rspStreamStats =
    new StreamStats(statsReceiver.scope("response", "stream"))
  private[this] val totalStreamStats =
    new StreamStats(statsReceiver.scope("stream"),
                    durationName = Some("total_latency"))
  private[this] val frameSizes = statsReceiver.stat("stream", "data_frame", "bytes")
  private[this] val reqLatency = statsReceiver.stat("request_latency_ms")
  // overall successes stat from response classifier
  private[this] val successes = statsReceiver.counter("successes")
  // overall failures stat from response classifier
  private[this] val failures = statsReceiver.counter("failures")

  override def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()
    req.stream.onEnd.respond(reqStreamStats(reqT))

    service(req)
      .transform {
        case Return(rsp0) =>
          val stream = rsp0.stream.onFrame {
            case Return(frame) if frame.isEnd =>
              classifier(H2ReqRep(req, Return((rsp0, Return(frame))))) match {
                case Successful(_) => successes.incr()
                case _ => failures.incr()
              }
            case Return(frame: Frame.Data) =>
              // if the frame is a data frame, update the data frame size stat
              frameSizes.add(frame.buf.length)
            case e @ Throw(_) =>
              // TODO: make this less repetitive
              classifier(H2ReqRep(req, Return((rsp0, e))))  match {
                case Successful(_) => successes.incr()
                case _ => failures.incr()
              }
          }
          Future.value(Response(rsp0.headers, stream))
        case Throw(e) =>
          // TODO: make this less repetitive
          classifier(H2ReqRep(req, Throw(e)))  match {
            case Successful(_) => successes.incr()
            case _ => failures.incr()
          }
          Future.exception(e)
      }
      .respond { result =>
        reqLatency.add(reqT().inMillis)
        val stream = result match {
          case Return(rsp) => req.stream.onEnd.join(rsp.stream.onEnd)
          case Throw(_) => req.stream.onEnd
        }
        val _ = stream.respond(totalStreamStats(reqT))
      }
      .onSuccess { rsp =>
        val rspT = Stopwatch.start()
        val _ = rsp.stream.onEnd.respond(rspStreamStats(rspT))
      }


  }

}
