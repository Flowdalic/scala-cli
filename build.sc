import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import $ivy.`io.get-coursier::coursier-launcher:2.0.16`
import $file.project.deps, deps.{Deps, Scala, graalVmVersion}
import $file.project.ghreleaseassets, ghreleaseassets.{upload, writeInZip}
import $file.project.nativeimage, nativeimage.generateNativeImage
import $file.project.publish, publish.{ghOrg, ghName, ScalaCliPublishModule}
import $file.project.settings, settings.cs

import de.tobiasroeser.mill.vcs.version.VcsVersion
import mill._, scalalib.{publish => _, _}

import scala.util.Properties


// Tell mill modules are under modules/
implicit def millModuleBasePath: define.BasePath =
  define.BasePath(super.millModuleBasePath.value / "modules")


object cli                    extends Cross[Cli](defaultCliScalaVersion)
object `jvm-tests`            extends JvmTests
object `native-tests`         extends NativeTests
object stubs                  extends JavaModule with ScalaCliPublishModule with PublishLocalNoFluff
object runner                 extends Cross[Runner](Scala.all: _*)
object `test-runner`          extends Cross[TestRunner](Scala.all: _*)
object bloopgun               extends Cross[Bloopgun](Scala.allScala2: _*)
object `line-modifier-plugin` extends Cross[LineModifierPlugin](Scala.all: _*)

// We should be able to switch to 2.13.x when bumping the scala-native version
def defaultCliScalaVersion = Scala.scala212

class Cli(val crossScalaVersion: String) extends CrossSbtModule with ScalaCliPublishModule {
  def moduleDeps = Seq(
    bloopgun(),
    `test-runner`()
  )
  def ivyDeps = Agg(
    Deps.ammCompiler,
    Deps.asm,
    Deps.bloopConfig,
    Deps.bsp4j,
    Deps.caseApp,
    Deps.coursierInterfaceSvmSubs,
    Deps.coursierJvm
      // scalaJsEnvNodeJs brings a guava version that conflicts with this
      .exclude(("com.google.collections", "google-collections")),
    Deps.coursierLauncher,
    Deps.jimfs, // scalaJsEnvNodeJs pulls jimfs:1.1, whose class path seems borked (bin compat issue with the guava version it depends on)
    Deps.jniUtils,
    Deps.nativeTestRunner,
    Deps.nativeTools,
    Deps.scalaJsEnvNodeJs,
    Deps.scalaJsLinker,
    Deps.scalaJsTestAdapter,
    Deps.scalametaTrees,
    Deps.scalaparse,
    Deps.svmSubs,
    Deps.swoval
  )
  def compileIvyDeps = Agg(
    Deps.svm
  )
  def mainClass = Some("scala.cli.ScalaCli")

  def constantsFile = T{
    val dest = T.dest / "Constants.scala"
    val code =
      s"""package scala.cli.internal
         |
         |/** Build-time constants. Generated by mill. */
         |object Constants {
         |  def version = "${publishVersion()}"
         |  def detailedVersion = "${VcsVersion.vcsState().format()}"
         |
         |  def bloopVersion = "${Deps.bloopConfig.dep.version}"
         |  def bspVersion = "${Deps.bsp4j.dep.version}"
         |  def scalaJsVersion = "${Deps.scalaJsLinker.dep.version}"
         |  def scalaNativeVersion = "${Deps.nativeTools.dep.version}"
         |
         |  def stubsOrganization = "${stubs.pomSettings().organization}"
         |  def stubsModuleName = "${stubs.artifactName()}"
         |  def stubsVersion = "${stubs.publishVersion()}"
         |
         |  def testRunnerOrganization = "${`test-runner`(defaultCliScalaVersion).pomSettings().organization}"
         |  def testRunnerModuleName = "${`test-runner`(defaultCliScalaVersion).artifactName()}"
         |  def testRunnerVersion = "${`test-runner`(defaultCliScalaVersion).publishVersion()}"
         |  def testRunnerMainClass = "${`test-runner`(defaultCliScalaVersion).mainClass().getOrElse(sys.error("No main class defined for test-runner"))}"
         |
         |  def runnerOrganization = "${runner(defaultCliScalaVersion).pomSettings().organization}"
         |  def runnerModuleName = "${runner(defaultCliScalaVersion).artifactName()}"
         |  def runnerVersion = "${runner(defaultCliScalaVersion).publishVersion()}"
         |  def runnerMainClass = "${runner(defaultCliScalaVersion).mainClass().getOrElse(sys.error("No main class defined for runner"))}"
         |
         |  def lineModifierPluginOrganization = "${`line-modifier-plugin`(defaultCliScalaVersion).pomSettings().organization}"
         |  def lineModifierPluginModuleName = "${`line-modifier-plugin`(defaultCliScalaVersion).artifactName()}"
         |  def lineModifierPluginVersion = "${`line-modifier-plugin`(defaultCliScalaVersion).publishVersion()}"
         |
         |  def semanticDbPluginOrganization = "${Deps.scalametaTrees.dep.module.organization.value}"
         |  def semanticDbPluginModuleName = "semanticdb-scalac"
         |  def semanticDbPluginVersion = "${Deps.scalametaTrees.dep.version}"
         |
         |  def localRepoResourcePath = "$localRepoResourcePath"
         |  def localRepoVersion = "${VcsVersion.vcsState().format()}"
         |}
         |""".stripMargin
    os.write(dest, code)
    PathRef(dest)
  }
  def generatedSources = super.generatedSources() ++ Seq(constantsFile())

  def nativeImage = T{
    val cp = runClasspath().map(_.path)
    val mainClass0 = mainClass().getOrElse(sys.error("Don't know what main class to use"))
    val dest = T.ctx().dest / "scala"
    val actualDest = T.ctx().dest / s"scala$extension"

    val localRepoJar0 = localRepoJar().path

    generateNativeImage(graalVmVersion, cp :+ localRepoJar0, mainClass0, dest, Seq(localRepoResourcePath))

    PathRef(actualDest)
  }

  def runWithAssistedConfig(args: String*) = T.command {
    val cp = (jar() +: upstreamAssemblyClasspath().toSeq).map(_.path).mkString(java.io.File.pathSeparator)
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))
    val graalVmHome = Option(System.getenv("GRAALVM_HOME")).getOrElse {
      import sys.process._
      Seq(cs, "java-home", "--jvm", s"graalvm-java11:$graalVmVersion", "--jvm-index", "cs").!!.trim
    }
    val outputDir = T.ctx().dest / "config"
    val command = Seq(
      s"$graalVmHome/bin/java",
      s"-agentlib:native-image-agent=config-output-dir=$outputDir",
      "-cp", cp,
      mainClass0
    ) ++ args
    os.proc(command.map(x => x: os.Shellable): _*).call(
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
    T.log.outputStream.println(s"Config generated in ${outputDir.relativeTo(os.pwd)}")
  }

  def runFromJars(args: String*) = T.command {
    val cp = (jar() +: upstreamAssemblyClasspath().toSeq).map(_.path).mkString(java.io.File.pathSeparator)
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))
    val command = Seq("java", "-cp", cp, mainClass0) ++ args
    os.proc(command.map(x => x: os.Shellable): _*).call(
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }

  def runClasspath = T{
    super.runClasspath() ++ Seq(localRepoJar())
  }

  def launcher = T{
    import coursier.launcher.{AssemblyGenerator, BootstrapGenerator, ClassPathEntry, Parameters, Preamble}
    import scala.util.Properties.isWin
    val cp = (runClasspath() ++ transitiveJars()).map(_.path).toSeq.filter(os.exists(_)).filter(!os.isDir(_))
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))

    val dest = T.ctx().dest / (if (isWin) "launcher.bat" else "launcher")

    val localRepoJar0 = localRepoJar().path

    val preamble = Preamble()
      .withOsKind(isWin)
      .callsItself(isWin)
    val entries = (cp :+ localRepoJar0).map(path => ClassPathEntry.Url(path.toNIO.toUri.toASCIIString))
    val loaderContent = coursier.launcher.ClassLoaderContent(entries)
    val params = Parameters.Bootstrap(Seq(loaderContent), mainClass0)
      .withDeterministic(true)
      .withPreamble(preamble)

    BootstrapGenerator.generate(params, dest.toNIO)

    PathRef(dest)
  }

  def standaloneLauncher = T{

    val cachePath = os.Path(coursier.cache.FileCache().location, os.pwd)
    def urlOf(path: os.Path): Option[String] = {
      if (path.startsWith(cachePath)) {
        val segments = path.relativeTo(cachePath).segments
        val url = segments.head + "://" + segments.tail.mkString("/")
        Some(url)
      }
      else None
    }

    import coursier.launcher.{AssemblyGenerator, BootstrapGenerator, ClassPathEntry, Parameters, Preamble}
    import scala.util.Properties.isWin
    val cp = (runClasspath() ++ transitiveJars()).map(_.path).toSeq.filter(os.exists(_)).filter(!os.isDir(_))
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))

    val dest = T.ctx().dest / (if (isWin) "launcher.bat" else "launcher")

    val localRepoJar0 = localRepoJar().path

    val preamble = Preamble()
      .withOsKind(isWin)
      .callsItself(isWin)
    val entries = (cp :+ localRepoJar0).map { path =>
      urlOf(path) match {
        case None =>
          val content = os.read.bytes(path)
          val name = path.last
          ClassPathEntry.Resource(name, os.mtime(path), content)
        case Some(url) => ClassPathEntry.Url(url)
      }
    }
    val loaderContent = coursier.launcher.ClassLoaderContent(entries)
    val params = Parameters.Bootstrap(Seq(loaderContent), mainClass0)
      .withDeterministic(true)
      .withPreamble(preamble)

    BootstrapGenerator.generate(params, dest.toNIO)

    PathRef(dest)
  }

  object test extends Tests {
    def ivyDeps = Agg(
      Deps.expecty,
      Deps.munit
    )
    def testFramework = "munit.Framework"
    def runClasspath = T{
      super.runClasspath() ++ Seq(localRepoJar())
    }
  }
}

trait CliTests extends SbtModule with ScalaCliPublishModule {
  def scalaVersion = sv
  def testLauncher: T[PathRef]
  def isNative = T{ false }

  def sv = Scala.scala213

  private def mainArtifactName = T{ artifactName() }
  object test extends Tests {
    def ivyDeps = Agg(
      Deps.expecty,
      Deps.munit,
      Deps.osLib,
      Deps.pprint
    )
    def testFramework = "munit.Framework"
    def forkEnv = super.forkEnv() ++ Seq(
      "SCALA_CLI" -> testLauncher().path.toString,
      "IS_NATIVE_SCALA_CLI" -> isNative().toString
    )
    def sources = T.sources {
      val name = mainArtifactName()
      super.sources().map { ref =>
        PathRef(os.Path(ref.path.toString.replace(name, "cli-tests")))
      }
    }
  }
}

trait NativeTests extends CliTests {
  def testLauncher = cli(defaultCliScalaVersion).nativeImage()
  def isNative = true
}

trait JvmTests extends CliTests {
  def testLauncher = cli(defaultCliScalaVersion).launcher()
}

trait PublishLocalNoFluff extends PublishModule {
  def emptyZip = T{
    import java.io._
    import java.util.zip._
    val dest = T.dest / "empty.zip"
    val baos = new ByteArrayOutputStream
    val zos = new ZipOutputStream(baos)
    zos.finish()
    zos.close()
    os.write(dest, baos.toByteArray)
    PathRef(dest)
  }
  // adapted from https://github.com/com-lihaoyi/mill/blob/fea79f0515dda1def83500f0f49993e93338c3de/scalalib/src/PublishModule.scala#L70-L85
  // writes empty zips as source and doc JARs
  def publishLocalNoFluff(localIvyRepo: String = null): define.Command[PathRef] = T.command {

    import mill.scalalib.publish.LocalIvyPublisher
    val publisher = localIvyRepo match {
      case null => LocalIvyPublisher
      case repo => new LocalIvyPublisher(os.Path(repo.replace("{VERSION}", publishVersion()), os.pwd))
    }

    publisher.publish(
      jar = jar().path,
      sourcesJar = emptyZip().path,
      docJar = emptyZip().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata(),
      extras = extraPublish()
    )

    jar()
  }
}

class Runner(val crossScalaVersion: String) extends CrossSbtModule with ScalaCliPublishModule with PublishLocalNoFluff {
  def mainClass = Some("scala.cli.runner.Runner")
  def ivyDeps =
    if (crossScalaVersion.startsWith("3.") && !crossScalaVersion.contains("-RC"))
      Agg(Deps.prettyStacktraces)
    else
      Agg.empty[Dep]
  def repositories = super.repositories ++ Seq(
    coursier.Repositories.sonatype("snapshots")
  )
  def sources = T.sources {
    val scala3DirNames =
      if (crossScalaVersion.startsWith("3.")) {
        val name =
          if (crossScalaVersion.contains("-RC")) "scala-3-unstable"
          else "scala-3-stable"
        Seq(name)
      } else Nil
    val extraDirs = scala3DirNames.map(name => PathRef(millSourcePath / "src" / "main" / name))
    super.sources() ++ extraDirs
  }
}

class TestRunner(val crossScalaVersion: String) extends CrossSbtModule with ScalaCliPublishModule with PublishLocalNoFluff {
  def ivyDeps = Agg(
    Deps.asm,
    Deps.testInterface
  )
  def mainClass = Some("scala.cli.testrunner.DynamicTestRunner")
}

class Bloopgun(val crossScalaVersion: String) extends CrossSbtModule with ScalaCliPublishModule {
  def ivyDeps = Agg(
    Deps.coursierInterface,
    Deps.snailgun
  )
  def mainClass = Some("scala.cli.bloop.bloopgun.Bloopgun")

  def constantsFile = T{
    val dest = T.dest / "Constants.scala"
    val code =
      s"""package scala.cli.bloop.bloopgun.internal
         |
         |/** Build-time constants. Generated by mill. */
         |object Constants {
         |  def bloopVersion = "${Deps.bloopConfig.dep.version}"
         |}
         |""".stripMargin
    os.write(dest, code)
    PathRef(dest)
  }
  def generatedSources = super.generatedSources() ++ Seq(constantsFile())
}

class LineModifierPlugin(val crossScalaVersion: String) extends CrossSbtModule with ScalaCliPublishModule with PublishLocalNoFluff {
  def compileIvyDeps =
    if (crossScalaVersion.startsWith("2."))
      Agg(Deps.scalac(crossScalaVersion))
    else
      Agg(Deps.scala3Compiler(crossScalaVersion))
}


def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
  T.command {
    import scala.concurrent.duration._
    val timeout = 10.minutes
    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSWORD")
    val data = define.Task.sequence(tasks.value)()

    publish.publishSonatype(
      credentials = credentials,
      pgpPassword = pgpPassword,
      data = data,
      timeout = timeout,
      log = T.ctx().log
    )
  }


def platformSuffix = {
  val arch = sys.props("os.arch").toLowerCase(java.util.Locale.ROOT) match {
    case "amd64" => "x86_64"
    case other => other
  }
  val os =
    if (Properties.isWin) "pc-win32"
    else if (Properties.isLinux) "pc-linux"
    else if (Properties.isMac) "apple-darwin"
    else sys.error(s"Unrecognized OS: ${sys.props("os.name")}")
  s"$arch-$os"
}

def extension =
  if (Properties.isWin) ".exe"
  else ""

def copyLauncher(directory: String = "artifacts") = T.command {
  val path = os.Path(directory, os.pwd)
  val nativeLauncher = cli(defaultCliScalaVersion).nativeImage().path
  val name = s"scala-$platformSuffix$extension"
  if (Properties.isWin)
    writeInZip(name, nativeLauncher, path / s"scala-$platformSuffix.zip")
  else {
    val dest = path / name
    os.copy(nativeLauncher, dest, createFolders = true, replaceExisting = true)
    os.proc("gzip", "-v", dest.toString).call(
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }
  ()
}

def uploadLaunchers(directory: String = "artifacts") = T.command {
  val path = os.Path(directory, os.pwd)
  val launchers = os.list(path).filter(os.isFile(_)).map { path =>
    path.toNIO -> path.last
  }
  val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
    sys.error("UPLOAD_GH_TOKEN not set")
  }
  val (tag, overwriteAssets) = {
    val ver = cli(defaultCliScalaVersion).publishVersion()
    if (ver.endsWith("-SNAPSHOT")) ("latest", true)
    else ("v" + ver, false)
  }
  upload(ghOrg, ghName, ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
}

private def stubsModules = {
  val javaModules = Seq(
    stubs
  )
  val crossModules = for {
    sv <- Scala.all
    proj <- Seq(runner, `test-runner`, `line-modifier-plugin`)
  } yield proj(sv)
  javaModules ++ crossModules
}

def publishStubs = T{
  val tasks = stubsModules.map(_.publishLocalNoFluff())
  define.Task.sequence(tasks)
}

def localRepo = T{
  val repoRoot = os.rel / "out" / "repo" / "{VERSION}"
  val tasks = stubsModules.map(_.publishLocalNoFluff(repoRoot.toString))
  define.Task.sequence(tasks)
}

def localRepoZip = T{
  val ver = runner(defaultCliScalaVersion).publishVersion()
  val something = localRepo()
  val repoDir = os.pwd / "out" / "repo" / ver
  val destDir = T.dest / ver / "repo.zip"
  val dest = destDir / "repo.zip"

  import java.io._
  import java.util.zip._
  os.makeDir.all(destDir)
  var fos: FileOutputStream = null
  var zos: ZipOutputStream = null
  try {
    fos = new FileOutputStream(dest.toIO)
    zos = new ZipOutputStream(new BufferedOutputStream(fos))
    os.walk(repoDir).filter(_ != repoDir).foreach { p =>
      val isDir = os.isDir(p)
      val name = p.relativeTo(repoDir).toString + (if (isDir) "/" else "")
      val entry = new ZipEntry(name)
      entry.setTime(os.mtime(p))
      zos.putNextEntry(entry)
      if (!isDir) {
        zos.write(os.read.bytes(p))
        zos.flush()
      }
      zos.closeEntry()
    }
    zos.finish()
  } finally {
    if (zos != null) zos.close()
    if (fos != null) fos.close()
  }

  PathRef(dest)
}

def localRepoResourcePath = "local-repo.zip"

def localRepoJar = T{
  val zip = localRepoZip().path
  val dest = T.dest / "repo.jar"

  import java.io._
  import java.util.zip._
  var fos: FileOutputStream = null
  var zos: ZipOutputStream = null
  try {
    fos = new FileOutputStream(dest.toIO)
    zos = new ZipOutputStream(new BufferedOutputStream(fos))

    val entry = new ZipEntry(localRepoResourcePath)
    entry.setTime(os.mtime(zip))
    zos.putNextEntry(entry)
    zos.write(os.read.bytes(zip))
    zos.flush()
    zos.closeEntry()

    zos.finish()
  } finally {
    if (zos != null) zos.close()
    if (fos != null) fos.close()
  }

  PathRef(dest)
}
