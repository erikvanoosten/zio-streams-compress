package zio.compress

import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.compress.archivers.{ArchiveEntry => CommonsArchiveEntry}
import zio._
import zio.compress.ArchiveEntry._
import zio.compress.Archiver.checkUncompressedSize
import zio.compress.JavaIoInterop._
import zio.compress.Tar._
import zio.stream._

import java.io.IOException
import java.nio.file.attribute.FileTime

object TarArchiver {
  def make(): TarArchiver =
    new TarArchiver()
}

class TarArchiver private extends Archiver[Some] {
  override def archive: ZPipeline[Any, Throwable, (ArchiveEntry[Some, Any], ZStream[Any, Throwable, Byte]), Byte] =
    viaOutputStream(new TarArchiveOutputStream(_)) { case (entryStream, tarOutputStream) =>
      entryStream
        .via(checkUncompressedSize)
        .mapZIO { case (archiveEntry, contentStream) =>
          def entry = archiveEntry.underlying[TarArchiveEntry]
          ZIO.attemptBlocking(tarOutputStream.putArchiveEntry(entry)) *>
            contentStream.runForeachChunk(chunk => ZIO.attemptBlocking(tarOutputStream.write(chunk.toArray))) *>
            ZIO.attemptBlocking(tarOutputStream.closeArchiveEntry())
        }
        .runDrain
    }
}

object TarUnarchiver {
  def make(chunkSize: Int = Defaults.DefaultChunkSize): TarUnarchiver =
    new TarUnarchiver(chunkSize)
}

class TarUnarchiver private (chunkSize: Int) extends Unarchiver[Option, TarArchiveEntry] {
  override def unarchive: ZPipeline[Any, Throwable, Byte, (ArchiveEntry[Option, TarArchiveEntry], ZStream[Any, IOException, Byte])] =
    viaInputStream[(ArchiveEntry[Option, TarArchiveEntry], ZStream[Any, IOException, Byte])](chunkSize) { inputStream =>
      for {
        tarInputStream <- ZIO.acquireRelease(ZIO.attemptBlocking(new TarArchiveInputStream(inputStream))) { tarInputStream =>
          ZIO.attemptBlocking(tarInputStream.close()).orDie
        }
      } yield {
        ZStream.repeatZIOOption {
          for {
            entry <- ZIO.attemptBlocking(Option(tarInputStream.getNextEntry)).some
          } yield {
            val archiveEntry = ArchiveEntry.fromUnderlying(entry)
            (archiveEntry, ZStream.fromInputStream(tarInputStream, chunkSize))
          }
        }
      }
    }
}

object Tar {
  // The underlying information is lost if the isDirectory attribute of an ArchiveEntry is changed
  implicit val tarArchiveEntryToUnderlying: ArchiveEntryToUnderlying[TarArchiveEntry] =
    new ArchiveEntryToUnderlying[TarArchiveEntry] {
      override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): TarArchiveEntry = {
        val fileOrDirName = entry.name match {
          case name if entry.isDirectory && !name.endsWith("/") => name + "/"
          case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
          case name => name
        }

        val tarEntry = underlying match {
          case tarEntry: TarArchiveEntry if tarEntry.isDirectory == entry.isDirectory =>
            // copy TarArchiveEntry
            val buffer = new Array[Byte](512) // TarArchiveOutputStream.RECORD_SIZE
            tarEntry.writeEntryHeader(buffer)
            val newTarEntry = new TarArchiveEntry(buffer)
            newTarEntry.setName(fileOrDirName)
            newTarEntry

          case _ =>
            new TarArchiveEntry(fileOrDirName)
        }

        entry.uncompressedSize.foreach(tarEntry.setSize)
        entry.lastModified.map(FileTime.from).foreach(tarEntry.setLastModifiedTime)
        entry.lastAccess.map(FileTime.from).foreach(tarEntry.setLastAccessTime)
        entry.creation.map(FileTime.from).foreach(tarEntry.setCreationTime)
        tarEntry
      }
    }

  implicit val tarArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, TarArchiveEntry] =
    new ArchiveEntryFromUnderlying[Option, TarArchiveEntry] {
      override def archiveEntry(underlying: TarArchiveEntry): ArchiveEntry[Option, TarArchiveEntry] =
        ArchiveEntry(
          name = underlying.getName,
          isDirectory = underlying.isDirectory,
          uncompressedSize = Some(underlying.getSize).filterNot(_ == CommonsArchiveEntry.SIZE_UNKNOWN),
          lastModified = Option(underlying.getLastModifiedTime).map(_.toInstant),
          lastAccess = Option(underlying.getLastAccessTime).map(_.toInstant),
          creation = Option(underlying.getCreationTime).map(_.toInstant),
          underlying = underlying
        )
    }
}
