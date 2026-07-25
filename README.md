# SME Tracker

Android app for small business inventory, sales, debt, and expense tracking, built for the Ugandan SME market. Kotlin + Jetpack Compose, offline-first with Room, synced to Firestore.

**New to this repo?** A business has two kinds of members: an **Owner** (sees all financials, approves expenses, reconciles cost/profit data) and a **Worker** (records day-to-day sales/inventory/expenses but can't see cost or profit — that's added later by an Owner). Most role-related code you'll run into is enforcing that split. Good starting points for reading the code: `SMEViewModel.kt` (what the UI can do), `SyncEngine.kt` (how local and remote data reconcile), and `firestore.rules` in the Firebase console (the actual access control — the app's role checks exist to avoid triggering writes the rules would reject anyway).

## Stack

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **Local storage:** Room (offline cache, source of truth for the UI)
- **Remote storage:** Firestore (source of truth for sync, source of truth once a device comes back online)
- **Auth:** Firebase Phone Auth (OTP via SMS)
- **DI:** Hilt — see `di/DatabaseModule.kt`, `di/RepositoryModule.kt`, `di/SyncModule.kt`. `SyncEngine` runs on a `@Singleton` `@ApplicationScope` `CoroutineScope` (not an Activity's `lifecycleScope`) so it can be shared between `MainActivity` and `SyncWorker` (a `@HiltWorker`) instead of each hand-rolling its own instance.
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

CI (`.github/workflows/android-ci.yml`) runs lint, unit tests, and a debug build on every push/PR to `main`, and uploads the lint report and debug APK as run artifacts. Release lint/build are not run in CI, since they require a signing config CI doesn't have — see **Release signing** below for testing that path locally.

## Release signing

Release builds (`isMinifyEnabled = true`, `isShrinkResources = true`) are signed using a config read from `app/keystore.properties`, which is gitignored and never committed. Without that file, `assembleRelease` still succeeds but produces an **unsigned** APK (this is what CI would get if it ever ran `assembleRelease`, which is why CI only builds `debug`).

1. **Generate the upload keystore once** (keep it forever — Play Store uploads require the same upload key for the life of the app; if it's lost, there's no way to publish updates to the existing listing again):
   ```
   keytool -genkeypair -v -keystore smetracker-upload.jks \
     -alias smetracker -keyalg RSA -keysize 2048 -validity 10000
   ```
   Back it up somewhere off-machine (cloud storage, password manager, etc.) along with its passwords — losing it is unrecoverable.

2. **Wire it up locally:**
   ```
   cp app/keystore.properties.example app/keystore.properties
   ```
   Then edit `app/keystore.properties` with the real `storeFile` (path relative to `app/`), `storePassword`, `keyAlias`, and `keyPassword`.

3. **Build and verify:**
   ```
   ./gradlew assembleRelease --stacktrace
   ./gradlew lintRelease --stacktrace
   ```
   Then confirm the signature with `apksigner` (ships in the Android SDK's `build-tools/<version>/` folder, not on PATH by default):
   ```
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
   This prints the signer certificate's SHA-256 fingerprint — save it, and cross-check it against what Play Console shows on first upload to confirm you're signing with the key you think you are.

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

**Write path:** `requestPush()` is called by the ViewModel after any local mutation. It walks every locally-pending row across every entity and pushes each to Firestore, clearing `pendingSync` on success. A failed push is retried on the next `requestPush()` call (the next edit, or the next `attachListeners()` catch-up on reconnect/re-login), and also by `SyncWorker` (`data/remote/sync/entities/SyncWorker.kt`) — a `@HiltWorker` sharing the same `@Singleton SyncEngine` — which runs a 15-minute `PeriodicWorkRequest` constrained to `NetworkType.CONNECTED` with exponential backoff, plus a one-time `triggerImmediateSync()` request queued after offline edits. `MainActivity` schedules the periodic worker on session launch.

**Role-based split (Owner vs. Worker):**

The owner/worker split mirrors `firestore.rules` exactly, so a rejected write from a worker is expected behavior, not a bug:

- `saleFinancials` and `inventoryCosts` are owner-write-only. A worker's push never attempts those writes. The financials half of a worker-recorded sale or inventory item simply doesn't exist in Firestore until an owner reconciles it via the **Reconciliation screen** (`SMEViewModel.reconcileSale` / `reconcileInventoryCost`). `Sale.financialsReconciled` / `InventoryItem.costReconciled` track whether that's happened yet.
- A worker's **expenses** listener is scoped with `.whereEqualTo("recordedBy", myPhone)`, because Firestore requires list queries to be provably restricted the same way the security rule restricts them — an unfiltered query from a worker is denied outright, not silently filtered. The phone number is threaded in from the session at attach time, so this is correct from the very first attach, not just after a second one.
- A worker's expense submission always pushes as `PENDING` with no approval fields (required by the create rule). An owner's own entry auto-pushes as `APPROVED` (there's no approve/reject UI yet for an owner reviewing their own submissions).
- Owner-only collections (`saleFinancials`, `inventoryCosts`) are only attached at all on an owner's device — a worker's device never even opens those listeners, matching what the rules already deny.

**Soft deletes:** every entity carries an `isDeleted` tombstone column. `SMERepository.delete*()` calls mark the local row `isDeleted = 1, pendingSync = 1` rather than issuing a Room `@Delete` (the DAO-level `@Delete` methods still exist but are unused by the app — kept for tests). The next push carries `isDeleted` to Firestore in the entity's normal `RemoteX` document (e.g. `RemoteSale.isDeleted` in `SaleSync.pushPending`), and incoming listeners merge it back in on pull, so a delete on one device does propagate to others — it's a soft delete synced like any other field update, not a hard Firestore document delete. Row-reading queries filter `WHERE isDeleted = 0`.

**Known limitations** (by design, not yet addressed):
- No conflict resolution — last write wins, whichever side writes last.
- The reconciliation-pending notification (`ReconciliationNotifier`) is local-only: it requires `SyncEngine`'s process to be alive and collecting. It won't wake a killed app — that would need FCM plus a server-side Cloud Function watching the sales/inventory collections, which doesn't exist yet.
- `SyncEngine` and the per-entity sync classes (`SaleSync`, `InventorySync`, etc.) have no automated test coverage — only the pure logic deliberately split out of them (`SaleMerge.kt`, `CheckoutGrouping.kt`) is unit tested. Exercising the sync classes themselves would need a fake/mocked `Firestore`, or the `androidTest` emulator harness (`FirebaseEmulatorRule.kt`) extended to cover them — currently that harness only covers auth and one DAO test, and isn't run in CI (see below).

### Auth (`data/remote/auth/`)

- **Sign-in:** Firebase Phone Auth. `AuthRepository.startPhoneVerification()` kicks off OTP via SMS and emits `CodeSent`, `AutoVerified` (Play Services auto-retrieval), or `VerificationFailed` as a cold `Flow`.
- **Business/role resolution:** after sign-in, `AuthRepository.resolvePhoneIndex()` looks up `phoneIndex/{phoneNumberE164}` in Firestore to resolve which business the phone belongs to and what role (`OWNER`/`WORKER`) it has. `MemberRole.fromString` parses this case-insensitively, but the enum names match the exact strings (`'OWNER'`/`'WORKER'`) that Firestore security rules check against.
- **Session state:** `SessionManager` holds the current session (business ID, role, phone number) that `SyncEngine` and the UI react to.

### Database (`data/database/SMEDatabase.kt`)

Room database, currently at schema version 14. Most version bumps pre-launch used `fallbackToDestructiveMigration` since there was no user data to preserve yet.

**Rule going forward:** any schema change from v9→v10 onward must ship a real `Migration` object — no more destructive fallback. See `MIGRATION_9_10` for the pattern (it doesn't touch the locally-merged cost/profit columns, so pulling remote data and reconciling costs locally can't clobber each other regardless of order).

### Testing

Unit tests cover the pure, Android/Firebase-free logic — `DashboardAnalytics`, `TimeUtils`, `CurrencyUtils`, `MemberRole` parsing, the checkout-grouping logic behind receipt numbering (`CheckoutGrouping.kt`, tested in `CheckoutGroupingTest.kt`) — plus `SMEViewModel`'s reconciliation math (`reconcileSale`/`reconcileInventoryCost`) in `SMEViewModelReconciliationTest.kt`, using hand-rolled `FakeSMEDao`/`FakeInventoryDao` fixtures (no mocking library in the project). Test fixtures for `Sale`, `Debt`, `Expense`, `InventoryItem`, and `Customer` must pass an explicit `id` string, since their default (`IdGenerator.newId()`) calls `FirebaseFirestore.getInstance()`, which crashes in a plain JVM test.

Not yet covered: the rest of `SyncEngine` and its per-entity sync classes (would need a fake/mocked `Firestore` instance) — `CheckoutGrouping.kt` was deliberately split out of `SaleSync.pushPending` so its logic could be tested without one.

## Receipts

Sale receipts use a provisional-now/reconciled-later pattern, matching the cost/profit reconciliation shape used elsewhere in the app:

- **Provisional number:** `ReceiptNumberGenerator` (`utils/ReceiptNumberGenerator.kt`) mints a locally-scoped receipt number the instant a sale is recorded, so a receipt can be shown/shared/printed fully offline. Format is `{last 4 digits of phone}-{6-digit local sequence}` (e.g. `0771-000042`) — two devices can't collide (different phone suffix), and a device's own sequence only increases, even across restarts (SharedPreferences-backed).
- **Authoritative number:** never the provisional one. `Sale.finalReceiptNumber` is claimed via a Firestore transaction in `SaleSync.pushPending` once the device is online, giving a real global sequence number.