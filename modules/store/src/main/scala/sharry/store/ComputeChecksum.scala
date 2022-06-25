package sharry.store

import binny._
import binny.util.Stopwatch
import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.{Pipe, Stream}
import sharry.common.{ByteSize, Ident, Timestamp}
import sharry.store.records.RFileMeta

trait ComputeChecksum[F[_]] {

  def submit(id: BinaryId, hint: Hint): F[Unit]

  def computeSync(id: BinaryId, hint: Hint, attr: AttributeNameSet): F[RFileMeta]

  def consumeAll(attr: AttributeNameSet): Stream[F, RFileMeta]
}
object ComputeChecksum {
  def apply[F[_]: Async](
      store: BinaryStore[F],
      config: ComputeChecksumConfig
  ): F[ComputeChecksum[F]] =
    for {
      queue <- Queue.bounded[F, Entry](config.capacity)
    } yield new ComputeChecksum[F] {
      private[this] val logger = sharry.logging.getLogger[F]

      def submit(id: BinaryId, hint: Hint): F[Unit] =
        queue.offer(Entry(id, hint))

      def computeSync(id: BinaryId, hint: Hint, select: AttributeNameSet): F[RFileMeta] =
        for {
          _ <- logger.debug(s"Compute $select for binary ${id.id}")
          w <- Stopwatch.start
          attr <- store
            .computeAttr(id, hint)
            .run(select)
            .getOrElse(BinaryAttributes.empty)
          _ <- Stopwatch.show(w)(time =>
            logger.debug(s"Computing $select for ${id.id} took $time")
          )
          now <- Timestamp.current[F]
          fm = RFileMeta(
            Ident.unsafe(id.id),
            now,
            attr.contentType.contentType,
            ByteSize(attr.length),
            attr.sha256
          )
        } yield fm

      def consumeAll(attr: AttributeNameSet): Stream[F, RFileMeta] =
        logger.stream
          .info(
            s"Starting computing checksum of submitted binaries: $config"
          )
          .drain ++
          Stream.repeatEval(queue.take).through(computePipe(attr))

      private def computePipe(
          attr: AttributeNameSet
      ): Pipe[F, Entry, RFileMeta] = in =>
        if (config.parallel > 1)
          in.parEvalMap(config.parallel)(e => computeSync(e.id, e.hint, attr))
        else in.evalMap(e => computeSync(e.id, e.hint, attr))
    }

  private case class Entry(id: BinaryId, hint: Hint)
}
