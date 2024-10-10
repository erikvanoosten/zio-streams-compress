package zio.compress

import zio.stream._
import zio.stream.compression.CompressionLevel._
import zio.stream.compression.CompressionStrategy._
import zio.stream.compression.{CompressionLevel, CompressionStrategy}

object GzipCompressor {
  private val CompressionLevels = Seq(
    DefaultCompression, NoCompression, BestSpeed, CompressionLevel2, CompressionLevel3, CompressionLevel4,
    CompressionLevel5, CompressionLevel6, CompressionLevel7, CompressionLevel8, BestCompression
  )
  private val CompressionStrategies = Seq(DefaultStrategy, Filtered, HuffmanOnly)

  /** @param level a gzip compression level, valid values: -1 (default), 0 (fastest) to 9 (best compression) */
  def intToCompressionLevel(level: Int): Option[CompressionLevel] =
    CompressionLevels.find(_.jValue == level)

  /** @param strategy a gzip compression strategy, valid values: 0 (default), 1 (filtered) or 2 (huffman only) */
  def intToCompressionStrategy(strategy: Int): Option[CompressionStrategy] =
    CompressionStrategies.find(_.jValue == strategy)

  def make(
      deflateLevel: Option[CompressionLevel] = None,
      deflateStrategy: Option[CompressionStrategy] = None,
      bufferSize: Int = Defaults.DefaultChunkSize
  ): GzipCompressor =
    new GzipCompressor(deflateLevel, deflateStrategy, bufferSize)
}

class GzipCompressor private (
    deflateLevel: Option[CompressionLevel],
    deflateStrategy: Option[CompressionStrategy],
    bufferSize: Int
) extends Compressor {
  override def compress: ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.gzip(
      bufferSize,
      deflateLevel.getOrElse(DefaultCompression),
      deflateStrategy.getOrElse(CompressionStrategy.DefaultStrategy)
    )
}

object GzipDecompressor {
  def make(bufferSize: Int = Defaults.DefaultChunkSize): GzipDecompressor =
    new GzipDecompressor(bufferSize)
}

class GzipDecompressor private (bufferSize: Int) extends Decompressor {
  override def decompress: ZPipeline[Any, Throwable, Byte, Byte] =
    ZPipeline.gunzip(bufferSize)
}
