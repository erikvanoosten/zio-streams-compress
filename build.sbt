lazy val scalaVersions = Seq("3.5.1", "2.13.15", "2.12.20")

ThisBuild / scalaVersion := scalaVersions.head
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / organization := "dev.zio"
name := (core.projectRefs.head / name).value

val V = new {
  val betterMonadicFor = "0.3.1"
  val brotli = "0.1.2"
  val commonsCompress = "1.27.1"
  val logbackClassic = "1.5.8"
  val lz4 = "1.8.0"
  val zio = "2.1.9"
  val zip4j = "2.11.5"
  val zstdJni = "1.5.6-6"
}

lazy val commonSettings: SettingsDefinition = Def.settings(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env
      .get("CI_VERSION")
      .collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := scmInfo.value.map(_.browseUrl),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/zio/zio-streams-compress"),
      "scm:git@github.com:zio/zio-streams-compress"
    )
  ),
  developers := List(
    Developer(
      id = "evanoosten",
      name = "Erik van Oosten",
      email = "noreply@example.com",
      url = url("https://github.com/zio/zio-streams-compress")
    )
  ),
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "dev.zio" %%% "zio-test" % V.zio % Test,
    "dev.zio" %%% "zio-test-sbt" % V.zio % Test,
  ),
  libraryDependencies ++= virtualAxes.?.value.getOrElse(Seq.empty).collectFirst {
    case VirtualAxis.ScalaVersionAxis(version, _) if version.startsWith("2.") =>
      compilerPlugin("com.olegpy" %% "better-monadic-for" % V.betterMonadicFor)
  },
  Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  Compile / doc / sources := Seq.empty,
  publishMavenStyle := true,
  publishTo := sonatypePublishToBundle.value,
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    sonatypeCredentialHost.value,
    username,
    password
  )).toList
)

lazy val root: Project =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      publishArtifact := false,
      publish / skip := true
    )
    .aggregate(core.projectRefs: _*)
    .aggregate(gzip.projectRefs: _*)
    .aggregate(zip.projectRefs: _*)
    .aggregate(zip4j.projectRefs: _*)
    .aggregate(tar.projectRefs: _*)
    .aggregate(zstd.projectRefs: _*)
    .aggregate(bzip2.projectRefs: _*)
    .aggregate(brotli.projectRefs: _*)
    .aggregate(lz4.projectRefs: _*)
    .aggregate(example.projectRefs: _*)

lazy val core = projectMatrix
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
    )
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)

lazy val gzip = projectMatrix
  .in(file("gzip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-gzip",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio
    )
  )
  .jvmPlatform(scalaVersions)
  //.jsPlatform(scalaVersions)

lazy val zip = projectMatrix
  .in(file("zip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-zip",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio
    )
  )
  .jvmPlatform(scalaVersions)

lazy val zip4j = projectMatrix
  .in(file("zip4j"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-zip4j",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "net.lingala.zip4j" % "zip4j" % V.zip4j
    )
  )
  .jvmPlatform(scalaVersions)

lazy val tar = projectMatrix
  .in(file("tar"))
  .dependsOn(core % "compile->compile;test->test")
  .dependsOn(gzip % "test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-tar",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "org.apache.commons" % "commons-compress" % V.commonsCompress
    )
  )
  .jvmPlatform(scalaVersions)

lazy val zstd = projectMatrix
  .in(file("zstd"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-zstd",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "com.github.luben" % "zstd-jni" % V.zstdJni
    )
  )
  .jvmPlatform(scalaVersions)

lazy val bzip2 = projectMatrix
  .in(file("bzip2"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-bzip2",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "org.apache.commons" % "commons-compress" % V.commonsCompress
    )
  )
  .jvmPlatform(scalaVersions)

lazy val brotli = projectMatrix
  .in(file("brotli"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-brotli",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "org.brotli" % "dec" % V.brotli
    )
  )
  .jvmPlatform(scalaVersions)

lazy val lz4 = projectMatrix
  .in(file("lz4"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "zio-streams-compress-lz4",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-streams" % V.zio,
      "org.lz4" % "lz4-java" % V.lz4
    )
  )
  .jvmPlatform(scalaVersions)

lazy val example = projectMatrix
  .in(file("example"))
  .dependsOn(gzip, tar, zip)
  .settings(commonSettings)
  .settings(
    publishArtifact := false,
    publish / skip := true
  )
  .settings(
    name := "zio-streams-compress-example",
  )
  .jvmPlatform(scalaVersions)
