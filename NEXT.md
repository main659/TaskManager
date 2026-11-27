# Next

Done: setup; Slice 1 — pure-Kotlin `:core` module (model, AvailabilityEvaluator, DependencyGraph,
TaskOperations for complete/undo/delete/setDependencies/midnight refresh), 64 unit tests.
Next: Slice 2 — persistence (Room schema, DAOs, repository, tests). Plan it and get approval first;
Room/KSP are new dependencies, so ask before adding them. `:app` does not depend on `:core` yet.
Decisions made in slice 1 (revisit if wrong):
- List order is ascending `position`; "move to top" = position below every other task.
- Only the task an operation targets gets a new `updatedAt`; moving to the top, the delay → availableFrom
  conversion and removing a deleted dependency don't touch it.
- Any task that becomes AVAILABLE moves to the top, including on undo (DONE → OPEN). An undone task that is
  locked by its own rules keeps its position.
- `TaskOperations` returns the full new task map; the repository persists the difference.
Open questions: none.
