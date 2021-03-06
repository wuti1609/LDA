import AssemblyKeys._
assemblySettings


name := "lda3"

version := "1.0"

scalaVersion := "2.10.4"
    

libraryDependencies ++= Seq(
  "com.github.scopt" % "scopt_2.10" % "3.2.0",
  "org.apache" % "spark" % "1.5.0",
  "edu.stanford.nlp" % "stanford-corenlp" % "3.4.1",
  "org.jsoup" % "jsoup" % "1.8.2",
  "mysql" % "mysql-connector-java" % "5.1.35"
)

resolvers += Resolver.mavenLocal
