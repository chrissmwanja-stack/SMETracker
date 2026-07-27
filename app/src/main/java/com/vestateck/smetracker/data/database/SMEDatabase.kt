package com.vestateck.smetracker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vestateck.smetracker.data.dao.DebtDao
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.LocalCredentialDao
import com.vestateck.smetracker.data.dao.SaleDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.LocalCredential
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.entities.StockAdjustment
import com.vestateck.smetracker.data.entities.Task

@Database(
    entities = [Sale::class, Customer::class, Debt::class, InventoryItem::class, Expense::class, Task::class, StockAdjustment::class, LocalCredential::class],
    version = 17,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SMEDatabase : RoomDatabase() {

    abstract fun smeDao(): SMEDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun saleDao(): SaleDao
    abstract fun debtDao(): DebtDao
    abstract fun localCredentialDao(): LocalCredentialDao

    // Local Room storage has no businessId scoping on any entity - it's a
    // single shared cache of whatever business's Firestore data was synced
    // most recently, not a per-business store. Firestore is the real source
    // of truth and IS properly scoped (businesses/{businessId}/...); this is
    // purely about the on-device cache.
    //
    // NOT called from MainActivity's sign-out flow (was, until it was
    // determined redundant there - see git history / that file's onSignOut
    // comment). A normal sign-out -> PIN re-login never changes
    // SessionManager.deviceBusinessId, so it's always the SAME business's
    // data sitting in Room and stays valid to reuse untouched. The actual
    // cross-business risk this comment describes - a different business's
    // data leaking into a device that switches accounts - is prevented
    // downstream instead, by SessionManager.saveBusinessMembership()'s full
    // clearAllTablesSuspending() wipe, which fires exactly when
    // deviceBusinessId is null (first-ever link on this device, or right
    // after forgetDeviceCredential() resets it on an explicit account
    // switch). That full wipe is a superset of what this method does, so
    // calling this one first would add nothing.
    //
    // Kept as a real, separately-tested method (see SMEDatabaseTest) rather
    // than deleted, in case a future caller needs a synced-only partial
    // clear without touching pendingSync rows - which is genuinely
    // different behavior from clearAllTablesSuspending() below. Deletes
    // only rows already confirmed synced (pendingSync = 0) and leaves any
    // pendingSync = 1 rows in place, so a caller doesn't silently destroy
    // anything a worker recorded offline and hadn't gotten a chance to push.
    suspend fun clearSyncedDataSuspending() {
        withTransaction {
            smeDao().deleteSyncedSales()
            smeDao().deleteSyncedCustomers()
            smeDao().deleteSyncedDebts()
            smeDao().deleteSyncedExpenses()
            smeDao().deleteSyncedTasks()
            inventoryDao().deleteSyncedItems()
            inventoryDao().deleteSyncedAdjustments()
        }
    }

    // Full wipe of every local table - used once, the very first time a
    // device links to a business (see SessionManager.saveBusinessMembership()),
    // to discard any local Room data that predates that link: leftover
    // dev/test rows, or anything recorded before Firebase Auth was wired up.
    // Unlike clearSyncedDataSuspending() above, this isn't pendingSync-aware -
    // none of that data can be pendingSync for a business relationship that
    // never existed, so there's nothing worth preserving here.
    //
    // RoomDatabase.clearAllTables() manages its own locking and must be
    // called outside of a transaction - not wrapped in withTransaction like
    // clearSyncedDataSuspending() above.
    suspend fun clearAllTablesSuspending() = withContext(Dispatchers.IO) { clearAllTables() }

    companion object {
        @Volatile
        private var INSTANCE: SMEDatabase? = null

        // v5 -> v6: link sales to inventory items so profit/stock can be tracked per sale.
        // Preserves existing sales/customers/debts/inventory data instead of wiping the DB.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN inventoryItemId INTEGER")
                db.execSQL("ALTER TABLE sales ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_inventoryItemId ON sales(inventoryItemId)")
            }
        }

        // v6 -> v7: primary keys (and the FKs referencing them) switched from Long
        // autoincrement to client-generated Firestore String IDs, and a `pendingSync`
        // column was added to every entity for the Phase 3 sync engine. No real user
        // data exists yet, so this relies on fallbackToDestructiveMigration below
        // rather than a hand-written Migration - the local DB is simply recreated.

        // v7 -> v8: Sale gained `costPriceSnapshot`; Expense gained `recordedBy`,
        // `status`, `approvedBy`, `approvedAt` - needed to finish wiring the sync
        // engine to these two entities. Same as v6->v7, this rides on
        // fallbackToDestructiveMigration rather than a hand-written Migration.
        // IMPORTANT: if any device testing this build has real local data (sales,
        // expenses, etc.) you care about, back it up before upgrading - this wipes
        // the local Room database on version bump. Fine for now since this is still
        // pre-launch, but write a real Migration instead once there's user data
        // worth preserving across an app update.

        // v8 -> v9: added the stock_adjustments table (see StockAdjustment.kt) -
        // the audit log behind Incoming Stock / Recount. Same pre-launch
        // fallbackToDestructiveMigration as v6->v7 and v7->v8 above; write a
        // real Migration instead once there's real user data to preserve.

        // v9 -> v10: Sale and InventoryItem each gained `recordedBy` and a
        // reconciliation flag (`financialsReconciled` / `costReconciled`) -
        // backs the owner Reconciliation screen, which surfaces worker-
        // recorded sales/items whose cost/profit an owner hasn't reviewed
        // yet. First real hand-written migration since v6->v7: this app is
        // no longer pre-launch, so existing local data (sales, inventory)
        // must be preserved, not wiped. Both new boolean columns default to
        // 1 (true/reconciled) so pre-existing rows don't suddenly flood the
        // reconciliation queue - only sales/items created going forward can
        // be flagged false, and only when a worker's device is the one
        // creating them (see SMEViewModel.addSale / upsertInventoryItem).
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN recordedBy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN financialsReconciled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN recordedBy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN costReconciled INTEGER NOT NULL DEFAULT 1")
            }
        }

        // v10 -> v11: InventoryItem gained localImagePath, imageUrl, and
        // imagePendingUpload - backs the optional item photo (e.g. a
        // boutique photographing each cloth print). All three default to
        // NULL/0 (no photo), so existing items are unaffected until someone
        // deliberately adds a picture via InventoryItemDialog.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN localImagePath TEXT")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN imagePendingUpload INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v11 -> v12: Expense gained localReceiptPath, receiptUrl, and
        // receiptPendingUpload - backs the receipt photo attached as proof
        // for tax/audit purposes when recording an expense. Same pattern as
        // MIGRATION_10_11 (InventoryItem photo fields): all default to
        // NULL/0, existing expenses unaffected until a receipt is added.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN localReceiptPath TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN receiptUrl TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN receiptPendingUpload INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v12 -> v13: no actual column change. InventoryItem.category and
        // Expense.category picked up explicit @ColumnInfo(defaultValue=...)
        // annotations matching their existing Kotlin defaults ("" and
        // "General"). Room folds defaultValue into its computed schema hash,
        // so even though SQLite needs no ALTER here, the version still has
        // to move or Room's schema validation fails on next launch against
        // any device already running v12 - fallbackToDestructiveMigration
        // would otherwise silently wipe local data rather than crash.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Intentionally empty - see comment above.
            }
        }

        // v13 -> v14: Sale gained provisionalReceiptNumber and
        // finalReceiptNumber - backs sale-receipt generation. Existing rows
        // get provisionalReceiptNumber = '' (blank), which the receipt UI
        // should treat as "no number assigned" rather than displaying an
        // empty string - only sales created after this migration go through
        // SMEViewModel.addSale's ReceiptNumberGenerator call.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN provisionalReceiptNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN finalReceiptNumber TEXT")
            }
        }

        // v14 -> v15: added isDeleted column to most entities (Sale, Customer,
        // Debt, Expense, Task) - backs the soft-delete/tombstone strategy
        // needed for reliable multi-device sync (preventing deleted rows
        // on one device from simply re-downloading from Firestore during
        // the next sync pull).
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE customers ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE debts ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v15 -> v16: added local_credentials table - backs offline PIN login
        // (see LocalCredential.kt / SessionManager). Purely additive, no
        // existing tables touched; new table starts empty until a user
        // completes an online phone-OTP verification and sets a PIN.
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_credentials` (
                        `businessId` TEXT NOT NULL,
                        `phoneNumberE164` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `firebaseUid` TEXT NOT NULL,
                        `pinHash` TEXT NOT NULL,
                        `pinSalt` TEXT NOT NULL,
                        `lastVerifiedOnlineAt` INTEGER NOT NULL,
                        PRIMARY KEY(`businessId`)
                    )
                    """.trimIndent()
                )
            }
        }

        // v16 -> v17: InventoryItem gained `sku` (optional barcode/SKU) plus
        // a non-unique index on it - backs scan-to-add at checkout (see
        // AddSaleScreen's BarcodeScanField / InventoryDao.getItemBySku).
        // Defaults to NULL, so existing items are simply "no code assigned
        // yet" until someone scans or types one in via InventoryItemDialog.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN sku TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_items_sku ON inventory_items(sku)")
            }
        }

        fun getDatabase(context: Context): SMEDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SMEDatabase::class.java,
                    "sme_tracker_database"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                    // Safety net for older installs with no migration path defined (v1-v4).
                    // Any new schema change from here on should get its own Migration above
                    // instead of relying on this, or existing user data will be wiped on upgrade.
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}