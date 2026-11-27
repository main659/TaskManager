# Task Manager — Spec (v1)

Working name: TaskManager. Package: `com.david.taskmanager`.

## 1. Goal

A native Android to-do app that replaces Google Tasks on my phone and adds:

- **Dependencies** — a task stays hidden until the tasks it depends on are done.
- **Date gating** — a task stays hidden until a given date.
- **Delays** — a task becomes available N days after a dependency is completed.

The default view only shows what I can act on now. Available tasks are mirrored to Google Tasks so they still appear in Google Calendar.

Single user (me), single device (Google Pixel 9). Planning happens on the phone only.

## 2. Stack

- Kotlin, Jetpack Compose, Material 3 (light + dark, dynamic color)
- Room (local database — the source of truth)
- Glance (home-screen widget)
- WorkManager (background sync, midnight refresh)
- Google Tasks API (REST) with Google sign-in / authorization
- minSdk 26, targetSdk = latest stable
- Tests: JUnit for `core`; Robolectric or instrumented tests for Room; fakes for Google

## 3. Domain model

### Task
| Field | Type | Notes |
|---|---|---|
| id | UUID | local id |
| title | String | required, non-blank |
| notes | String? | |
| status | OPEN / DONE | no CANCELLED status: cancelling a task means deleting it |
| dueDate | LocalDate? | date only (Google Tasks due dates are date-only) |
| availableFrom | LocalDate? | hidden before this date |
| dependencies | List<Dependency> | |
| position | Long | manual ordering |
| createdAt | Instant | |
| updatedAt | Instant | used for sync conflict resolution |
| completedAt | Instant? | set when status → DONE, cleared on undo |

### Dependency
| Field | Type | Notes |
|---|---|---|
| taskId | UUID | the task this one waits on |
| delayDays | Int ≥ 0 | default 0 |

### Derived state (never stored)
- **AVAILABLE** — status OPEN, and `availableFrom` is null or ≤ today, and every dependency is satisfied.
- **LOCKED** — status OPEN but not available. Must expose the reason(s): "Waiting on: <title>", "Available from 3 Dec", "Available 2 days after <title>" (with the resulting date).
- **DONE** — from status.

A dependency is **satisfied** when the dependency task is DONE and `date(completedAt, local tz) + delayDays ≤ today`.

### Rules
- "Today" = current date in the device's local time zone, via an injected `Clock`.
- A task cannot depend on itself.
- Adding a dependency that would create a cycle is rejected with a clear error.
- Undoing a completion (DONE → OPEN) re-locks dependents that are still OPEN.
- Delete is the only way to cancel a task, and it is a hard delete. Deleting a task removes it from other tasks' dependency lists, so dependents may unlock (ask for confirmation if it has dependents).
- Exception — deleting a DONE task must not change its dependents: for each dependent still inside that dependency's delay window, set the dependent's `availableFrom` to `max(existing availableFrom, date(completedAt, local tz) + delayDays)` before removing the dependency. Applies to local deletes and deletes pulled from Google alike. (Deleting an OPEN task frees its dependents as above.)
- When a task becomes available, it moves to the top of the Available list (its `position` is set above all others). This happens in the same write that unlocks it (e.g. completing its last dependency, or undoing the task's own completion) or, for date-based unlocks, during the midnight refresh. No extra field or "newly unlocked" marker is stored.
- Availability must be re-evaluated at local midnight so date-gated tasks appear without opening the app.

## 4. UI

1. **Available** (default screen) — list of available tasks; checkbox completes (with Undo snackbar); quick-add field; manual reorder.
2. **Locked** — list of locked tasks with their lock reason and, where computable, the date they unlock.
3. **Done** — collapsed section or separate screen; can un-complete.
4. **Task detail / edit** — title, notes, due date, available-from date, dependencies (pick tasks, set delay days per dependency), delete.

Look and feel: close to Google Tasks — Material 3, minimal, fast. Polish (animations, drag-and-drop) is milestone 8, not before.

## 5. Widget (Glance)

- Shows available tasks (title + due date if set), in the same order as the app.
- Checkbox completes the task directly from the widget.
- Tapping a title opens that task in the app; a "+" opens quick add.
- Refreshes on every data change and at local midnight.

## 6. Google integration

### Setup (manual, done by me)
1. Create a Google Cloud project; enable the Google Tasks API.
2. OAuth consent screen: set to **In production** (Testing mode makes access expire about weekly). Unverified-app warning is acceptable.
3. Create an **Android** OAuth client with the package name and the SHA-1 of the debug signing key (`./gradlew signingReport`). Add the release key's SHA-1 later.

### Behaviour
- I sign in once and pick one target Google Tasks list.
- **Local is the source of truth for structure.** Dependencies, availability dates and delays are never written to Google.
- **Push (app → Google):**
  - Task becomes AVAILABLE → create it in the target list (title, notes, due date).
  - Title / notes / due date edited locally → update in Google.
  - Completed locally → mark completed in Google.
  - Task becomes LOCKED again (undo, new dependency added) or is deleted locally → delete from Google.
- **Pull (Google → app):** on app open and every 15 minutes (WorkManager minimum):
  - Fetch changes since last sync (`updatedMin`).
  - Completed in Google → complete locally (which may unlock dependents → pushed).
  - Title / notes / due edited in Google → update locally; conflicts resolved by last write wins on `updated` timestamp.
  - Deleted in Google → delete locally, with the same effect as a local delete (removed from dependents' dependency lists; no confirmation possible).
  - Task created directly in Google (no mapping) → import as a new local OPEN task with no rules.
- A mapping table links local id ↔ Google task id. **Every sync operation must be idempotent** — running sync twice must never create duplicates.
- Local actions apply instantly; outgoing changes go through a persisted queue retried with backoff when offline.
- **Duplicate prevention for creates** (a lost `tasks.insert` response must not produce a second Google task):
  1. Before sending a create, the queue entry is persisted as *in flight* with the attempt time; on success the Google id is stored in the mapping and the entry is cleared.
  2. Before retrying an in-flight create, list the target list's tasks updated since shortly before the attempt; if an unmapped task with the same title, notes and due date exists, map it instead of inserting again.
  3. During pull, an unmapped Google task that matches (title, notes, due) a local task with an in-flight or recent create is mapped to it instead of being imported as a new task.
  - Later, not v1: a cleanup pass deleting extra Google tasks mapped to the same local task; a hidden local-id marker in notes only if matching proves insufficient.
- **One-time import:** on first sign-in, import all open tasks from the chosen list.

### Known API limits (verify against the Google Tasks API docs before relying on them)
- Due dates are date-only.
- Recurrence and starred status are, as far as I know, not exposed by the API — out of scope.

## 7. Architecture

- **`core` module — pure Kotlin, no Android dependencies.**
  - Domain model
  - `AvailabilityEvaluator` (state + lock reasons + unlock date)
  - `DependencyGraph` (cycle detection, dependents lookup)
  - Sync decision logic as pure functions: given local state + remote changes → list of actions
- **`app` module**
  - Room entities, DAOs, migrations; repositories
  - Compose UI with ViewModels exposing `StateFlow`
  - Glance widget
  - WorkManager workers (sync, midnight refresh)
  - `GoogleTasksGateway` interface + real implementation + `FakeGoogleTasksGateway` for tests
- Time is always read through an injected `Clock` — never `LocalDate.now()` directly.

## 8. Build order (each slice: builds, tests pass, committed)

1. **Core logic** — model, availability, delays, lock reasons, cycle detection. Exhaustive unit tests (edge cases: delay crossing midnight, undo, deleted OPEN dependency, deleted DONE dependency inside its delay window, multiple dependencies, time zone).
2. **Persistence** — Room schema, DAOs, repository, tests.
3. **UI** — Available, Locked, Done, Task detail; complete/undo.
4. **Widget** — available list, complete from widget, midnight refresh.
   → **Milestone: I use this instead of Google Tasks on my phone from this day on.**
5. **Google sign-in + one-time import.**
6. **Push** available tasks to Google Tasks (they appear in Calendar).
7. **Pull** completions/edits/deletes from Google; sync queue; offline handling.
8. **Polish** — drag-and-drop reorder, animations, empty states, release build.

## 9. Out of scope for v1

Recurring tasks, subtasks, multiple lists, reminders/notifications, sharing, tablet layouts, web or desktop versions, backups beyond Google sync.

## 10. Decisions (formerly open questions)

1. Cancelling = deleting. There is no CANCELLED status; a delete (local or in Google) is a hard delete that removes the task from dependents' dependency lists. Deleting a DONE task converts any pending delay on its dependents into an `availableFrom` date, so their unlock date does not change (§3 Rules).
2. A task can have both `availableFrom` and dependencies; all conditions must hold.
3. An unlocked task moves to the top of the Available list (see §3 Rules). No "newly unlocked" highlight or timestamp.
4. Tasks that can never unlock cannot exist: deleting a task removes it as a dependency.
5. Reorder before milestone 8 is a "Move up / Move down" menu; drag-and-drop comes in milestone 8.
6. Create duplicates are prevented with steps 1–3 in §6; cleanup pass and notes marker are deferred.

## 11. Working agreements for the coding agent

- Plan before coding each slice; wait for approval of the plan.
- Write `core` tests first.
- Don't add dependencies without asking.
- Keep changes scoped to the current slice; no drive-by refactors.
- Run `./gradlew test` (and `./gradlew assembleDebug`) before declaring a slice done.
- One commit per working slice, descriptive message.
