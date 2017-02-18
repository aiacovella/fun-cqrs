package io.funcqrs.model

import java.time.OffsetDateTime
import java.util.UUID

import io.funcqrs._
import io.funcqrs.behavior.{ Types, _ }

sealed trait TimeTracker {

  def id: TrackerId
  def previousTasks: List[Task]

  def isBusy =
    this match {
      case _: BusyTracker => true
      case _              => false
    }

  def isIdle = !isBusy

}

case class BusyTracker(id: TrackerId, currentTask: Task, previousTasks: List[Task] = List()) extends TimeTracker {

  def stop(end: OffsetDateTime): TimeTracker = {
    IdleTracker(id, previousTasks :+ currentTask)
  }

  def actionsWhenBusy =
    TimeTracker.actions
      .commandHandler {
        OneEvent {
          case StopTracking => TimerStopped(OffsetDateTime.now(), EventId())
        }
      }
      .commandHandler {
        ManyEvents {
          case cmd: ReplaceTask =>
            val now = OffsetDateTime.now()
            List(
              TimerStopped(now, EventId()),
              // the event handler for TimerStarter is defined
              // on the behavior case branch for idling TimeTracker
              TimerStarted(cmd.title, now, EventId())
            )
        }
      }
      .eventHandler {
        case TimerStopped(end, _) => stop(end)
      }
}

case class IdleTracker(id: TrackerId, previousTasks: List[Task] = List()) extends TimeTracker {

  def start(title: String, start: OffsetDateTime): TimeTracker = {
    val task = Task(title, start)
    BusyTracker(id, task, previousTasks)
  }

  def actionsWhenIdle =
    TimeTracker.actions
      .commandHandler {
        OneEvent {
          case StartTracking(title) => TimerStarted(title, OffsetDateTime.now(), EventId())
        }
      }
      .commandHandler {
        OneEvent {
          case ReplaceTask(title) => TimerStarted(title, OffsetDateTime.now(), EventId())
        }
      }
      .eventHandler {
        case TimerStarted(title, startDate, _) => start(title, startDate)
      }

}

case class Task(title: String, started: OffsetDateTime, stopped: Option[OffsetDateTime] = None) {
  def isRunning = stopped.isEmpty
}

object TimeTracker extends Types[TimeTracker] {

  type Id      = TrackerId
  type Command = TrackerCommand
  type Event   = TrackerEvent

  def constructionHandlers(id: TrackerId) =
    actions
      .commandHandler {
        OneEvent { case CreateTracker => TimerCreated(EventId()) }
      }
      .commandHandler {
        ManyEvents {
          case CreateAndStartTracking(taskTitle) =>
            List(
              TimerCreated(EventId()),
              // the event handler for TimerStarter depends on a created Tracker
              // and therefore can't be defined in this 'Actions'
              // behavior is available in next transition
              TimerStarted(taskTitle, OffsetDateTime.now(), EventId())
            )
        }
      }
      .eventHandler {
        case TimerCreated(_) => IdleTracker(id)
      }

  def behavior(id: TrackerId) =
    Behavior
      .construct(constructionHandlers(id))
      .andThen {
        case tracker: IdleTracker => tracker.actionsWhenIdle
        case tracker: BusyTracker => tracker.actionsWhenBusy
      }

}

case class TrackerId(value: String) extends AggregateId

object TrackerId {
  def generate: TrackerId = TrackerId(UUID.randomUUID().toString)
}

sealed trait TrackerCommand
case object CreateTracker extends TrackerCommand
final case class CreateAndStartTracking(taskTitle: String) extends TrackerCommand
final case class StartTracking(title: String) extends TrackerCommand
final case class ReplaceTask(title: String) extends TrackerCommand
case object StopTracking extends TrackerCommand

sealed trait TrackerEvent
final case class TimerCreated(id: EventId) extends TrackerEvent
final case class TimerStarted(title: String, start: OffsetDateTime, id: EventId) extends TrackerEvent
final case class TimerStopped(end: OffsetDateTime, id: EventId) extends TrackerEvent
