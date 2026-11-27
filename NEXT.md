# Next

Done:
- Slice 1 — pure-Kotlin `:core` module (model, AvailabilityEvaluator, DependencyGraph, TaskOperations).
- Slice 2 — persistence: Room DB v1 (`tasks`, `task_dependencies`, `app_state`; schema exported to
  `app/schemas/`), `TaskDao`, `TaskRepository(db, clock)`; `TaskOperations.add` / `updateDetails` in core.
  98 unit tests (76 core, 22 app via Robolectric).
Next: Slice 3 — UI (Available, Locked, Done, Task detail; complete/undo; Move up / Move down menu).
Plan it and get approval first. Likely needs new dependencies (lifecycle-viewmodel-compose, navigation-compose):
ask before adding. Also needs: an app-level container (manual DI) that builds `TaskDatabase` + `TaskRepository`
with `Clock.systemDefaultZone()`, a reorder operation in core (tests first), and calling
`refreshForToday()` on app start until the slice 4 midnight worker exists.
Decisions so far (revisit if wrong):
- List order is ascending `position`; "move to top" = position below every other task. New tasks go on top.
- Only the task an operation targets gets a new `updatedAt`; moving to the top, the delay → availableFrom
  conversion and removing a deleted dependency don't touch it.
- Any task that becomes AVAILABLE moves to the top, including on undo (DONE → OPEN). An undone task that is
  locked by its own rules keeps its position.
- Each repository write = one transaction: load all tasks, apply one `TaskOperations` call, save the diff.
  A thrown error (e.g. cycle) rolls the write back.
- Dates are stored as epoch days, instants as epoch millis (sub-millisecond precision is dropped).
- `refreshForToday()`: first run only records the date; if the stored date is later than today, nothing moves.
Open questions: none.
