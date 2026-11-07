package dev.tasky.android

import android.app.Application

class TaskyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.observeProcessLifecycle()
    }
}
