# zio-streams-compress

Integrations for several compression algorithms and archive formats for [ZIO Streams](https://zio.dev).
Heavily inspired by [fs2-compress](https://github.com/lhns/fs2-compress).

## Usage

### build.sbt

```sbt
libraryDependencies += "dev.zio" %% "zio-streams-compress-gzip" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-zip" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-zip4j" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-tar" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-bzip2" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-zstd" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-brotli" % "2.1.0"
libraryDependencies += "dev.zio" %% "zio-streams-compress-lz4" % "2.1.0"
```

Currently only jvm is supported. PRs for scala-js and scala-native are welcome.

### Example

```scala
import zio._
import zio.compress.{ArchiveEntry, GzipDecompressor, TarUnarchiver, ZipArchiver}
import zio.stream._

import java.nio.charset.StandardCharsets.UTF_8

object ExampleApp extends ZIOAppDefault {
  override def run =
    for {
      // Compress a file with GZIP
      _ <- ZStream
        .fromFileName("file")
        .via(GzipDecompressor.make().decompress)
        .run(ZSink.fromFileName("file.gz"))

      // List all items in a gzip tar archive:
      _ <- ZStream
        .fromFileName("file.tgz")
        .via(GzipDecompressor.make().decompress)
        .via(TarUnarchiver.make().unarchive)
        .mapZIO { case (archiveEntry, contentStream) =>
          for {
            content <- contentStream.runCollect
            _ <- Console.printLine(s"${archiveEntry.name} ${content.length}")
          } yield ()
        }
        .runDrain

      // Create a ZIP archive (use the zip4j version for password support)
      _ <- ZStream(archiveEntry("file1.txt", "Hello world!".getBytes(UTF_8)))
        .via(ZipArchiver.make().archive)
        .run(ZSink.fromFileName("file.zip"))
    } yield ()

  private def archiveEntry(
      name: String,
      content: Array[Byte]
  ): (ArchiveEntry[Some, Any], ZStream[Any, Throwable, Byte]) = {
    (ArchiveEntry(name, Some(content.length)), ZStream.fromIterable(content))
  }

}
```

## Running the tests

```shell
SBT_OPTS=-Xmx4G sbt test
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
