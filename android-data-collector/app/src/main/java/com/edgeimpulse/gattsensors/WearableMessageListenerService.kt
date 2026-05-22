package com.edgeimpulse.gattsensors

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableMessageListenerService : WearableListenerService() {

    private lateinit var dataRepository: DataRepository

    override fun onCreate() {
        super.onCreate()
        dataRepository = DataRepository(applicationContext)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        dataRepository.onMessageReceived(messageEvent)
    }
}
