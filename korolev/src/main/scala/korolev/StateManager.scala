package korolev

import java.util.concurrent.ConcurrentLinkedQueue

import scala.language.higherKinds
import scala.util.{Failure, Success}

abstract class StateManager[F[+_]: Async, State] extends (StateManager.Transition[State] => F[Unit]) {
  def state: State
  def subscribe[U](f: State => U): StateManager.Unsubscribe
  def onDestroy[U](f: () => U): StateManager.Unsubscribe
  def destroy(): Unit
  def apply(transition: StateManager.Transition[State]): F[Unit]
  def update(state: State): F[Unit]
}

object StateManager {

  type Unsubscribe = () => Unit
  type Transition[State] = PartialFunction[State, State]

  def apply[F[+_]: Async, S](initialState: S): StateManager[F, S] = {
    new StateManager[F, S] {

      val queue = new ConcurrentLinkedQueue[(Async.Promise[F, Unit], Transition[S])]

      @volatile var currentState = initialState
      @volatile var subscribers = List.empty[S => _]
      @volatile var onDestroyListeners = List.empty[() => _]
      @volatile var inProgress = false

      def update(state: S): F[Unit] = apply { case _ => state }

      def apply(transition: Transition[S]): F[Unit] = {
        def executeNext(): Unit = {
          val (promise, transition) = queue.poll()
          try {
            transition.lift(currentState) match {
              case Some(newState) =>
                currentState = newState
                subscribers.foreach(f => f(newState))
                promise.complete(Success(()))
              case None =>
                promise.complete(Failure(new Exception("Transition doesn't fit this state")))
            }
          } catch {
            case e: Throwable =>
              promise.complete(Failure(e))
          } finally {
            if (queue.isEmpty) inProgress = false
            else executeNext()
          }
        }
        val promise = Async[F].promise[Unit]
        queue.add(promise -> transition)
        if (!inProgress) {
          inProgress = true
          executeNext()
        }
        promise.future
      }

      def destroy(): Unit = {
        for (listener <- onDestroyListeners)
          listener()
      }

      def state: S = currentState

      def onDestroy[U](f: () => U): Unsubscribe = {
        onDestroyListeners = f :: onDestroyListeners
        () => onDestroyListeners = onDestroyListeners.filter(_ != f)
      }

      def subscribe[U](f: S => U): StateManager.Unsubscribe = {
        subscribers = f :: subscribers
        () => subscribers = subscribers.filter(_ != f)
      }
    }
  }
}
