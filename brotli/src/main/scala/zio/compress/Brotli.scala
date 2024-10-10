package zio.compress

import zio.stream._
import org.brotli.dec.BrotliInputStream
import zio.compress.JavaIoInterop.viaInputStreamByte

//noinspection ScalaFileName
object BrotliDecompressor {
  def make(customDictionary: Option[Array[Byte]] = None): BrotliDecompressor =
    new BrotliDecompressor(customDictionary)
}

//noinspection ScalaFileName
class BrotliDecompressor private(customDictionary: Option[Array[Byte]]) extends Decompressor {
  override def decompress: ZPipeline[Any, Throwable, Byte, Byte] =
    viaInputStreamByte { inputStream =>
      new BrotliInputStream(
        inputStream,
        BrotliInputStream.DEFAULT_INTERNAL_BUFFER_SIZE,
        customDictionary.orNull
      )
    }
}
