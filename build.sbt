import sbt._

organization := "org.suecarter"

name := "simple-spray-websockets"

scalaVersion := "2.11.6"

version := "1.0-SNAPSHOT"

fork := true

libraryDependencies ++= Seq(
  "com.wandoulabs.akka" %% "spray-websocket" % "0.1.4",
  "io.spray" %% "spray-json" % "1.3.2",
  "io.spray" %% "spray-testkit" % "1.3.3" % "test",
  "ch.qos.logback" % "logback-classic" % "1.1.3" % "test",
  "org.slf4j" % "jul-to-slf4j" % "1.7.12" % "test",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.11" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.11" % "test",
  "org.scalatest" %% "scalatest" % "2.2.5" % "test"
)

scalacOptions in Compile ++= Seq(
  "-encoding", "UTF-8", "-target:jvm-1.6",
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
</developers>
