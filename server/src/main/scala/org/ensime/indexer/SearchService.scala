package org.ensime.indexer

import java.sql.SQLException

import akka.actor.{ Props, ActorLogging, Actor, ActorSystem }
import akka.event.slf4j.SLF4JLogging
import org.apache.commons.vfs2._
import org.ensime.indexer.DatabaseService._
import pimpathon.file._

import org.ensime.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Provides methods to perform ENSIME-specific indexing tasks,
 * receives events that require an index update, and provides
 * searches against the index.
 *
 * We have an H2 database for storing relational information
 * and Lucene for advanced indexing.
 */
class SearchService(
  config: EnsimeConfig,
  resolver: SourceResolver
)(
  implicit
  actorSystem: ActorSystem,
  vfs: EnsimeVFS
) extends ClassfileIndexer
    with ClassfileListener
    with SLF4JLogging {

  private val version = "1.0"

  private val index = new IndexService(config.cacheDir / ("index-" + version))
  private val db = new DatabaseService(config.cacheDir / ("sql-" + version))

  implicit val workerEC = actorSystem.dispatchers.lookup("akka.search-service-dispatcher")

  /**
   * Indexes everything, making best endeavours to avoid scanning what
   * is unnecessary (e.g. we already know that a jar or classfile has
   * been indexed).
   *
   * The decision of what will be indexed is performed syncronously,
   * as is the removal of stale data, but the itself itself is
   * performed asyncronously.
   *
   * @return the number of files estimated to be (removed, indexed)
   *         from the index and database. This is only an estimate
   *         because we may not have TODO
   */
  def refresh(): Future[(Int, Int)] = {
    def scan(f: FileObject) = f.findFiles(EnsimeVFS.ClassfileSelector) match {
      case null => Nil
      case res => res.toList
    }

    // TODO visibility test/main and which module is viewed (a Lucene concern, not H2)

    val jarUris = config.allJars.map(vfs.vfile).map(_.getName.getURI)

    // remove stale entries: must be before index or INSERT/DELETE races
    val stale = for {
      known <- db.knownFiles()
      f = known.file
      name = f.getName.getURI
      if !f.exists || known.changed ||
        (name.endsWith(".jar") && !jarUris(name))
    } yield f

    log.info(s"removing ${stale.size} stale files from the index")
    if (log.isTraceEnabled)
      log.trace(s"STALE = $stale")

    // individual DELETEs in H2 are really slow
    val removing = stale.grouped(1000).map { files =>
      Future {
        index.remove(files)
        db.removeFiles(files)
      }(workerEC)
    }

    val removed = Future.sequence(removing).map(_ => stale.size)

    val bases = {
      config.modules.flatMap {
        case (name, m) =>
          m.targetDirs.flatMap { d => scan(vfs.vfile(d)) } ::: m.testTargetDirs.flatMap { d => scan(vfs.vfile(d)) } :::
            m.compileJars.map(vfs.vfile) ::: m.testJars.map(vfs.vfile)
      }
    }.toSet ++ config.javaLibs.map(vfs.vfile)

    // start indexing after all deletes have completed (not pretty)
    val indexing = removed.map { _ =>
      // could potentially do the db lookup in parallel
      bases.filter(db.outOfDate).toList map {
        case classfile if classfile.getName.getExtension == "class" => Future[Unit] {
          val check = FileCheck(classfile)
          val symbols = extractSymbols(classfile, classfile)
          persist(check, symbols)
        }(workerEC)

        case jar => Future[Unit] {
          log.debug(s"indexing $jar")
          val check = FileCheck(jar)
          val symbols = scan(vfs.vjar(jar)) flatMap (extractSymbols(jar, _))
          persist(check, symbols)
        }(workerEC)
      }
    }

    val indexed = indexing.flatMap { w => Future.sequence(w) }.map(_.size)
    val indexedAndCommitted = indexed map { count =>
      // delayed commits speedup initial indexing time
      log.debug("committing index to disk...")
      index.commit()
      log.debug("...done committing index")
      count
    }

    for {
      r <- removed
      i <- indexedAndCommitted
    } yield (r, i)
  }

  def refreshResolver(): Unit = resolver.update()

  def persist(check: FileCheck, symbols: List[FqnSymbol]): Unit = try {
    index.persist(check, symbols)
    db.persist(check, symbols)
  } catch {
    case e: SQLException =>
      // likely a timing issue or corner-case dupe FQNs
      log.warn(s"failed to insert $symbols ${e.getClass}: ${e.getMessage}")
  }

  private val blacklist = Set("sun/", "sunw/", "com/sun/")
  private val ignore = Set("$$anon$", "$$anonfun$", "$worker$")
  import org.ensime.util.RichFileObject._
  private def extractSymbols(container: FileObject, f: FileObject): List[FqnSymbol] = {
    f.pathWithinArchive match {
      case Some(relative) if blacklist.exists(relative.startsWith) => Nil
      case _ =>
        val name = container.getName.getURI
        val path = f.getName.getURI
        val (clazz, refs) = indexClassfile(f)

        val depickler = new ClassfileDepickler(f)

        val source = resolver.resolve(clazz.name.pack, clazz.source)
        val sourceUri = source.map(_.getName.getURI)

        // TODO: other types of visibility when we get more sophisticated
        if (clazz.access != Public) Nil
        else FqnSymbol(None, name, path, clazz.name.fqnString, None, None, sourceUri, clazz.source.line) ::
          clazz.methods.toList.filter(_.access == Public).map { method =>
            val descriptor = method.descriptor.descriptorString
            FqnSymbol(None, name, path, method.name.fqnString, Some(descriptor), None, sourceUri, method.line)
          } ::: clazz.fields.toList.filter(_.access == Public).map { field =>
            val internal = field.clazz.internalString
            FqnSymbol(None, name, path, field.name.fqnString, None, Some(internal), sourceUri, clazz.source.line)
          } ::: depickler.getTypeAliases.toList.filter(_.access == Public).map { rawType =>
            FqnSymbol(None, name, path, rawType.fqn, None, None, sourceUri, None)
          }

    }
  }.filterNot(sym => ignore.exists(sym.fqn.contains))

  // TODO: provide context (user's current module and main/test)
  /** free-form search for classes */
  def searchClasses(query: String, max: Int): List[FqnSymbol] = {
    val fqns = index.searchClasses(query, max)
    db.find(fqns) take max
  }

  /** free-form search for classes and methods */
  def searchClassesMethods(terms: List[String], max: Int): List[FqnSymbol] = {
    val fqns = index.searchClassesMethods(terms, max)
    db.find(fqns) take max
  }

  /** only for exact fqns */
  def findUnique(fqn: String): Option[FqnSymbol] = db.find(fqn)

  /* DELETE then INSERT in H2 is ridiculously slow, so we put all modifications
   * into a blocking queue and dedicate a thread to block on draining the queue.
   * This has the effect that we always react to a single change on disc but we
   * will work through backlogs in bulk.
   *
   * We always do a DELETE, even if the entries are new, but only INSERT if
   * the list of symbols is non-empty.
   */

  val backlogActor = actorSystem.actorOf(Props(new IndexingQueueActor(this)), "ClassfileIndexer")

  def delete(files: List[FileObject]): Unit = {
    index.remove(files)
    db.removeFiles(files)
  }

  def classfileAdded(f: FileObject): Unit = classfileChanged(f)

  def classfileRemoved(f: FileObject): Unit = {
    backlogActor ! FileUpdate(f, Nil)
  }

  def classfileChanged(f: FileObject): Unit = Future {
    val symbols = extractSymbols(f, f)
    backlogActor ! FileUpdate(f, symbols)
  }(workerEC)

  def shutdown(): Unit = {
    db.shutdown()
  }
}

case class FileUpdate(fileObject: FileObject, symbolList: List[FqnSymbol])
case object UpdateComplete

class IndexingQueueActor(searchService: SearchService) extends Actor with ActorLogging {
  var busy = false
  var queue = Vector[(FileObject, List[FqnSymbol])]()
  override def receive: Receive = {
    case update: FileUpdate =>
      queue = queue :+ (update.fileObject, update.symbolList)
      processQueue()
    case UpdateComplete =>
      busy = false
      processQueue()
  }

  def processQueue(): Unit = {
    if (!busy && queue.nonEmpty) {
      val batch = queue.take(500)
      queue = queue.drop(500)
      val selfRef = self
      Future {
        val work = {
          batch.groupBy(_._1).map {
            case (k, values) => values.last
          }
        }.toList

        log.info(s"Indexing ${work.size} classfiles")

        searchService.delete(work.map(_._1))

        work.collect {
          case (file, syms) if syms.nonEmpty =>
            searchService.persist(FileCheck(file), syms)
        }
        selfRef ! UpdateComplete
      }
    }
  }
}
