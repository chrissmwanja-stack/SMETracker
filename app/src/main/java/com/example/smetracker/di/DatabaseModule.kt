package com.example.smetracker.di

/*
// Hilt is not yet set up in this project.
// The project is currently using manual dependency injection in AppNavigation.kt.
// If you want to use Hilt, you will need to add the necessary dependencies to build.gradle.kts.

import android.content.Context
import androidx.room.Room
import com.example.smetracker.data.database.SMEDatabase
import com.example.smetracker.data.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SMEDatabase {
        return Room.databaseBuilder(
            context,
            SMEDatabase::class.java,
            "sme_tracker.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideSaleDao(database: SMEDatabase): SaleDao = database.saleDao()

    @Provides
    fun provideCustomerDao(database: SMEDatabase): CustomerDao = database.customerDao()

    @Provides
    fun provideDebtDao(database: SMEDatabase): DebtDao = database.debtDao()

    @Provides
    fun provideInventoryDao(database: SMEDatabase): InventoryDao = database.inventoryDao()

    @Provides
    fun provideExpenseDao(database: SMEDatabase): ExpenseDao = database.expenseDao()

    @Provides
    fun provideTaskDao(database: SMEDatabase): TaskDao = database.taskDao()
}
*/
