# Next

Done:
- Slice 1 — pure-Kotlin `:core` module (model, AvailabilityEvaluator, DependencyGraph, TaskOperations).
- Slice 2 — persistence: Room DB v1 (`tasks`, `task_dependencies`, `app_state`; schema in `app/schemas/`),
  `TaskDao`, `TaskRepository(db, clock)`.
- Slice 3 — UI: Available / Locked / Done tabs, quick-add sheet, complete + Undo snackbar, Move up / down menu,
  task detail (title, notes, dates, dependencies with delays, delete with dependents warning). Navigation 3
  with per-screen ViewModels; manual DI in `AppContainer` (`TaskManagerApplication`); `SystemZoneClock`;
  refresh on app start and at each local midnight while visible. Verified on the Pixel 9 (2026-11-27).
- Espresso upgraded to 3.7.0; Compose UI tests no longer pinned to Robolectric SDK 34.
- Slice 4 — Glance widget (`widget/TaskWidget.kt`: Available list in app order, checkbox completes via
  `CompleteTaskAction`, title opens the task, "+" opens quick add, header opens the app) and the midnight
  refresh (`refresh/MidnightRefresh.kt`: self-rescheduling `MidnightRefreshWorker`, `MidnightScheduler`,
  `TimeChangeReceiver`). Launch intents in `launch/LaunchIntents.kt`. 165 unit tests (94 core, 71 app).
Next: David to verify slice 4 on the Pixel 9 (checklist below) → **milestone: replaces Google Tasks**.
Then Slice 5 — Google sign-in + one-time import (plan first; needs Google auth / Tasks API dependencies and
the manual Cloud setup from SPEC §6).
Slice 4 device checklist: add the widget (long-press home screen → Widgets → TaskManager); check a task off
from it; tap a title (opens its detail, back → Available); tap "+" (quick-add sheet open); edit in the app and
see the widget update; a task with "available from" tomorrow appears on the widget after midnight without
opening the app (may lag by minutes in Doze).
Open items: none.
Decisions so far (revisit if wrong):
- List order is ascending `position`; "move to top" = position below every other task. New tasks go on top.
- Only the task an operation targets gets a new `updatedAt`; moving to the top / reordering, the delay →
  availableFrom conversion and removing a deleted dependency don't touch it.
- Any task that becomes AVAILABLE moves to the top, including on undo (DONE → OPEN). An undone task that is
  locked by its own rules keeps its position.
- Each repository write = one transaction: load all tasks, apply one `TaskOperations` call, save the diff.
  Edits that touch some fields (title/notes, one date, dependencies) read the rest inside the transaction.
- Writes run in `AppContainer.appScope`, not `viewModelScope`, so leaving a screen never cancels a save.
- Detail screen: dates and dependencies save immediately; title/notes save 500 ms after typing stops and when
  the screen closes; a blank title is never saved. Delay per dependency is capped at 365 days.
- Locked list: soonest unlock date first, then tasks waiting on an open task; Done: most recent first.
- Dates are stored as epoch days, instants as epoch millis (sub-millisecond precision is dropped).
- `refreshForToday()`: first run only records the date; if the stored date is later than today, nothing moves.
- Widget updates go through `TaskRepository(onChange = …)`, called only after a write commits a change (and when
  the refresh moves to a new date); no background Flow collector. A widget-update failure never fails a write.
- Widget/launcher intents use NEW_TASK | CLEAR_TASK, so MainActivity builds its back stack from the intent
  (`LaunchIntents.stateFrom`) and never handles `onNewIntent`. Per-task intents carry the id in the data URI.
- Midnight chain: one unique job `midnight-refresh` at next local midnight + 1 min. App start = KEEP,
  the worker queues its successor with APPEND_OR_REPLACE only on success (failures retry), time / time-zone
  change = REPLACE with an immediate run. WorkManager timing is inexact under Doze (no exact-alarm permission).
- Glance LazyColumn item ids must be ≥ -2^62: `widgetItemId` folds the UUID into [0, 2^62).
- No Undo on the widget (widgets can't show snackbars); un-complete from the app's Done tab.
Open questions: none.
