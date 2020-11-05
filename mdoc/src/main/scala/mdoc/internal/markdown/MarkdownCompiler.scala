package mdoc.internal.markdown

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import mdoc.Reporter
import mdoc.document.Document
import mdoc.document._
import mdoc.internal.document.DocumentBuilder
import mdoc.internal.document.MdocNonFatal
import mdoc.internal.pos.PositionSyntax
import mdoc.internal.pos.PositionSyntax._
import mdoc.internal.pos.TokenEditDistance
import scala.collection.JavaConverters._
import scala.collection.Seq
import scala.meta._
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.internal.util.{Position => GPosition}
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualDirectory
import sun.misc.Unsafe

object MarkdownCompiler {

  def default(): MarkdownCompiler = fromClasspath(classpath = "", scalacOptions = "")

  def buildDocument(
      compiler: MarkdownCompiler,
      reporter: Reporter,
      sectionInputs: List[SectionInput],
      instrumented: String,
      filename: String
  ): EvaluatedDocument = {
    val instrumentedInput = InstrumentedInput(filename, instrumented)
    reporter.debug(s"$filename: instrumented code\n$instrumented")
    val compileInput = Input.VirtualFile(filename, instrumented)
    val edit = TokenEditDistance.fromTrees(sectionInputs.map(_.source), compileInput)
    val doc = compiler.compile(compileInput, reporter, edit, "repl.Session$") match {
      case Some(cls) =>
        val ctor = cls.getDeclaredConstructor()
        ctor.setAccessible(true)
        val doc = ctor.newInstance().asInstanceOf[DocumentBuilder].$doc
        try {
          doc.build(instrumentedInput)
        } catch {
          case e: PositionedException =>
            val input = sectionInputs(e.section).input
            val pos =
              if (e.pos.isEmpty) {
                Position.Range(input, 0, 0)
              } else {
                val slice = Position.Range(
                  input,
                  e.pos.startLine,
                  e.pos.startColumn,
                  e.pos.endLine,
                  e.pos.endColumn
                )
                slice.toUnslicedPosition
              }
            reporter.error(pos, e.getCause)
            Document.empty(instrumentedInput)
          case MdocNonFatal(e) =>
            reporter.error(e)
            Document.empty(instrumentedInput)
        }
      case None =>
        // An empty document will render as the original markdown
        Document.empty(instrumentedInput)
    }
    EvaluatedDocument(doc, sectionInputs)
  }

  def fromClasspath(classpath: String, scalacOptions: String): MarkdownCompiler = {
    val fullClasspath =
      if (classpath.isEmpty) defaultClasspath(_ => true)
      else {
        val base = Classpath(classpath)
        val runtime = defaultClasspath(path => path.toString.contains("mdoc-runtime"))
        base ++ runtime
      }
    new MarkdownCompiler(fullClasspath.syntax, scalacOptions)
  }

  private def defaultClasspath(fn: Path => Boolean): Classpath = {
    val paths =
      getURLs(getClass.getClassLoader)
        .map(url => AbsolutePath(Paths.get(url.toURI)))
    Classpath(paths.toList)
  }

  /**
    * Utility to get SystemClassLoader/ClassLoader urls in java8 and java9+
    *   Based upon: https://gist.github.com/hengyunabc/644f8e84908b7b405c532a51d8e34ba9
    */
  private def getURLs(classLoader: ClassLoader): Seq[URL] = {
    if (classLoader.isInstanceOf[URLClassLoader]) {
      classLoader.asInstanceOf[URLClassLoader].getURLs()
      // java9+
    } else if (classLoader
        .getClass()
        .getName()
        .startsWith("jdk.internal.loader.ClassLoaders$")) {
      try {
        val field = classOf[Unsafe].getDeclaredField("theUnsafe")
        field.setAccessible(true)
        val unsafe = field.get(null).asInstanceOf[Unsafe]

        // jdk.internal.loader.ClassLoaders.AppClassLoader.ucp
        val ucpField = classLoader.getClass().getDeclaredField("ucp")
        val ucpFieldOffset: Long = unsafe.objectFieldOffset(ucpField)
        val ucpObject = unsafe.getObject(classLoader, ucpFieldOffset)

        // jdk.internal.loader.URLClassPath.path
        val pathField = ucpField.getType().getDeclaredField("path")
        val pathFieldOffset = unsafe.objectFieldOffset(pathField)
        val paths: Seq[URL] = unsafe
          .getObject(ucpObject, pathFieldOffset)
          .asInstanceOf[java.util.ArrayList[URL]]
          .asScala

        paths
      } catch {
        case ex: Exception =>
          ex.printStackTrace()
          Nil
      }
    } else {
      Nil
    }
  }

}

class MarkdownCompiler(
    classpath: String,
    scalacOptions: String,
    target: AbstractFile = new VirtualDirectory("(memory)", None)
) {
  private val settings = new Settings()
  settings.Yrangepos.value = true
  settings.deprecation.value = true // enable detailed deprecation warnings
  settings.unchecked.value = true // enable detailed unchecked warnings
  settings.outputDirs.setSingleOutput(target)
  settings.classpath.value = classpath
  // enable -Ydelambdafy:inline to avoid future timeouts, see:
  //   https://github.com/scala/bug/issues/9824
  //   https://github.com/scalameta/mdoc/issues/124
  settings.Ydelambdafy.value = "inline"
  settings.processArgumentString(scalacOptions)

  private val sreporter = new FilterStoreReporter(settings)
  var global = new Global(settings, sreporter)
  private def reset(): Unit = {
    global = new Global(settings, sreporter)
  }
  private val appClasspath: Array[URL] = classpath
    .split(File.pathSeparator)
    .map(path => new File(path).toURI.toURL)
  private val appClassLoader = new URLClassLoader(
    appClasspath,
    this.getClass.getClassLoader
  )

  private def clearTarget(): Unit = target match {
    case vdir: VirtualDirectory => vdir.clear()
    case _ =>
  }

  private def toSource(input: Input): BatchSourceFile = {
    val filename = Paths.get(input.syntax).getFileName.toString
    new BatchSourceFile(filename, new String(input.chars))
  }

  def fail(original: Seq[Tree], input: Input, sectionPos: Position): String = {
    sreporter.reset()
    val g = global
    val run = new g.Run
    run.compileSources(List(toSource(input)))
    val out = new ByteArrayOutputStream()
    val ps = new PrintStream(out)
    val edit = TokenEditDistance.fromTrees(original, input)
    sreporter.infos.foreach {
      case sreporter.Info(pos, msgOrNull, gseverity) =>
        val msg = nullableMessage(msgOrNull)
        val mpos = toMetaPosition(edit, pos)
        if (sectionPos.contains(mpos) || gseverity == sreporter.ERROR) {
          val severity = gseverity.toString().toLowerCase
          val formatted = PositionSyntax.formatMessage(mpos, severity, msg, includePath = false)
          ps.println(formatted)
        }
    }
    out.toString()
  }

  def compileSources(input: Input, vreporter: Reporter, edit: TokenEditDistance): Unit = {
    clearTarget()
    sreporter.reset()
    val g = global
    val run = new g.Run
    run.compileSources(List(toSource(input)))
    report(vreporter, input, edit)
  }

  def compile(
      input: Input,
      vreporter: Reporter,
      edit: TokenEditDistance,
      className: String,
      retry: Int = 0
  ): Option[Class[_]] = {
    reset()
    compileSources(input, vreporter, edit)
    if (!sreporter.hasErrors) {
      val loader = new AbstractFileClassLoader(target, appClassLoader)
      try {
        Some(loader.loadClass(className))
      } catch {
        case _: ClassNotFoundException =>
          if (retry < 1) {
            reset()
            compile(input, vreporter, edit, className, retry + 1)
          } else {
            vreporter.error(
              s"${input.syntax}: skipping file, the compiler produced no classfiles " +
                "and reported no errors to explain what went wrong during compilation. " +
                "Please report an issue to https://github.com/scalameta/mdoc/issues."
            )
            None
          }
      }
    } else {
      None
    }
  }

  private def toMetaPosition(edit: TokenEditDistance, pos: GPosition): Position = {
    def toOffsetPosition(offset: Int): Position = {
      edit.toOriginal(offset) match {
        case Left(_) =>
          Position.None
        case Right(p) =>
          p.toUnslicedPosition
      }
    }
    if (pos.isDefined) {
      if (pos.isRange) {
        (edit.toOriginal(pos.start), edit.toOriginal(pos.end - 1)) match {
          case (Right(start), Right(end)) =>
            Position.Range(start.input, start.start, end.end).toUnslicedPosition
          case (_, _) =>
            toOffsetPosition(pos.point)
        }
      } else {
        toOffsetPosition(pos.point)
      }
    } else {
      Position.None
    }
  }

  private def nullableMessage(msgOrNull: String): String =
    if (msgOrNull == null) "" else msgOrNull
  private def report(vreporter: Reporter, input: Input, edit: TokenEditDistance): Unit = {
    sreporter.infos.foreach {
      case sreporter.Info(pos, msgOrNull, severity) =>
        val msg = nullableMessage(msgOrNull)
        val mpos = toMetaPosition(edit, pos)
        val actualMessage =
          if (mpos == Position.None) {
            val line = pos.lineContent
            if (line.nonEmpty) {
              new CodeBuilder()
                .println(s"${input.syntax}:${pos.line} (mdoc generated code) $msg")
                .println(pos.lineContent)
                .println(pos.lineCaret)
                .toString
            } else {
              msg
            }
          } else {
            msg
          }
        severity match {
          case sreporter.ERROR => vreporter.error(mpos, actualMessage)
          case sreporter.INFO => vreporter.info(mpos, actualMessage)
          case sreporter.WARNING => vreporter.warning(mpos, actualMessage)
        }
      case _ =>
    }
  }

}
