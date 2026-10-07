# SME Tracker

Android app for small business inventory, sales, debt, and expense tracking, built for the Ugandan SME market (amounts in UGX). Kotlin + Jetpack Compose, offline-first with Room, synced to Firestore.

**New to this repo?** A business has two kinds of members: an **Owner** (sees all financials, approves expenses, reconciles cost/profit data) and a **Worker** (records day-to-day sales/inventory/expenses but can't see cost or profit - that's added later by an Owner). Most role-related code you'll run into enforces that split. Good starting points: `SMEViewModel.kt` (what the UI can do), `SyncEngine.kt` (how local and remote data reconcile), and `firestore.rules` / `storage.rules` in the repo root (the actual access control - the app's role checks exist to avoid triggering writes the rules would reject anyway).

## Features

- **Sales and receipts** - multi-item checkout, provisional receipt numbers that work offline, printable/shareable till-slip receipts
- **Inventory** - item photos, camera and hardware barcode scanning, bulk add, CSV import, stock adjustments and recounts
- **Debts and customers** - debt tracking, customer list, top-customers report
- **Expenses** - worker submissions with owner approval, receipt photos
- **Tasks** - shared task list
- **Reports** - sales, debt, inventory, payment breakdown, PDF export
- **Owner reconciliation** - owners fill in cost/profit data for items and sales workers recorded
- **Team** - owners add workers by phone number
- **Offline login** - after one online OTP verification, a device-local PIN unlocks the app with no network

## Stack

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **Local storage:** Room (offline cache, source of truth for the UI)
- **Remote storage:** Firestore (source of truth for cross-device sync) and Firebase Storage (item and receipt photos)
- **Auth:** Firebase Phone Auth (OTP via SMS), with Firebase App Check (Play Integrity in release, debug provider in debug)
- **DI:** Hilt - see `di/DatabaseModule.kt`, `di/RepositoryModule.kt`, `di/SyncModule.kt`. `SyncEngine` runs on a `@Singleton` `@ApplicationScope` `CoroutineScope` (not an Activity's `lifecycleScope`) so it can be shared between `MainActivity` and `SyncWorker` (a `@HiltWorker`).
- **Background work:** WorkManager (`SyncWorker`)
- **Scanning:** CameraX + ML Kit barcode scanning
- **Monitoring:** Firebase Crashlytics and Analytics
- **Build:** Gradle 9.6.1, AGP 9.2.1, Kotlin 2.4.10, KSP 2.3.10, Room 2.8.4, Firebase BoM 34.16.0, compileSdk/targetSdk 37, minSdk 24, JDK 21 (exact versions live in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`)

## Setup

1. **Clone and open in Android Studio** (a recent version that supports AGP 9.2.1 / compileSdk 37).
2. **JDK 21 is required.** The Gradle toolchain (`gradle/gradle-daemon-jvm.properties`) will fetch it automatically via the Foojay resolver if it's not already installed.
3. **Firebase config:** `app/google-services.json` is committed (this is normal - access control relies on Firestore/Storage security rules, not on keeping this file secret). It points at the `com.vestateck.smetracker` Firebase app in project `smetracker-9825a`. Note that `.gitignore` also lists this file, but it was committed before that entry existed, so git keeps tracking it.
4. **Register your signing fingerprints** (required for phone sign-in to work - see [Troubleshooting](#troubleshooting)).
5. **Build:**
   ```
   ./gradlew assembleDebug
   ```
6. **Run unit tests:**
   ```
   ./gradlew testDebugUnitTest
   ```
7. **Lint:**
   ```
   ./gradlew lintDebug
   ```

On Windows use `.\gradlew` in PowerShell.

CI (`.github/workflows/android-ci.yml`) has two jobs on every push/PR to `main`:

- **build** (ubuntu): lint, unit tests, debug build; uploads the lint report and debug APK as artifacts.
- **instrumented-tests** (macos, runs after `build`): starts the Firebase Auth + Firestore emulators, boots an API 34 emulator, and runs `connectedDebugAndroidTest`.

Release lint/build are not run in CI, since they require a signing config CI doesn't have - see **Release** below.

## Firebase setup

- **Project:** `smetracker-9825a` (see `.firebaserc`).
- **Phone auth:** enable Phone as a sign-in method. Check Authentication -> Settings -> SMS region policy allows Uganda (+256). For development, add numbers under Authentication -> Sign-in method -> Phone -> *Phone numbers for testing* so you don't spend SMS quota or hit integrity checks.
- **SHA fingerprints:** add the SHA-1 and SHA-256 of every key that signs a build you'll run (see below).
- **Play Integrity API:** must be enabled in the Google Cloud project behind the Firebase project.
- **App Check:** the debug provider is used in debug builds. Register each debug device's token (Logcat, filter `App Check debug secret`) under App Check -> Apps -> Manage debug tokens if enforcement is on.
- **Rules:** `firestore.rules` and `storage.rules` are the source of truth and are wired up in `firebase.json`. Expense receipt files can only be written by the recording member or an owner (mirroring the Firestore expense rules); inventory photos can be added or replaced by any member, and deleted only by an owner. Deploy with:
  ```
  firebase deploy --only firestore:rules,storage
  ```
- **Emulators:** `firebase emulators:start --only auth,firestore --project smetracker-9825a` (ports in `firebase.json`). Instrumented tests expect them at `10.0.2.2:9099` / `:8080`.

## Release

Release builds (`isMinifyEnabled = true`, `isShrinkResources = true`) are signed using a config read from `app/keystore.properties`, which is gitignored and never committed. Without that file, `assembleRelease` still succeeds but produces an **unsigned** APK.

1. **Generate the upload keystore once** (keep it forever - Play Store uploads require the same upload key for the life of the app; if it's lost, publishing updates to the existing listing needs a Play support key reset):
   ```
   keytool -genkeypair -v -keystore smetracker-upload.jks \
     -alias smetracker -keyalg RSA -keysize 2048 -validity 10000
   ```
   Back it up somewhere off-machine along with its passwords.

2. **Wire it up locally:**
   ```
   cp app/keystore.properties.example app/keystore.properties
   ```
   Edit `app/keystore.properties` with the real `storeFile` (path relative to `app/`), `storePassword`, `keyAlias`, and `keyPassword`.

3. **Bump the version** in `app/build.gradle.kts` (`versionCode` must be higher than anything already uploaded to Play; `versionName` is the user-facing string).

4. **Build:**
   ```
   ./gradlew clean bundleRelease      # .aab for Play: app/build/outputs/bundle/release/
   ./gradlew assembleRelease          # .apk for direct install: app/build/outputs/apk/release/
   ./gradlew lintRelease --stacktrace
   ```

5. **Verify the signature** of an APK with `apksigner` (in the Android SDK's `build-tools/<version>/` folder, not on PATH by default):
   ```
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

6. **Show your fingerprints** any time with:
   ```
   ./gradlew signingReport
   ```

### Fingerprints Firebase needs

| Build installed from | Key whose SHA-1 and SHA-256 must be registered |
|---|---|
| Android Studio / debug APK | debug keystore (`~/.android/debug.keystore`) |
| Release APK you built and sideloaded | your upload key (`smetracker-upload.jks`) |
| Google Play (any track) | the **Play App Signing key** (Play Console -> Setup -> App signing) |

After adding fingerprints, re-download `google-services.json` and replace `app/google-services.json`.

### Before a public Play release

- Publish the privacy policy and account-deletion pages, update the placeholder URLs in `utils/AppLinks.kt`, and register them in Play Console (Data safety and App content -> Account deletion).
- Confirm the Play App Signing fingerprints are in Firebase.
- Confirm every schema change since the last release has a real Room `Migration` (see Database below) and that `app/schemas/` is committed.

## Troubleshooting

**"This app is not authorized to use Firebase Authentication ... Invalid app info in play_integrity_token"**
Phone sign-in is sending an integrity token Firebase can't match to a registered app. Check, in order:
1. The SHA-1 and SHA-256 of the key that signed this build are registered (table above), and `google-services.json` was re-downloaded afterwards.
2. The Play Integrity API is enabled in the Google Cloud project.
3. The device has Google Play: use an emulator image with the Play Store icon, and update Google Play Services.
4. If App Check enforcement is on, the debug device's token is registered.
5. While developing, use a Firebase *phone number for testing* to bypass SMS and integrity.

## Architecture

### Data flow

```
Compose UI  ->  SMEViewModel  ->  SMERepository  ->  Room (local, offline cache)
                                                           ^
                                                           v
                                                     SyncEngine  <->  Firestore (remote)
```

- **Room is what the UI reads from.** Every screen observes Room via `SMERepository`/`SMEViewModel`, so the app works fully offline.
- `SMEViewModel` delegates domain logic to per-domain classes in `viewmodel/actions/` (`SaleActions`, `InventoryActions`, `ExpenseActions`, `DebtActions`, `CustomerActions`, `TaskActions`, `ReconciliationActions`).
- **Synced entities** (`Sale`, `Debt`, `Expense`, `InventoryItem`, `Customer`, `Task`, `StockAdjustment`) have a `pendingSync` flag. Local writes set it to `true`.
- **Firestore is the source of truth for cross-device sync.** `SyncEngine` and the per-entity classes in `data/remote/sync/entities/` are the only code that talks to Firestore for business data.

### How sync works (`data/remote/sync/SyncEngine.kt`)

**Read path:** one Firestore snapshot listener per collection the current session is allowed to read (scoped by role). Every added/modified doc is upserted into Room with `pendingSync = false`, since it just arrived from the server. Listeners are (re)attached when the session's business changes.

**Write path:** `requestPush()` is called after any local mutation. It walks every locally-pending row across every entity and pushes each to Firestore, clearing `pendingSync` on success. A failed push is retried on the next `requestPush()` (the next edit, or the next `attachListeners()` catch-up on reconnect/re-login), and also by `SyncWorker` (`data/remote/sync/entities/SyncWorker.kt`), a `@HiltWorker` sharing the same `@Singleton SyncEngine`. It runs a 15-minute `PeriodicWorkRequest` constrained to `NetworkType.CONNECTED` with exponential backoff, plus a one-time `triggerImmediateSync()` queued after offline edits. `MainActivity` schedules the periodic worker on session launch.

**Role-based split (Owner vs. Worker):**

The owner/worker split mirrors `firestore.rules`, so a rejected write from a worker is expected behavior, not a bug:

- `saleFinancials` and `inventoryCosts` are owner-write-only. A worker's push never attempts those writes. The financials half of a worker-recorded sale or inventory item doesn't exist in Firestore until an owner reconciles it via the **Reconciliation screen** (`SMEViewModel.reconcileSale` / `reconcileInventoryCost`). `Sale.financialsReconciled` / `InventoryItem.costReconciled` track whether that's happened yet.
- A worker's **expenses** listener is scoped with `.whereEqualTo("recordedBy", myPhone)`, because Firestore requires list queries to be provably restricted the same way the security rule restricts them - an unfiltered query from a worker is denied outright, not silently filtered.
- A worker's expense submission always pushes as `PENDING` with no approval fields (required by the create rule). An owner's own entry auto-pushes as `APPROVED`.
- Owner-only collections (`saleFinancials`, `inventoryCosts`) are only attached on an owner's device.

**Soft deletes:** every entity carries an `isDeleted` tombstone column. `SMERepository.delete*()` marks the local row `isDeleted = 1, pendingSync = 1` rather than issuing a Room `@Delete` (the DAO-level `@Delete` methods still exist but are unused by the app - kept for tests). The next push carries `isDeleted` to Firestore in the entity's normal remote document, and incoming listeners merge it back in, so deletes propagate to other devices as ordinary field updates. Row-reading queries filter `WHERE isDeleted = 0`. Tombstones are not currently purged.

**Known limitations** (by design, not yet addressed):
- No conflict resolution - last write wins.
- The reconciliation-pending notification (`ReconciliationNotifier`) is local-only: it requires `SyncEngine`'s process to be alive. Waking a killed app would need FCM plus a Cloud Function watching the sales/inventory collections, which doesn't exist yet.
- Local tables have no `businessId` column; isolation relies on the data-owner marker above rather than on the schema.
- Listeners are keyed on the business ID, so a role change inside the same business is not picked up until the session is re-established.
- Workers can edit or soft-delete sales they recorded themselves (see `firestore.rules`); there is no append-only audit trail.

### Auth (`data/remote/auth/`)

- **Sign-in:** Firebase Phone Auth. `AuthRepository.startPhoneVerification()` kicks off OTP via SMS and emits `CodeSent`, `AutoVerified` (Play Services auto-retrieval), or `VerificationFailed` as a cold `Flow`.
- **Business/role resolution:** after sign-in, `AuthRepository.resolvePhoneIndex()` looks up `phoneIndex/{phoneNumberE164}` in Firestore to resolve which business the phone belongs to and what role (`OWNER`/`WORKER`) it has. `MemberRole.fromString` parses this case-insensitively, but the enum names match the exact strings (`'OWNER'`/`'WORKER'`) the security rules check. A phone number belongs to at most one business.
- **Session state:** `SessionManager` holds the current session (business ID, role, phone number) that `SyncEngine` and the UI react to. The session DataStore file is excluded from Android backup so a restore can't leave the app believing it's logged in without a live Firebase session.
- **Local data ownership:** Room is not businessId-scoped, so `SessionManager` records which business owns the rows in Room (`DATA_BUSINESS_ID`). `saveBusinessMembership()` keeps the data, including unsynced rows, when the same business signs back in, and wipes every table when a different business (or no known owner) links. `SyncEngine` also refuses to attach listeners or push if the marker disagrees with the session. Switching accounts on a device therefore discards that device's unsynced rows for the previous business. Installs that predate the marker fall back to the old signals, so upgrading users keep their data.
- **Offline PIN login:** after one successful online OTP verification per business, the user sets a 4-6 digit PIN. `PinHasher` stores a salted PBKDF2 hash in the local-only `local_credentials` Room table (never synced). Subsequent launches can unlock with the PIN and no network.
- **PIN brute-force protection:** `SessionManager.attemptPinLogin()` applies `PinLockoutPolicy`: 5 wrong PINs lock the PIN screen for 30 s, doubling per further failure up to 15 min, and the 10th wrong PIN erases the device credential and forces a full OTP sign-in. Counters live in the session DataStore and survive restarts and sign-out. This protects the PIN screen, not the stored hash: a rooted device's database file could still be attacked offline, and the lockout relies on the device clock. The Room database is excluded from Android backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`) so the hash does not leave the device that way.

### Database (`data/database/SMEDatabase.kt`)

Room database, currently at **schema version 17**, with `exportSchema = true`. Schemas are written to `app/schemas/` on build (KSP `room.schemaLocation`); commit them so migrations can be reviewed and tested.

**Rule going forward:** any schema change from v9 onward must ship a real `Migration` object. See `MIGRATION_9_10` for the pattern. The builder only allows a destructive fallback from versions 1-8 (installs from before a complete migration chain existed); an upgrade from v9 or later with a missing `Migration` fails loudly instead of wiping local data and any unsynced rows.

### Testing

- **Unit tests** (`app/src/test`, 15 test files): pure Android/Firebase-free logic - `DashboardAnalytics`, `TimeUtils`, `CurrencyUtils`, `MemberRole` parsing, `CheckoutGrouping` (receipt grouping), `SaleMerge`, `BarcodeScanResolver`, `PinLockoutPolicy`, `SMEViewModel` reconciliation math, and the `viewmodel/actions/*` delegates - using hand-rolled `FakeSMEDao`/`FakeInventoryDao` fixtures (no mocking library). Test fixtures for `Sale`, `Debt`, `Expense`, `InventoryItem`, and `Customer` must pass an explicit `id` string, since their default (`IdGenerator.newId()`) calls `FirebaseFirestore.getInstance()`, which crashes in a plain JVM test.
- **Instrumented tests** (`app/src/androidTest`): in-memory Room (`SMEDatabaseTest`, DAO tests), `SessionManagerTest` (including PIN lockout and business-switch data ownership), phone auth against the Auth emulator, and emulator-backed sync tests for sales, inventory, stock adjustments, expenses, and the shared entities (customers, debts, tasks). These use `FirebaseEmulatorRule` and run in CI on every push/PR.
- **Not yet covered:** `SyncEngine` orchestration itself (listener attach/detach, role scoping) and `SyncWorker`.

Run the instrumented suite locally by starting the emulators (see Firebase setup) and running `./gradlew connectedDebugAndroidTest` against an emulator or device.

## Receipts

Sale receipts use a provisional-now/reconciled-later pattern, matching the cost/profit reconciliation shape used elsewhere in the app:

- **Provisional number:** `ReceiptNumberGenerator` (`utils/ReceiptNumberGenerator.kt`) mints a locally-scoped receipt number the instant a sale is recorded, so a receipt can be shown/shared/printed fully offline. Format is `{last 4 digits of phone}-{6-digit local sequence}` (e.g. `0771-000042`) - two devices can't collide (different phone suffix), and a device's own sequence only increases, even across restarts (SharedPreferences-backed).
- **Authoritative number:** never the provisional one. `Sale.finalReceiptNumber` is claimed via a Firestore transaction on `businesses/{id}/counters/receiptSequence` in `SaleSync.pushPending` once the device is online, giving a real global sequence number. Receipts are rendered by `ReceiptRenderer` in a printed till-slip style.

## Project layout

```
app/src/main/java/com/vestateck/smetracker/
  data/          Room entities, DAOs, database, remote models, sync, auth
  repository/    SMERepository
  viewmodel/     SMEViewModel + actions/ delegates
  screens/       Compose screens (sales, inventory, reports, expenses, ...)
  ui/            theme, shared components, auth/PIN/business-setup screens
  navigation/    Screen routes
  notifications/ reconciliation notifier
  di/            Hilt modules
  utils/         receipts, PDF reports, CSV import, PIN hashing, currency/time helpers
firestore.rules, storage.rules, firebase.json   Firebase config (repo root)
```