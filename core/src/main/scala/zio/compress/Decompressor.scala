package zio.compress

import zio.stream._

trait Decompressor {
  def decompress: ZPipeline[Any, Throwable, Byte, Byte]
}

object Decompressor {
  def empty: Decompressor = new Decompressor {
    override def decompress: ZPipeline[Any, Nothing, Byte, Byte] = ZPipeline.identity
  }
}
