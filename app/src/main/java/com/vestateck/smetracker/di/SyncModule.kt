package com.vestateck.smetracker.di

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the process-lifetime CoroutineScope SyncEngine runs on.
 *
 * Before Hilt, SyncEngine took MainActivity's lifecycleScope directly - fine
 * for a single-Activity app, but it meant SyncEngine could only ever be
 * constructed inside something with access to an Activity, which is exactly
 * why SyncWorker.doWork() had to hand-roll its own separate SyncEngine
 * instance instead of sharing this one. It also would have blocked injecting
 * SyncEngine into a @HiltViewModel: Hilt's ViewModelComponent can only see
 * SingletonComponent-installed bindings, not ActivityComponent ones.
 *
 * Using an application-scoped SupervisorJob-backed CoroutineScope instead
 * fixes both: SyncEngine becomes an ordinary @Singleton, injectable
 * anywhere (ViewModel, Activity, or Worker), and it now survives
 * configuration changes exactly like it survived Activity recreation
 * before. Its actual start/stop lifecycle is still controlled explicitly by
 * MainActivity's sign-in/sign-out flow, same as before - this only changes
 * what backs its coroutines, not when they run.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSyncEngine(
        smeDao: SMEDao,
        inventoryDao: InventoryDao,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore
    ): SyncEngine = SyncEngine(smeDao, inventoryDao, sessionManager, scope, context, firestore)
}