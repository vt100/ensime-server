package org.ensime.core

import java.io.{ File, IOException }
import java.net.URLEncoder
import java.util.jar.JarFile

import org.ensime.api._

import akka.actor.{ Actor, ActorLogging }
import akka.io.IO
import com.google.common.io.{ ByteStreams, Files }

import org.ensime.config._
import org.ensime.server.protocol._
import org.ensime.server.protocol.ProtocolConst._
import spray.can.Http
import spray.http.HttpMethods._
import spray.http._

import scala.collection.mutable

class DocServer(
  val config: EnsimeConfig,
  startHttpServer: Boolean = true,
  // Should be a raw version string, as in 1.7.x
  forceJavaVersion: Option[String] = None
)
    extends Actor with ActorLogging with DocUsecaseHandling {
  import context.system

  var htmlToJar = mutable.HashMap[String, File]()

  val jarNameToJar = mutable.HashMap[String, File]()
  val allDocJars = config.modules.values.flatMap(_.docJars).toList

  sealed trait DocType
  case object Javadoc extends DocType
  case object Javadoc8 extends DocType
  case object Scaladoc extends DocType

  val docTypes = mutable.HashMap[String, DocType]()
  var port: Option[Int] = None

  // In javadoc docs, index.html has a comment that reads 'Generated by javadoc'
  private val JavadocComment = """Generated by javadoc (?:\(([0-9\.]+))?""".r.unanchored
  override def preStart(): Unit = {
    val t0 = System.currentTimeMillis()

    // On startup, do a fast scan (< 1s for 50 jars) to determine
    // the package contents of each jar, and whether it's a javadoc or
    // scaladoc.
    for (
      jarFile <- allDocJars if jarFile.exists()
    ) {
      try {
        val jar = new JarFile(jarFile)
        val jarFileName = jarFile.getName
        jarNameToJar(jarFileName) = jarFile
        docTypes(jarFileName) = Scaladoc
        val enumEntries = jar.entries()
        while (enumEntries.hasMoreElements) {
          val entry = enumEntries.nextElement()
          if (!entry.isDirectory) {
            val f = new File(entry.getName)
            val dir = f.getParent
            if (dir != null) {
              htmlToJar(entry.getName) = jarFile
            }
            // Check for javadocs
            if (entry.getName == "index.html") {
              val bytes = ByteStreams.toByteArray(jar.getInputStream(entry))
              new String(bytes) match {
                case JavadocComment(version: String) if version.startsWith("1.8") =>
                  docTypes(jarFileName) = Javadoc8
                case JavadocComment(_*) =>
                  docTypes(jarFileName) = Javadoc
                case _ =>
              }
            }
          }
        }
      } catch {
        case e: IOException => log.error("Failed to process doc jar: " + jarFile.getName)
      }
    }
    log.debug(s"Spent ${System.currentTimeMillis - t0}ms scanning ${allDocJars.length} doc jars.")
    if (startHttpServer) {
      IO(Http) ! Http.Bind(self, interface = "localhost", port = 0)
    }
  }

  private def javaFqnToPath(fqn: DocFqn): String = {
    if (fqn.typeName == "package") {
      fqn.pack.replace(".", "/") + "/package-summary.html"
    } else {
      fqn.pack.replace(".", "/") + "/" + fqn.typeName + ".html"
    }
  }

  protected def scalaFqnToPath(fqn: DocFqn): String = {
    if (fqn.typeName == "package") {
      fqn.pack.replace(".", "/") + "/package.html"
    } else fqn.pack.replace(".", "/") + "/" + fqn.typeName + ".html"
  }

  private def makeLocalUri(jar: File, sig: DocSigPair): String = {
    val jarName = jar.getName
    val docType = docTypes(jarName)
    val java = docType == Javadoc || docType == Javadoc8
    if (java) {
      val path = javaFqnToPath(sig.java.fqn)
      val anchor = sig.java.member.map { s =>
        "#" + URLEncoder.encode(if (docType == Javadoc8) toJava8Anchor(s) else s, "UTF-8")
      }.getOrElse("")
      s"http://localhost:${port.getOrElse(0)}/$jarName/$path$anchor"
    } else {
      val scalaSig = maybeReplaceWithUsecase(jar, sig.scala)
      val anchor = scalaSig.fqn.mkString +
        scalaSig.member.map {
          "@" + URLEncoder.encode(_, "UTF-8")
        }.getOrElse("")
      s"http://localhost:${port.getOrElse(0)}/$jarName/index.html#$anchor"
    }
  }

  private val PackRegexp = """^((?:[a-z0-9]+\.)+)""".r

  private def guessJar(sig: DocSigPair): Option[File] = {
    htmlToJar.get(scalaFqnToPath(sig.scala.fqn))
      .orElse(htmlToJar.get(javaFqnToPath(sig.java.fqn)))
  }

  private def resolveLocalUri(sig: DocSigPair): Option[String] = {
    log.debug("Resolving uri for: " + sig)
    guessJar(sig) match {
      case Some(jar) =>
        log.debug(s"Resolved to jar: $jar")
        Some(makeLocalUri(jar, sig))
      case _ =>
        log.debug(s"Failed to resolve doc jar for: $sig")
        None
    }
  }

  // Javadoc 8 changed the anchor format to remove illegal
  // url characters: parens, commas, brackets.
  // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=432056
  // and https://bugs.openjdk.java.net/browse/JDK-8025633
  private val Java8Chars = """(?:, |\(|\)|\[\])""".r
  private def toJava8Anchor(anchor: String): String = {
    Java8Chars.replaceAllIn(anchor, { m =>
      anchor(m.start) match {
        case ',' => "-"
        case '(' => "-"
        case ')' => "-"
        case '[' => ":A"
      }
    })
  }

  private def resolveWellKnownUri(sig: DocSigPair): Option[String] = {
    if (sig.java.fqn.javaStdLib) {
      val path = javaFqnToPath(sig.java.fqn)
      val rawVersion = forceJavaVersion.getOrElse(scala.util.Properties.javaVersion)
      val version =
        if (rawVersion.startsWith("1.8")) "8" else if (rawVersion.startsWith("1.7")) "7" else "6"
      val anchor = sig.java.member.map {
        m => "#" + URLEncoder.encode(if (version == "8") toJava8Anchor(m) else m, "UTF-8")
      }.getOrElse("")
      Some(s"http://docs.oracle.com/javase/$version/docs/api/$path$anchor")
    } else None
  }

  private val GetPathRegexp = """^/(.+?\.jar)/(.+)$""".r
  private def getJarEntry(path: String): Option[Array[Byte]] = {
    try {
      GetPathRegexp.findFirstMatchIn(path).flatMap { m =>
        val jarName = m.group(1)
        val tail = m.group(2)
        log.debug("Jar and entry: " + (jarName, tail))
        jarNameToJar.get(jarName).flatMap { f =>
          val jar = new JarFile(f)
          val entry = Option(jar.getJarEntry(tail))
          entry.map { e => ByteStreams.toByteArray(jar.getInputStream(e)) }
        }
      }
    } catch {
      case e: IOException => None
    }
  }

  override def receive = {
    case x: Any => try { process(x) } catch {
      case e: Exception => log.error(e, "Error during DocServer message processing")
    }
  }

  private def process(msg: Any): Unit = {
    msg match {
      case Http.Bound(addr) =>
        log.debug(s"DocServer listening at $addr")
        port = Some(addr.getPort)

      // When a new connection comes in we register ourselves as the connection
      // handler
      case _: Http.Connected => sender ! Http.Register(self)

      // Handle external HTTP requests.
      case HttpRequest(GET, p @ Uri.Path(_), _, _, _) =>
        log.debug("GET " + p)
        val response = getJarEntry(p.path.toString()).map { bytes =>
          HttpResponse(entity = HttpEntity(
            MediaTypes.forExtension(Files.getFileExtension(p.path.toString()))
              .getOrElse(MediaTypes.`text/html`), HttpData(bytes)
          ))
        }.getOrElse(HttpResponse(status = 404))
        sender ! response

      // Handle internal ENSIME requests.
      case DocUriReq(sig) =>
        try {
          sender ! resolveLocalUri(sig).orElse(resolveWellKnownUri(sig))
        } catch {
          case e: Exception =>
            // TODO The caller is expecting Option[String] this will generate a secondary error
            log.error(e, "Error handling RPC: " + sig)
            sender ! RPCError(
              ErrExceptionInRPC,
              "Error occurred in indexer. Check the server log."
            )
        }
      case other => log.error("Unknown message type: " + other)
    }
  }
}
