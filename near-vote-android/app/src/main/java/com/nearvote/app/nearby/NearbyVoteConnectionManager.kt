package com.nearvote.app.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets

class NearbyVoteConnectionManager(
    context: Context,
    private val selfName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onLog(message: String)
        fun onMessage(endpointId: String, message: String)
        fun onEndpointConnected(endpointId: String)
        fun onEndpointDisconnected(endpointId: String)
    }

    private val serviceId = "com.nearvote.app.NEAR_VOTE"
    private val strategy = Strategy.P2P_CLUSTER
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = linkedSetOf<String>()

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            listener.onLog("Connection requested by ${connectionInfo.endpointName}")
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints += endpointId
                listener.onEndpointConnected(endpointId)
                listener.onLog("Connected: $endpointId")
            } else {
                listener.onLog("Connection failed: $endpointId (${result.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints -= endpointId
            listener.onEndpointDisconnected(endpointId)
            listener.onLog("Disconnected: $endpointId")
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener.onLog("Found endpoint: ${info.endpointName}")
            client.requestConnection(selfName, endpointId, lifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            listener.onLog("Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener.onMessage(endpointId, String(bytes, StandardCharsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(selfName, serviceId, lifecycleCallback, options)
            .addOnSuccessListener { listener.onLog("Advertising started") }
            .addOnFailureListener { listener.onLog("Advertising failed: ${it.message}") }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener { listener.onLog("Discovery started") }
            .addOnFailureListener { listener.onLog("Discovery failed: ${it.message}") }
    }

    fun sendToAll(message: String) {
        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.forEach { endpointId ->
            client.sendPayload(endpointId, payload)
        }
        listener.onLog("Sent to ${connectedEndpoints.size} endpoints")
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
    }
}

