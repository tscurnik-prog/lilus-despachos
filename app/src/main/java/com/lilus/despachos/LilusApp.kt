package com.lilus.despachos

import android.app.Application

class LilusApp : Application() {
    val repository: DispatchRepository by lazy { DispatchRepository(LilusDbHelper(this)) }
    val driveClient: DriveClient by lazy { DriveClient() }
    val settings: Settings by lazy { Settings(this) }
}
