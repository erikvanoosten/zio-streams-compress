package zio.compress

import com.github.luben.zstd.{ZstdInputStream, ZstdOutputStream}
import zio.compress.JavaIoInterop.{viaInputStreamByte, viaOutputStreamByte}
import zio.stream._

object ZstdCompressor {
  def make(
      level: Option[Int] = None,
      workers: Option[Int] = None,
  ): ZstdCompressor =
    new ZstdCompressor(level, workers)
}

class ZstdCompressor private (level: Option[Int], workers: Option[Int]) extends Compressor {
  override def compress: ZPipeline[Any, Throwable, Byte, Byte] =
    viaOutputStreamByte { outputStream =>
      val zstdOutputStream = new ZstdOutputStream(outputStream)
      level.foreach(zstdOutputStream.setLevel)
      workers.foreach(zstdOutputStream.setWorkers)
      zstdOutputStream
    }
}

object ZstdDecompressor {
  def make(): ZstdDecompressor = new ZstdDecompressor()
}

class ZstdDecompressor private extends Decompressor {
  override def decompress: ZPipeline[Any, Throwable, Byte, Byte] =
    viaInputStreamByte(new ZstdInputStream(_))
}
