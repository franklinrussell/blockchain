ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "3.8.2"

val http4sVersion = "0.23.27"
val circeVersion  = "0.14.10"

lazy val root = (project in file("."))
  .settings(
    name := "summitcoin",

    libraryDependencies ++= Seq(
      // HTTP server — Ember is the recommended CE3 backend for http4s
      "org.http4s"     %% "http4s-ember-server" % http4sVersion,
      "org.http4s"     %% "http4s-circe"        % http4sVersion,
      "org.http4s"     %% "http4s-dsl"          % http4sVersion,

      // JSON serialization
      "io.circe"       %% "circe-core"          % circeVersion,
      "io.circe"       %% "circe-generic"       % circeVersion,
      "io.circe"       %% "circe-parser"        % circeVersion,

      // Logging (required by http4s/CE3 to suppress startup warnings)
      "org.typelevel"  %% "log4cats-slf4j"      % "2.7.0",
      "ch.qos.logback"  % "logback-classic"     % "1.5.12",
    ),

    // Suppress spurious "object X is already defined" from circe macro expansion
    scalacOptions += "-Wconf:msg=object.*is already defined:s",
  )
