package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.{param => _, _}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw, Try}

/**
 * A filter that sets the ResponseClass generated by a
 * com.twitter.finagle.buoyant.h2.service.H2Classifier
 * into a header.
 *
 * This is pretty much just a HTTP/2 version of the HTTP/1  `ClassifierFilter`,
 * but because H2 classifiers must operate on both responses and response streams,
 * it's significantly more complex.
 */
object ClassifierFilter {
  val role = Stack.Role("H2Classifier")

  val SuccessClassHeader = "l5d-success-class"

  protected val log = Logger.get("H2ClassifierFilter")

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.H2Classifier, ServiceFactory[Request, Response]] {
      override val role: Stack.Role = ClassifierFilter.role
      override val description = "Sets the stream classification into a header"
      override def make(
        classifierP: param.H2Classifier,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
        val param.H2Classifier(classifier) = classifierP
        new ClassifierFilter(classifier).andThen(next)
      }
    }

  /**
   * Extractor for the `l5d-success-class` header field in streams/responses
   */
  private[this] object GetSuccessClass {
    @inline def getSuccessClass(headers: Headers): Option[ResponseClass] =
      headers.get(SuccessClassHeader).map { value =>
        val success = Try { value.toDouble }.getOrElse {
          log.warning(s"invalid `l5d-success-class` value $value, assumed failure")
          0.0
        }
        if (success > 0.0) ResponseClass.Successful(success)
        else ResponseClass.Failed(false)
      }

    @inline def unapply(frame: Frame): Option[ResponseClass] =
      frame match {
        case trailers: Trailers => getSuccessClass(trailers)
        case _ => None
      }

    @inline def unapply(message: Message): Option[ResponseClass] =
      getSuccessClass(message.headers)
  }

  /**
   * An `H2Classifier` that classifies responses/streams based on the
   * `l5d-success-class` header.
   *
   * @note that this classifier is undefined for responses or streams
   *       lacking this header.
   */
  object SuccessClassClassifier extends H2Classifier {
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(GetSuccessClass(c)))))) => c
      case _ => ResponseClass.NonRetryableFailure
    }

    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(_, Return(GetSuccessClass(c))) => c
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }
  }
}

class ClassifierFilter(classifier: H2Classifier) extends SimpleFilter[Request, Response] {
  import ClassifierFilter.SuccessClassHeader

  private[this] val successHeader: ResponseClass => String =
    _.fractionalSuccess.toString

  private[this] val classifyEarly: H2ReqRep => Option[String] =
    classifier.responseClassifier.lift(_).map(successHeader)

  @inline private[this] def classifyStream(
    req: Request,
    rep: Response,
    frame: Option[Frame] = None
  ): String = {
    val rr = H2ReqRepFrame(req, Return((rep, frame.map(Return(_)))))
    val respClass = classifier.streamClassifier(rr)
    successHeader(respClass)
  }

  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    svc(req).map { rep: Response =>
      classifyEarly(H2ReqRep(req, rep))
        .map { success =>
          // classify early - response class goes in headers
          rep.headers.set(SuccessClassHeader, success)
          rep
        }
        .getOrElse {
          // if the early classification attempt is not defined, attempt
          // late classification on the last frame in the response stream...
          if (rep.stream.isEmpty) {
            val success = classifyStream(req, rep)
            rep.headers.set(SuccessClassHeader, success)
            rep
          } else {
            // flatMap over the frames in the response stream, since we may
            // need to add a new Trailers frame to the end of the stream.
            val stream = rep.stream.flatMap {
              case frame: Trailers =>
                // if the current frame is a Trailers frame, we've reached
                // the end of the stream. just add the  success class header
                // to it.
                val success = classifyStream(req, rep, Some(frame))
                frame.set(SuccessClassHeader, success)
                Seq(frame)
              case frame: Frame.Data if frame.isEnd =>
                // if the current frame is a data frame with the end of stream
                // flag, then we need to send this frame followed by a new
                // Trailers frame
                val success = classifyStream(req, rep, Some(frame))
                // since this frame has the end of stream flag, we need to
                // replace it with a new data frame without that flag.
                // otherwise, the trailers we add to the stream will never
                // be reached.
                val frame2 = Frame.Data(frame.buf, eos = false)
                // release the old data frame, since it will be replaced.
                frame.release()
                Seq(frame2, Trailers(SuccessClassHeader -> success))
              case frame =>
                // if the current frame is not an end frame, just keep going...
                Seq(frame)
            }
            rep.headers.set("te", "trailers")
            Response(rep.headers, stream)
          }
        }
    }
  }
}
