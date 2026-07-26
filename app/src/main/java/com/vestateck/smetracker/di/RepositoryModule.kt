package com.vestateck.smetracker.di

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.LocalCredentialDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.remote.auth.AuthRepository
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.ReceiptNumberGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val TAG = "RepositoryModule"

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth {
        val auth = FirebaseAuth.getInstance()
        if (isRunningTest()) {
            try {
                Log.i(TAG, "Configuring FirebaseAuth for emulator (10.0.2.2:9099)")
                auth.useEmulator("10.0.2.2", 9099)
                auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseAuth emulator already set or failed: \${e.message}")
            }
        }
        return auth
    }

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore {
        val firestore = FirebaseFirestore.getInstance()
        if (isRunningTest()) {
            try {
                Log.i(TAG, "Configuring FirebaseFirestore for emulator (10.0.2.2:8080)")
                firestore.useEmulator("10.0.2.2", 8080)
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseFirestore emulator already set or failed: \${e.message}")
            }
        }
        return firestore
    }

    private fun isRunningTest(): Boolean {
        val result = try {
            Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            true
        } catch (e: ClassNotFoundException) {
            try {
                Class.forName("androidx.test.InstrumentationRegistry")
                true
            } catch (e2: ClassNotFoundException) {
                false
            }
        }
        return result
    }

    @Provides
    @Singleton
    fun provideSMERepository(smeDao: SMEDao, inventoryDao: InventoryDao): SMERepository =
        SMERepository(smeDao, inventoryDao)

    @Provides
    @Singleton
    fun provideAuthRepository(auth: FirebaseAuth, firestore: FirebaseFirestore): AuthRepository =
        AuthRepository(auth, firestore)

    @Provides
    @Singleton
    fun provideBusinessRepository(firestore: FirebaseFirestore, auth: FirebaseAuth): BusinessRepository =
        BusinessRepository(firestore, auth)

    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        localCredentialDao: LocalCredentialDao,
        database: SMEDatabase
    ): SessionManager =
        SessionManager(context, localCredentialDao, database)

    @Provides
    @Singleton
    fun provideReceiptNumberGenerator(@ApplicationContext context: Context): ReceiptNumberGenerator =
        ReceiptNumberGenerator(context)
}
