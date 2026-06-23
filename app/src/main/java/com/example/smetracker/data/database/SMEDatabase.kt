package com.example.smetracker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 5,
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

        fun getDatabase(context: Context): SMEDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SMEDatabase::class.java,
                    "sme_tracker_database"
                )
                    .fallbackToDestructiveMigration(false)  // add this
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}