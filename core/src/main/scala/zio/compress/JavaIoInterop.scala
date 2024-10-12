package zio.compress

import zio._
import zio.stream._

import java.io.{BufferedOutputStream, InputStream, OutputStream}

private[compress] object JavaIoInterop {

  def viaInputStreamByte(
      makeInputStream: InputStream => InputStream,
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  ): ZPipeline[Any, Throwable, Byte, Byte] =
    viaInputStream[Byte](queueSize) { inputStream =>
      ZIO.attemptBlocking(ZStream.fromInputStream(makeInputStream(inputStream)))
    }

  def viaInputStream[Out](
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  )(
      streamReader: InputStream => ZIO[Scope, Throwable, ZStream[Any, Throwable, Out]]
  ): ZPipeline[Any, Throwable, Byte, Out] =
    ZPipeline.fromFunction[Any, Throwable, Byte, Out] { stream =>
      ZStream.unwrapScoped {
        for {
          queue <- ZIO.acquireRelease(Queue.bounded[Take[Nothing, Byte]](queueSize))(_.shutdown)
          streamEnd <- Promise.make[Throwable, Unit]
          _ <- stream.chunks
            .map(Take.chunk)
            .run(ZSink.fromQueue(queue))
            .zipLeft(queue.offer(Take.end))
            .intoPromise(streamEnd)
            .forkScoped
          queueInputStream <- ZStream.fromQueue(queue).flattenTake.toInputStream
          result <- streamReader(queueInputStream)
          _ <- ZIO.addFinalizer(streamEnd.await.orDie)
        } yield result.interruptWhen(streamEnd)
      }
    }

  def viaOutputStreamByte(
      makeOutputStream: OutputStream => OutputStream,
      chunkSize: Int = Defaults.DefaultChunkSize,
      queueSize: Int = Defaults.DefaultChunkedQueueSize
  ): ZPipeline[Any, Throwable, Byte, Byte] =
    viaOutputStream[Byte, OutputStream](makeOutputStream, chunkSize, queueSize) { case (stream, outputStream) =>
      stream.runForeachChunk(chunk => ZIO.attemptBlocking(outputStream.write(chunk.toArray)))
    }

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
          queue <- ZIO.acquireRelease(Queue.bounded[Take[Nothing, Byte]](queueSize))(_.shutdown)
          outputStream <- {
            val queueOutputStream = new BufferedOutputStream(new QueueOutputStream(runtime, queue), chunkSize)
            ZIO.attemptBlocking(makeOutputStream(queueOutputStream))
          }
          streamEnd <- Promise.make[Throwable, Unit]
          _ <- streamWriter(stream, outputStream)
            .intoPromise(streamEnd)
            .ensuring(ZIO.attemptBlocking(outputStream.close()).orDie)
            .forkScoped
          _ <- ZIO.addFinalizer(streamEnd.await.orDie)
        } yield ZStream.fromQueue(queue).flattenTake.interruptWhen(streamEnd)
      }
    }
}

private[compress] class QueueOutputStream(runtime: Runtime[Any], queue: Queue[Take[Nothing, Byte]])
    extends OutputStream {
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
