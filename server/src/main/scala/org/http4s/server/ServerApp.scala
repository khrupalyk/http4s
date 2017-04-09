package org.http4s
package server

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import scala.annotation.tailrec
import scalaz.concurrent.Task

/**
 * Starts a server and gracefully terminates at shutdown.  The server
 * is terminated and the shutdown task is run either by a JVM shutdown
 * hook or an invocation of `requestShutdown()`.
 *
 * If the server fails to start, the `shutdown` task is not invoked.
 * More robust resource management is possible through `ProcessApp` or
 * `StreamApp`, which are introduced in http4s-0.16 and http4s-0.17,
 * respectively.
 */
trait ServerApp {
  private[this] val logger = org.log4s.getLogger

  /** Return a server to run */
  def server(args: List[String]): Task[Server]

  /** Return a task to shutdown the application.
   *
   *  This task is run as a JVM shutdown hook, or when
   *  [[org.http4s.server.ServerApp.requestShutdown]] is explicitly called.
   *
   *  The default implementation shuts down the server, and waits for
   *  it to finish.  Other resources may shutdown by flatMapping this
   *  task.
   */
  def shutdown(server: Server): Task[Unit] =
    server.shutdown

  private sealed trait LifeCycle
  private case object Init extends LifeCycle
  private case object Started extends LifeCycle
  private case object Stopping extends LifeCycle
  private case object Stopped extends LifeCycle

  /** The current state of the server. */
  private val state =
    new AtomicReference[LifeCycle](Init)

  @tailrec
  private def doShutdown(s: Server): Unit =
    state.get match {
      case _ if state.compareAndSet(Started, Stopping) =>
        logger.info(s"Shutting down server on ${s.address}")
        try shutdown(s).run
        finally state.set(Stopped)
        logger.info(s"Stopped server on ${s.address}")
      case Stopping | Stopped =>
        logger.debug(s"Ignoring duplicate shutdown request for ${s.address}")
      case state =>
        logger.warn(s"Tried to shutdown server $s, but was in state $state.  Trying again in 1 second")
        Thread.sleep(1000)
        doShutdown(s)
    }

  private[this] val latch =
    new CountDownLatch(1)

  /** Explicitly request a graceful shutdown of the service.
   *
   *  There is no operational standard for this, but some common
   *  implementations include:
   *  - an admin port receiving a connection
   *  - a JMX command
   *  - monitoring a file
   *  - console input in an interactive session
   */
  def requestShutdown(): Unit = {
    logger.info("Received shutdown request")
    latch.countDown()
  }

  private def run(args: List[String]): Unit = {
    val s = server(args).map { s =>
      sys.addShutdownHook {
        doShutdown(s)
      }
      s
    }.attempt.run.fold(
      e => logger.error(e)("Fatal error running server"),
      s => {
        state.set(Started)
        logger.info(s"Started server on ${s.address}")
        latch.await()
        doShutdown(s)
      }
    )
  }

  final def main(args: Array[String]): Unit =
    run(args.toList)
}

