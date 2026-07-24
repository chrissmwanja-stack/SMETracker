package com.vestateck.smetracker.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.LocalCredentialDao
import com.vestateck.smetracker.data.dao.SMEDao
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

/**
 * Repositories, session state, and small utility classes that used to be
 * `by lazy` vals in MainActivity. All @Singleton - process-lifetime, same as
 * before (this app has one Activity, so the old per-Activity lazy vals were
 * already effectively process-scoped in practice).
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

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
        localCredentialDao: LocalCredentialDao
    ): SessionManager =
        SessionManager(context, localCredentialDao)

    @Provides
    @Singleton
    fun provideReceiptNumberGenerator(@ApplicationContext context: Context): ReceiptNumberGenerator =
        ReceiptNumberGenerator(context)
}