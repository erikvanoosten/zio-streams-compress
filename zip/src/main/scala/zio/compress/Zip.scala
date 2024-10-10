package zio.compress

import zio._
import zio.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import zio.compress.Archiver.checkUncompressedSize
import zio.compress.JavaIoInterop._
import zio.compress.Zip._
import zio.stream._
import zio.stream.compression.CompressionLevel

import java.io.IOException
import java.nio.file.attribute.FileTime
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

sealed abstract class ZipMethod(val jValue: Int)
object ZipMethod {
  case object Stored extends ZipMethod(ZipEntry.STORED)
  case object Deflated extends ZipMethod(ZipEntry.DEFLATED)
}

object ZipArchiver {
  def make(method: ZipMethod = ZipMethod.Deflated, level: Option[CompressionLevel] = None): ZipArchiver =
    new ZipArchiver(method, level.filter(_ != CompressionLevel.DefaultCompression))
}

class ZipArchiver private (method: ZipMethod, level: Option[CompressionLevel]) extends Archiver[Some] {
  override def archive: ZPipeline[Any, Throwable, (ArchiveEntry[Some, Any], ZStream[Any, Throwable, Byte]), Byte] =
    viaOutputStream { outputStream =>
      val zipOutputStream = new ZipOutputStream(outputStream)
      zipOutputStream.setMethod(method.jValue)
      level.foreach(l => zipOutputStream.setLevel(l.jValue))
      zipOutputStream
    } { case (entryStream, zipOutputStream) =>
      entryStream
        .via(checkUncompressedSize)
        .mapZIO { case (archiveEntry, contentStream) =>
          def entry = archiveEntry.underlying[ZipEntry]
          ZIO.attemptBlocking(zipOutputStream.putNextEntry(entry)) *>
            contentStream.runForeachChunk(chunk => ZIO.attemptBlocking(zipOutputStream.write(chunk.toArray))) *>
            ZIO.attemptBlocking(zipOutputStream.closeEntry())
        }
        .runDrain
    }
}

object ZipUnarchiver {
  def make(chunkSize: Int = Defaults.DefaultChunkSize): ZipUnarchiver =
    new ZipUnarchiver(chunkSize)
}

class ZipUnarchiver private (chunkSize: Int) extends Unarchiver[Option, ZipEntry] {
  override def unarchive
      : ZPipeline[Any, Throwable, Byte, (ArchiveEntry[Option, ZipEntry], ZStream[Any, IOException, Byte])] =
    viaInputStream[(ArchiveEntry[Option, ZipEntry], ZStream[Any, IOException, Byte])](chunkSize) { inputStream =>
      for {
        zipInputStream <- ZIO.acquireRelease(ZIO.attemptBlocking(new ZipInputStream(inputStream))) { zipInputStream =>
          ZIO.attemptBlocking(zipInputStream.close()).orDie
        }
      } yield {
        ZStream.repeatZIOOption {
          for {
            entry <- ZIO.attemptBlocking(Option(zipInputStream.getNextEntry)).some
          } yield {
            val archiveEntry = ArchiveEntry.fromUnderlying(entry)
            (archiveEntry, ZStream.fromInputStream(zipInputStream, chunkSize))
          }
        }
      }
    }
}

object Zip {
  // The underlying information is lost if the name or isDirectory attribute of an ArchiveEntry is changed
  implicit val zipArchiveEntryToUnderlying: ArchiveEntryToUnderlying[ZipEntry] =
    new ArchiveEntryToUnderlying[ZipEntry] {
      override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): ZipEntry = {
        val zipEntry = underlying match {
          case zipEntry: ZipEntry if zipEntry.getName == entry.name && zipEntry.isDirectory == entry.isDirectory =>
            new ZipEntry(zipEntry)

          case _ =>
            val fileOrDirName = entry.name match {
              case name if entry.isDirectory && !name.endsWith("/") => name + "/"
              case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
              case name => name
            }
            new ZipEntry(fileOrDirName)
        }

        entry.uncompressedSize.foreach(zipEntry.setSize)
        entry.lastModified.map(FileTime.from).foreach(zipEntry.setLastModifiedTime)
        entry.lastAccess.map(FileTime.from).foreach(zipEntry.setLastAccessTime)
        entry.creation.map(FileTime.from).foreach(zipEntry.setCreationTime)
        zipEntry
      }
    }

  implicit val zipArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, ZipEntry] =
    new ArchiveEntryFromUnderlying[Option, ZipEntry] {
      override def archiveEntry(underlying: ZipEntry): ArchiveEntry[Option, ZipEntry] =
        ArchiveEntry(
          name = underlying.getName,
          isDirectory = underlying.isDirectory,
          uncompressedSize = Some(underlying.getSize).filterNot(_ == -1),
          lastModified = Option(underlying.getLastModifiedTime).map(_.toInstant),
          lastAccess = Option(underlying.getLastAccessTime).map(_.toInstant),
          creation = Option(underlying.getCreationTime).map(_.toInstant),
          underlying = underlying
        )
    }
}
