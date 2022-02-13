package com.crossbowffs.quotelock.app

import android.app.Application
import com.crossbowffs.quotelock.account.SyncAccountManager
import com.crossbowffs.quotelock.backup.RemoteBackup

/**
 * @author Yubyf
 * @date 2021/6/20.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncAccountManager.getInstance().initialize(this)
        if (RemoteBackup.instance.isGoogleAccountSignedIn(this)) {
            val accountName = RemoteBackup.instance.getSignedInGoogleAccountEmail(this)
            if (!accountName.isNullOrEmpty()) {
                SyncAccountManager.getInstance().addOrUpdateAccount(accountName)
            }
        }
    }
}