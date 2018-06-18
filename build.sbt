lazy val akkaVersion = "2.5.13"

lazy val commonSettings = Seq(
  organization := "com.github.benhutchison",
  version := "0.1",
  scalaVersion := "2.12.6",
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
    name := "factor"
  )

lazy val example = (project in file("example"))
  .dependsOn(root)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %%  "monocle-core"  % "1.5.0",
      "com.github.julien-truffaut" %%  "monocle-macro" % "1.5.0",
      "org.typelevel" %% "cats-effect" % "1.0.0-RC2",
      "org.typelevel" %% "mouse" % "0.17",
      "org.specs2" %% "specs2-core" % "4.2.0" % "test",
    ),
    name := "sausagefactory"
  )

lazy val integrationTest = (project in file("integrationTest"))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .dependsOn(root)
  .dependsOn(example)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "1.0.0-RC2",
      "org.typelevel" %% "mouse" % "0.17",
    ),
    name := "integrationTest"
  )
