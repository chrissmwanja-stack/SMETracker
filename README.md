# SME Tracker

Android app for small business inventory, sales, debt, and expense tracking, built for the Ugandan SME market. Kotlin + Jetpack Compose, offline-first with Room, synced to Firestore.

**New to this repo?** A business has two kinds of members: an **Owner** (sees all financials, approves expenses, reconciles cost/profit data) and a **Worker** (records day-to-day sales/inventory/expenses but can't see cost or profit — that's added later by an Owner). Most role-related code you'll run into is enforcing that split. Good starting points for reading the code: `SMEViewModel.kt` (what the UI can do), `SyncEngine.kt` (how local and remote data reconcile), and `firestore.rules` in the Firebase console (the actual access control — the app's role checks exist to avoid triggering writes the rules would reject anyway).

## Stack

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **Local storage:** Room (offline cache, source of truth for the UI)
- **Remote storage:** Firestore (source of truth for sync, source of truth once a device comes back online)
- **Auth:** Firebase Phone Auth (OTP via SMS)
- **DI:** manual (no Hilt yet — see `di/DatabaseModule.kt`, currently commented out; wiring happens by hand in `MainActivity.kt`)
- **Build:** Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, compileSdk/targetSdk 37, minSdk 24, JDK 21

## Setup

1. **Clone and open in Android Studio** (a recent version that supports AGP 9.2.1 / compileSdk 37).
2. **JDK 21 is required.** The Gradle toolchain (`gradle/gradle-daemon-jvm.properties`) will fetch it automatically via the Foojay resolver if it's not already installed.
3. **Firebase config:** `app/google-services.json` is already committed (this is normal — the app relies on Firestore security rules for access control, not on keeping this file secret). It points at the `com.vestateck.smetracker` Firebase app.
4. **Build:**
   ```
   ./gradlew assembleDebug
   ```
5. **Run tests:**
   ```
   ./gradlew testDebugUnitTest
   ```
6. **Lint:**
   ```
   ./gradlew lintDebug
   ```

CI (`.github/workflows/android-ci.yml`) runs lint, unit tests, and a debug build on every push/PR to `main`, and uploads the lint report and debug APK as run artifacts.

## Architecture

### Data flow

```
Compose UI  →  SMEViewModel  →  SMERepository  →  Room (local, offline cache)
                                                       ↕
                                                 SyncEngine  ↔  Firestore (remote)
```

- **Room is what the UI reads from.** Every screen observes Room via `SMERepository`/`SMEViewModel`, so the app works fully offline.
- **Every entity** (`Sale`, `Debt`, `Expense`, `InventoryItem`, `Customer`, `Task`, `StockAdjustment`) has a `pendingSync` flag. Local writes set it to `true`.
- **Firestore is the source of truth for cross-device sync.** `SyncEngine` is the only thing that talks to Firestore directly.

### How sync works (`data/remote/sync/SyncEngine.kt`)

**Read path:** one Firestore snapshot listener per collection the current session is allowed to read (scoped by role — see below). Every added/modified doc is upserted into Room with `pendingSync = false`, since it just arrived from the server.

**Write path:** `requestPush()` is called by the ViewModel after any local mutation. It walks every locally-pending row across every entity and pushes each to Firestore, clearing `pendingSync` on success. There's no retry/backoff/WorkManager — a failed push is simply retried on the next `requestPush()` call (the next edit, or the next `attachListeners()` catch-up on reconnect/re-login).

**Role-based split (Owner vs. Worker):**

The owner/worker split mirrors `firestore.rules` exactly, so a rejected write from a worker is expected behavior, not a bug:

- `saleFinancials` and `inventoryCosts` are owner-write-only. A worker's push never attempts those writes. The financials half of a worker-recorded sale or inventory item simply doesn't exist in Firestore until an owner reconciles it via the **Reconciliation screen** (`SMEViewModel.reconcileSale` / `reconcileInventoryCost`). `Sale.financialsReconciled` / `InventoryItem.costReconciled` track whether that's happened yet.
- A worker's **expenses** listener is scoped with `.whereEqualTo("recordedBy", myPhone)`, because Firestore requires list queries to be provably restricted the same way the security rule restricts them — an unfiltered query from a worker is denied outright, not silently filtered. The phone number is threaded in from the session at attach time, so this is correct from the very first attach, not just after a second one.
- A worker's expense submission always pushes as `PENDING` with no approval fields (required by the create rule). An owner's own entry auto-pushes as `APPROVED` (there's no approve/reject UI yet for an owner reviewing their own submissions).
- Owner-only collections (`saleFinancials`, `inventoryCosts`) are only attached at all on an owner's device — a worker's device never even opens those listeners, matching what the rules already deny.

**Known limitations** (by design, not yet addressed):
- Deletions are not synced in either direction.
- No conflict resolution — last write wins, whichever side writes last.
- Push only runs when requested, not on a timer or connectivity change.
- The reconciliation-pending notification (`ReconciliationNotifier`) is local-only: it requires `SyncEngine`'s process to be alive and collecting. It won't wake the app if killed — that would need FCM plus a server-side Cloud Function watching the sales/inventory collections, which doesn't exist yet.

### Auth (`data/remote/auth/`)

- **Sign-in:** Firebase Phone Auth. `AuthRepository.startPhoneVerification()` kicks off OTP via SMS and emits `CodeSent`, `AutoVerified` (Play Services auto-retrieval), or `VerificationFailed` as a cold `Flow`.
- **Business/role resolution:** after sign-in, `AuthRepository.resolvePhoneIndex()` looks up `phoneIndex/{phoneNumberE164}` in Firestore to resolve which business the phone belongs to and what role (`OWNER`/`WORKER`) it has. `MemberRole.fromString` parses this case-insensitively, but the enum names match the exact strings (`'OWNER'`/`'WORKER'`) that Firestore security rules check against.
- **Session state:** `SessionManager` holds the current session (business ID, role, phone number) that `SyncEngine` and the UI react to.

### Database (`data/database/SMEDatabase.kt`)

Room database, currently at schema version 10. Most version bumps pre-launch used `fallbackToDestructiveMigration` since there was no user data to preserve yet.

**Rule going forward:** any schema change from v9→v10 onward must ship a real `Migration` object — no more destructive fallback. See `MIGRATION_9_10` for the pattern (it doesn't touch the locally-merged cost/profit columns, so pulling remote data and reconciling costs locally can't clobber each other regardless of order).

### Testing

Unit tests cover the pure, Android/Firebase-free logic — `DashboardAnalytics`, `TimeUtils`, `CurrencyUtils`, `MemberRole` parsing. Test fixtures for `Sale`, `Debt`, `Expense`, `InventoryItem`, and `Customer` must pass an explicit `id` string, since their default (`IdGenerator.newId()`) calls `FirebaseFirestore.getInstance()`, which crashes in a plain JVM test.

Not yet covered: `SMEViewModel`'s reconciliation math (`reconcileSale`) against a mocked repository, and `SyncEngine` itself (would need a fake/mocked `Firestore` instance).

## Known follow-ups

- `SyncEngine.kt` is ~710 lines and the most conceptually dense file in the repo — a candidate for splitting once sync behavior stabilizes (e.g. one file per entity, or read-path/write-path separation).
- Hilt DI is scaffolded but not wired in (`di/DatabaseModule.kt` is commented out); DI is currently manual in `MainActivity.kt`.