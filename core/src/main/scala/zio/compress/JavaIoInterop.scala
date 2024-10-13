package zio.compress

import zio._
import zio.stream._

import java.io.{BufferedOutputStream, InputStream, OutputStream}

private[compress] object JavaIoInterop {

  /** Makes a pipeline that makes the incoming ZStream available for reading via an InputStream. This InputStream can
    * then be wrapped by `makeInputStream`. All bytes read from the wrapped InputStream are put in the outgoing ZStream.
    *
    * @param makeInputStream
    *   Create the wrapped InputStream.
    * @param queueSize
    *   Chunks of the incoming ZStream go through an internal queue. This parameter determines the size of that queue.
    *   Defaults to 2.
    * @return
    *   The created pipeline.
    */
  def viaInputStreamByte(
      makeInputStream: InputStream => InputStream,
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  ): ZPipeline[Any, Throwable, Byte, Byte] =
    viaInputStream[Byte](queueSize) { inputStream =>
      ZIO.attemptBlocking(ZStream.fromInputStream(makeInputStream(inputStream)))
    }

  /** Makes a pipeline that makes the incoming ZStream available for reading via an InputStream. This is then used by
    * function `streamReader` to produce the outgoing ZStream.
    *
    * @param queueSize
    *   Chunks of the incoming ZStream go through an internal queue. This parameter determines the size of that queue.
    *   Defaults to 2.
    * @param streamReader
    *   A ZIO that reads from the given incoming InputStream to produce the outgoing ZStream. The outgoing ZStream must
    *   end when the InputStream closes.
    * @return
    *   The created pipeline.
    */
  def viaInputStream[Out](
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  )(
      streamReader: InputStream => ZIO[Scope, Throwable, ZStream[Any, Throwable, Out]]
  ): ZPipeline[Any, Throwable, Byte, Out] =
    ZPipeline.fromFunction[Any, Throwable, Byte, Out] { stream =>
      ZStream.unwrapScoped {
        for {
          queue <- ZIO.acquireRelease(Queue.bounded[Take[Throwable, Byte]](queueSize))(_.shutdown)
          _ <- stream.chunks
            .map(Take.chunk)
            .run(ZSink.fromQueue(queue))
            .onDoneCause(
              cause => queue.offer(Take.failCause(cause)),
              _ => queue.offer(Take.end)
            )
            .forkScoped
          queueInputStream <- ZStream.fromQueue(queue).flattenTake.toInputStream
          result <- streamReader(queueInputStream)
        } yield result
      }
    }

  /** Makes a pipeline that captures the output of an OutputStream (created by `makeOutputStream`) and makes it
    * available as the outgoing ZStream. The incoming ZStream of bytes is written to the OutputStream.
    *
    * @see
    *   [[viaOutputStream]]
    */
  def viaOutputStreamByte(
      makeOutputStream: OutputStream => OutputStream,
      chunkSize: Int = Defaults.DefaultChunkSize,
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  ): ZPipeline[Any, Throwable, Byte, Byte] =
    viaOutputStream[Byte, OutputStream](makeOutputStream, chunkSize, queueSize) { case (stream, outputStream) =>
      stream.runForeachChunk(chunk => ZIO.attemptBlocking(outputStream.write(chunk.toArray)))
    }

  /** Makes a pipeline that captures the output of an OutputStream of type `OS` (created by `makeOutputStream`) and
    * makes it available as the outgoing ZStream. The input ZStream (with items of type `In`) is written to the
    * OutputStream by `streamWriter`.
    *
    * Many of these OutputStreams immediately start writing to their underlying outputStream from their constructor.
    * (Usually just a few bytes, for example a magic header indicating file type.) Since the internal queue is bounded
    * (see `queueSize`), and the queue reader starts last, the output stream is buffered. The buffer needs to be large
    * enough to fit all the initially written data. Each time the buffer fills up, the pipeline produces a chunk in the
    * outgoing ZStream. Therefore, the buffer size is set with the `chunkSize` parameter. With the default settings we
    * get large buffers and a small queue.
    *
    * @param makeOutputStream
    *   Create the wrapped OutputStream.
    * @param chunkSize
    *   The internal buffer size, also the chunk size of the outgoing ZStream, defaults to 64KiB.
    * @param queueSize
    *   The internal queue size, defaults to 2.
    * @param streamWriter
    *   Function that writes items from the given incoming ZStream to the given OutputStream. The OutputStream should
    *   _not_ be closed when the stream ends.
    * @tparam In
    *   Type of incoming items.
    * @tparam OS
    *   Type of the OutputStream.
    * @return
    *   The created pipeline.
    */
  def viaOutputStream[In, OS <: OutputStream](
      makeOutputStream: OutputStream => OS,
      chunkSize: Int = Defaults.DefaultChunkSize,
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  )(
      streamWriter: (ZStream[Any, Throwable, In], OS) => ZIO[Any, Throwable, Unit]
  ): ZPipeline[Any, Throwable, In, Byte] =
    ZPipeline.fromFunction[Any, Throwable, In, Byte] { stream =>
      ZStream.unwrapScoped {
        for {
          runtime <- ZIO.runtime[Any]
          queue <- ZIO.acquireRelease(Queue.bounded[Take[Throwable, Byte]](queueSize))(_.shutdown)
          // Note that we need to close the result from `makeOutputStream`. Therefore, `makeOutputStream` can not be
          // executed as a part of `streamWriter`.
          _ <- ZIO
            .attemptBlocking {
              val queueOutputStream = new BufferedOutputStream(new QueueOutputStream(runtime, queue), chunkSize)
              makeOutputStream(queueOutputStream)
            }
            .flatMap { outputStream =>
              streamWriter(stream, outputStream)
                .onDoneCause(
                  cause => queue.offer(Take.failCause(cause)),
                  _ => ZIO.attemptBlocking(outputStream.close()).orDie
                )
            }
            .forkScoped
        } yield ZStream.fromQueue(queue).flattenTake
      }
    }
}

private[compress] class QueueOutputStream[E](runtime: Runtime[Any], queue: Queue[Take[E, Byte]]) extends OutputStream {
  override def write(b: Int): Unit =
    offer(Take.single(b.toByte))

  override def write(b: Array[Byte]): Unit =
    offer(Take.chunk(Chunk.fromArray(java.util.Arrays.copyOf(b, b.length))))

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    offer(Take.chunk(Chunk.fromArray(java.util.Arrays.copyOfRange(b, off, off + len))))

  override def close(): Unit =
    offer(Take.end)

  private def offer(take: Take[Nothing, Byte]): Unit =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run {
        queue.offer(take)
      }
    }
}
