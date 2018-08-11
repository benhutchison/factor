import ReleaseTransformations._
import sbt._

lazy val akkaVersion = "2.5.13"
lazy val theScalaVersion = "2.12.6"

lazy val commonSettings = Seq(
  organization := "com.github.benhutchison",
  scalaVersion := theScalaVersion,
  scalacOptions ++= Seq("-feature", "-deprecation", "-language:implicitConversions", "-language:higherKinds"),
)

lazy val root = (project in file("."))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-remote" % akkaVersion,
      "org.typelevel" %% "cats-effect" % "1.0.0-RC2",
      "org.typelevel" %% "mouse" % "0.17",
    ),
    name := "factor",
    crossScalaVersions := Seq(theScalaVersion),
    publishMavenStyle := true,
    licenses += ("The Apache Software License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://github.com/benhutchison/factor")),
    developers := List(Developer("benhutchison", "Ben Hutchison", "brhutchison@gmail.com", url = url("https://github.com/benhutchison"))),
    scmInfo := Some(ScmInfo(url("https://github.com/benhutchison/factor"), "scm:git:https://github.com/benhutchison/factor.git")),
    releaseCrossBuild := true,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
    ),
  )

lazy val example = (project in file("example"))
  .dependsOn(root)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %%  "monocle-core"  % "1.5.0",
      "com.github.julien-truffaut" %%  "monocle-macro" % "1.5.0",
      "org.typelevel" %% "mouse" % "0.17",
      "org.specs2" %% "specs2-core" % "4.2.0" % "test",
    ),
    name := "sausagefactory",
    publish / skip := true,
  )

lazy val integrationTest = (project in file("integrationTest"))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .dependsOn(root)
  .dependsOn(example)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
    ),
    name := "integrationTest",
    publish / skip := true,
  )

ThisBuild / publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)
