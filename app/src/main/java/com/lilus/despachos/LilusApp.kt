package com.lilus.despachos

import android.app.Application

class LilusApp : Application() {
    val repository: DispatchRepository by lazy { DispatchRepository(LilusDbHelper(this)) }
    val syncClient: SyncClient by lazy { SyncClient() }
    val settings: Settings by lazy { Settings(this) }
}
