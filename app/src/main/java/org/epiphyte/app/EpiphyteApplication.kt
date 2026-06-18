package org.epiphyte.app

import android.app.Application
import org.epiphyte.app.controller.AppController
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class EpiphyteApplication : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        // Register Bouncy Castle as security provider
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        controller = AppController(this)
    }
}
