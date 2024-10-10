package zio.compress

import zio.stream._

class ArchiveSingleFileCompressor[Size[A] <: Option[A]] private (
    archiver: Archiver[Size],
    entry: ArchiveEntry[Size, Any]
) extends Compressor {
  override def compress: ZPipeline[Any, Throwable, Byte, Byte] =
    ZPipeline.fromFunction { stream =>
      ZStream((entry, stream)).via(archiver.archive)
    }
}

object ArchiveSingleFileCompressor {
  def apply[Size[A] <: Option[A]](
      archiver: Archiver[Size],
      entry: ArchiveEntry[Size, Any]
  ): ArchiveSingleFileCompressor[Size] =
    new ArchiveSingleFileCompressor(archiver, entry)

  def forName(archiver: Archiver[Option], name: String): ArchiveSingleFileCompressor[Option] =
    new ArchiveSingleFileCompressor(archiver, ArchiveEntry(name))

  def forName(archiver: Archiver[Some], name: String, size: Long): ArchiveSingleFileCompressor[Some] =
    new ArchiveSingleFileCompressor(archiver, ArchiveEntry(name, Some(size)))
}

class ArchiveSingleFileDecompressor[Size[A] <: Option[A], Underlying] private (
    unarchiver: Unarchiver[Size, Underlying]
) extends Decompressor {
  override def decompress: ZPipeline[Any, Throwable, Byte, Byte] =
    ZPipeline.fromFunction { stream =>
      stream
        .via(unarchiver.unarchive)
        .flatMap {
          case (entry, s) if entry.isDirectory => s.drain
          case (_, s) => ZStream(s)
        }
        .take(1)
        .flatten
    }
}

object ArchiveSingleFileDecompressor {
  def apply[Size[A] <: Option[A], Underlying](
      unarchiver: Unarchiver[Size, Underlying]
  ): ArchiveSingleFileDecompressor[Size, Underlying] =
    new ArchiveSingleFileDecompressor(unarchiver)
}
