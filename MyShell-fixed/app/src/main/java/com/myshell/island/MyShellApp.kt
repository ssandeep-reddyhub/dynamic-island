package com.myshell.island

import android.app.Application
import android.content.Context
import com.myshell.island.di.AppContainer
import com.myshell.island.util.Channels
import kotlinx.coroutines.launch

class MyShellApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.create(this)
        container.scope.launch { container.settings.migrate() }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MyShellApp).container
