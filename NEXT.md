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
  Verified on the Pixel 9 (2026-11-27) → **milestone reached: replaces Google Tasks.**
- Google Cloud setup (SPEC §6) done 2026-11-28: Tasks API enabled, consent screen In production (home page and
  privacy policy hosted at https://main659.github.io/taskmanager-site/, repo `main659/taskmanager-site`),
  Android OAuth client for `com.david.taskmanager` + debug SHA-1 `25:AE:75:…:49:40`. No client id in code.
- Slice 5 — Google sign-in + one-time import. Top-bar ⋮ → "Google Tasks" screen (`ui/google/`): connect
  (Play services `AuthorizationClient`, scope `tasks`) → choose a list → import its open tasks → connected /
  disconnect. `core/sync/Import.kt` (`planImport`, `TaskOperations.addImported`); `google/` (gateway
  interface, `HttpGoogleTasksGateway` over HttpURLConnection + org.json, `PlayServicesAuthorizer`);
  DB v2 auto-migration adds `google_task_links`; connection in `app_state`. 205 unit tests (107 core, 98 app).
Next: David to verify slice 5 on the Pixel 9 (checklist below), then Slice 6 — push available tasks to Google
(plan first).
Slice 5 device checklist: ⋮ → Google Tasks → Connect; pick the account, accept the unverified-app warning
(Advanced → Go to TaskManager) and grant access; choose a list; the result shows imported / linked counts and
the tasks appear on top of Available and on the widget; existing tasks survive the DB upgrade; leaving and
reopening the screen shows "Connected to: …"; Disconnect keeps the tasks; reconnecting links instead of
duplicating. If sign-in fails with "developer error" (code 10), the SHA-1 / package in the OAuth client don't
match this build.
Open items: none.
Backlog (after the sync slices; not scheduled):
- Polish (slice 8): tapping anywhere on the widget (outside the checkbox / "+") opens the app; drag-and-drop
  reordering in Available instead of the Move up / down menu; other UI tweaks David will list.
- Second iteration — day-locked tasks: available only on chosen weekdays (e.g. "go to the office", "call the
  dentist" Mon–Fri). Fits as a new derived lock reason in `AvailabilityEvaluator`; the midnight refresh
  already re-evaluates daily.
- Second iteration — recurring tasks (e.g. "clean my room", "meal prep"): completing one schedules the next
  occurrence. Needs a design for how it interacts with Google sync (completion pulled from Google must also
  create the next occurrence locally), so plan it after slices 5–7.
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
- Import: only open tasks; a Google task whose trimmed title matches (ignoring case) an OPEN, unlinked local
  task is linked to it instead of imported (topmost local match first); blank Google titles become "Untitled";
  subtasks are imported flat, right after their parent. Imported tasks go on top in Google's order.
- Google due dates are midnight UTC; the date part is used as is (no zone conversion).
- `google_task_links` has no FK to `tasks`: a link outlives a local delete so that push can delete the Google
  copy, and such a Google task is never re-imported. Disconnect deletes all links and the stored list.
- The connection (list id + title) is stored only when the import commits, in the same transaction; importing
  another list while connected is refused. The import runs in `appScope`.
- Migration tests build v1 from `app/schemas/.../1.json` and let Room validate the upgrade (no room-testing).
Open questions: none.
