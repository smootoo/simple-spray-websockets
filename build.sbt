import sbt._

organization := "org.suecarter"

name := "simple-spray-websockets"

scalaVersion := "2.11.7"

version := "1.0"

fork := true

val SPRAY_VERSION = "1.3.3"
val AKKA_VERSION = "2.3.11"

libraryDependencies ++= Seq(
  "io.spray" %% "spray-can" % SPRAY_VERSION,
  "io.spray" %% "spray-routing-shapeless2" % SPRAY_VERSION,
  "io.spray" %% "spray-json" % "1.3.2",
  "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION,
  "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test",
  "io.spray" %% "spray-testkit" % SPRAY_VERSION % "test",
  "ch.qos.logback" % "logback-classic" % "1.1.3" % "test",
  "org.slf4j" % "jul-to-slf4j" % "1.7.12" % "test",
  "com.typesafe.akka" %% "akka-slf4j" % AKKA_VERSION % "test",
  "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test",
  "org.scalatest" %% "scalatest" % "2.2.5" % "test",
  "org.specs2" %% "specs2-core" % AKKA_VERSION % "test"
)

dependencyOverrides ++= Set(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4"
)

scalacOptions in Compile ++= Seq(
  // we need 1.7 methods for compression
  "-encoding", "UTF-8", "-target:jvm-1.7",
  "-feature", "-deprecation",
  "-Xfatal-warnings",
  "-language:postfixOps"
)

//ScoverageSbtPlugin.ScoverageKeys.coverageFailOnMinimum := true

//ScoverageSbtPlugin.ScoverageKeys.coverageMinimum := 90

scalariformSettings

licenses := Seq(
  "MIT" -> url("http://opensource.org/licenses/MIT")
)

homepage := Some(url("http://github.com/smootoo/simple-spray-websockets"))

publishTo <<= version { v: String =>
  val nexus = "https://oss.sonatype.org/"
  if (v.contains("SNAP")) Some("snapshots" at nexus + "content/repositories/snapshots")
  else                    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

credentials += Credentials(
  "Sonatype Nexus Repository Manager", "oss.sonatype.org",
  sys.env.get("SONATYPE_USERNAME").getOrElse(""),
  sys.env.get("SONATYPE_PASSWORD").getOrElse("")
)

pomExtra :=
<scm>
  <url>git@github.com:smootoo/simple-spray-websockets.git</url>
  <connection>scm:git:git@github.com:smootoo/simple-spray-websockets.git</connection>
</scm>
<developers>
   <developer>
      <id>smootoo</id>
      <name>Sue Carter</name>
   </developer>
   <developer>
      <id>fommil</id>
      <name>Sam Halliday</name>
   </developer>
</developers>
