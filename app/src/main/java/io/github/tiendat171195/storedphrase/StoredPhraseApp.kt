package io.github.tiendat171195.storedphrase

import android.app.Application

class StoredPhraseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
