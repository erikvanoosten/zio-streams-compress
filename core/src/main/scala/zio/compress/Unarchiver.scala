package zio.compress

import zio.stream._

trait Unarchiver[Size[A] <: Option[A], Underlying] {
  def unarchive: ZPipeline[Any, Throwable, Byte, (ArchiveEntry[Size, Underlying], ZStream[Any, Throwable, Byte])]
}
