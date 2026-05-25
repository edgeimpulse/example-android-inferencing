package com.edgeimpulse.gattsensors

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Republishes incoming Wear OS messages onto [WearEventBus] so the live
 * [DataRepository] held by the active ViewModel can consume them. The
 * system may instantiate this service in its own short-lived process, so
 * we never touch ViewModel state directly here.
 */
class WearableMessageListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        WearEventBus.publish(messageEvent)
    }
}
