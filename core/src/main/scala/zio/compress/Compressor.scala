package zio.compress

import zio.stream._

trait Compressor {
  def compress: ZPipeline[Any, Throwable, Byte, Byte]
}

object Compressor {
  def empty: Compressor = new Compressor {
    override def compress: ZPipeline[Any, Nothing, Byte, Byte] = ZPipeline.identity
  }
}
