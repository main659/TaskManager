# Next

Done: setup; Slice 1 — pure-Kotlin `:core` module (model, AvailabilityEvaluator, DependencyGraph,
TaskOperations for complete/undo/delete/setDependencies/midnight refresh), 63 unit tests.
Next: Slice 2 — persistence (Room schema, DAOs, repository, tests). Plan it and get approval first;
Room/KSP are new dependencies, so ask before adding them. `:app` does not depend on `:core` yet.
Decisions made in slice 1 (revisit if wrong):
- List order is ascending `position`; "move to top" = position below every other task.
- Only the task an operation targets gets a new `updatedAt`; moving to the top, the delay → availableFrom
  conversion and removing a deleted dependency don't touch it.
- Undo (DONE → OPEN) keeps the task's old position; only LOCKED → AVAILABLE moves a task to the top.
- `TaskOperations` returns the full new task map; the repository persists the difference.
Open questions: none.
