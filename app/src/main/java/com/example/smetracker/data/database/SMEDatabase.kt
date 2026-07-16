package com.example.smetracker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smetracker.data.dao.DebtDao
import com.example.smetracker.data.dao.InventoryDao
import com.example.smetracker.data.dao.SaleDao
import com.example.smetracker.data.dao.SMEDao
import com.example.smetracker.data.entities.Customer
import com.example.smetracker.data.entities.Debt
import com.example.smetracker.data.entities.Expense
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.Sale
import com.example.smetracker.data.entities.Task

@Database(
    entities = [Sale::class, Customer::class, Debt::class, InventoryItem::class, Expense::class, Task::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SMEDatabase : RoomDatabase() {

    abstract fun smeDao(): SMEDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun saleDao(): SaleDao
    abstract fun debtDao(): DebtDao

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
        // rather than a hand-written Migration — the local DB is simply recreated.

        fun getDatabase(context: Context): SMEDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SMEDatabase::class.java,
                    "sme_tracker_database"
                )
                    .addMigrations(MIGRATION_5_6)
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