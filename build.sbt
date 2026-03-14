ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "3.8.2"

val http4sVersion  = "0.23.27"
val circeVersion   = "0.14.10"
val doobieVersion  = "1.0.0-RC4"

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

      // Database persistence (optional — used when DATABASE_URL is set)
      "org.tpolecat"   %% "doobie-core"         % doobieVersion,
      "org.tpolecat"   %% "doobie-postgres"     % doobieVersion,
      "org.tpolecat"   %% "doobie-hikari"       % doobieVersion,
      // Explicit PostgreSQL JDBC driver — must NOT be "provided" so sbt-assembly includes it
      "org.postgresql"  % "postgresql"           % "42.7.3",

      // Logging (required by http4s/CE3 to suppress startup warnings)
      "org.typelevel"  %% "log4cats-slf4j"      % "2.7.0",
      "ch.qos.logback"  % "logback-classic"     % "1.5.12",
    ),

    // Suppress spurious "object X is already defined" from circe macro expansion
    scalacOptions += "-Wconf:msg=object.*is already defined:s",

    // ── sbt-assembly (fat jar for Docker) ─────────────────────────────────────
    assembly / mainClass    := Some("SummitCoinNode"),
    assembly / assemblyJarName := "summitcoin.jar",
    assembly / assemblyMergeStrategy := {
      // ServiceLoader descriptors must be concatenated, not discarded
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      // HOCON reference.conf files must be concatenated (cats-effect, http4s, etc.)
      case "reference.conf"                      => MergeStrategy.concat
      // Java 9 module descriptors conflict across dependencies — discard all
      case x if x.endsWith("module-info.class")  => MergeStrategy.discard
      // Everything else in META-INF (manifests, licences) can be discarded
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    },
  )
