# Next

Done:
- Slice 1 — pure-Kotlin `:core` module (model, AvailabilityEvaluator, DependencyGraph, TaskOperations).
- Slice 2 — persistence: Room DB v1 (`tasks`, `task_dependencies`, `app_state`; schema in `app/schemas/`),
  `TaskDao`, `TaskRepository(db, clock)`.
- Slice 3 — UI: Available / Locked / Done tabs, quick-add sheet, complete + Undo snackbar, Move up / down menu,
  task detail (title, notes, dates, dependencies with delays, delete with dependents warning). Navigation 3
  with per-screen ViewModels; manual DI in `AppContainer` (`TaskManagerApplication`); `SystemZoneClock`;
  refresh on app start and at each local midnight while visible. 145 unit tests (94 core, 51 app).
Next: Slice 4 — Glance widget (available list, complete from widget, "+" opens quick add, tap opens the task)
and the midnight refresh worker (WorkManager). Plan it and get approval first; Glance and WorkManager are new
dependencies. Widget deep links = set the Navigation 3 back stack (`Screen.Detail`) from the intent.
Slice 3 verified on the Pixel 9 by David (2026-11-27): works well.
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
Open questions: none.
