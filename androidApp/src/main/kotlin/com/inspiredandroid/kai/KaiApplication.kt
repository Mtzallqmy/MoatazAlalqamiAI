package com.inspiredandroid.kai

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.sandbox.sandboxModule
import com.inspiredandroid.kai.security.MigrationMarkers
import com.inspiredandroid.kai.security.SecretStoreMigrationRunner
import com.inspiredandroid.kai.security.secretStoreModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class KaiApplication : Application() {

    private val taskScheduler: TaskScheduler by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KaiApplication)
            modules(appModule, sandboxModule, secretStoreModule(this@KaiApplication))
        }
        // One-time migration: plaintext API keys stored in legacy SharedPreferences
        // are moved into the Keystore-backed encrypted SecretStore.
        val helper = object : org.koin.core.component.KoinComponent {}
        SecretStoreMigrationRunner(
            secretStore = helper.get<com.inspiredandroid.kai.security.SecretStore>(),
            appSettings = helper.get<com.inspiredandroid.kai.data.AppSettings>(),
            markers = MigrationMarkers(this@KaiApplication),
        ).run()
        // Track app foreground state so the scheduler only pushes a heartbeat notification
        // when the in-app banner isn't visible. ViewModel lifecycle is the wrong signal —
        // it survives backgrounding and only clears on Activity destruction.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                taskScheduler.appInForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                taskScheduler.appInForeground = false
            }
        })
    }
}
