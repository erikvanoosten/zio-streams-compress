package zio.compress

import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE
import net.jpountz.lz4.{LZ4FrameInputStream, LZ4FrameOutputStream}
import zio.compress.JavaIoInterop.{viaInputStreamByte, viaOutputStreamByte}
import zio.stream._

sealed trait Lz4CompressorBlockSize
object Lz4CompressorBlockSize {
  case object BlockSize64KB extends Lz4CompressorBlockSize
  case object BlockSize256KB extends Lz4CompressorBlockSize
  case object BlockSize1MB extends Lz4CompressorBlockSize
  case object BlockSize4MB extends Lz4CompressorBlockSize

  def fromLz4BlockSizeIndicator(indicator: Int): Option[Lz4CompressorBlockSize] =
    indicator match {
      case 4 => Some(BlockSize64KB)
      case 5 => Some(BlockSize256KB)
      case 6 => Some(BlockSize1MB)
      case 7 => Some(BlockSize4MB)
      case _ => None
    }
}

object Lz4Compressor {
  def make(
      blockSize: Lz4CompressorBlockSize = Lz4CompressorBlockSize.BlockSize256KB
  ): Lz4Compressor = {
    val lz4BlockSize = blockSize match {
      case Lz4CompressorBlockSize.BlockSize64KB => BLOCKSIZE.SIZE_64KB
      case Lz4CompressorBlockSize.BlockSize256KB => BLOCKSIZE.SIZE_256KB
      case Lz4CompressorBlockSize.BlockSize1MB => BLOCKSIZE.SIZE_1MB
      case Lz4CompressorBlockSize.BlockSize4MB => BLOCKSIZE.SIZE_4MB
    }
    new Lz4Compressor(lz4BlockSize)
  }
}

class Lz4Compressor private (blockSize: LZ4FrameOutputStream.BLOCKSIZE) extends Compressor {
  override def compress: ZPipeline[Any, Throwable, Byte, Byte] =
    viaOutputStreamByte(new LZ4FrameOutputStream(_, blockSize))
}

object Lz4Decompressor {
  def make(): Lz4Decompressor =
    new Lz4Decompressor()
}

class Lz4Decompressor private extends Decompressor {
  override def decompress: ZPipeline[Any, Throwable, Byte, Byte] =
    viaInputStreamByte(new LZ4FrameInputStream(_))
}
