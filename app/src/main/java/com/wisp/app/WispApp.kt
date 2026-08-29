package com.wisp.app

import android.app.Application

class WispApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Make sure the device has an identity before any screen or service
        // asks for it, so the code is stable from the very first launch.
        DeviceIdentity.myCode(this)
        Notifications.createChannels(this)
    }
}
