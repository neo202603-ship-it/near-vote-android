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
        fun onEndpointFound(endpointId: String, endpointName: String)
        fun onEndpointConnected(endpointId: String)
        fun onEndpointDisconnected(endpointId: String)
        fun onConnectionCountChanged(count: Int)
    }

    private val serviceId = "com.nearvote.app.NEAR_VOTE"
    private val strategy = Strategy.P2P_CLUSTER
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = linkedSetOf<String>()
    private val pendingEndpoints = linkedSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private var isAdvertising = false
    private var isDiscovering = false

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            endpointNames[endpointId] = connectionInfo.endpointName
            listener.onLog("${connectionInfo.endpointName} 연결 요청 수신")
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { listener.onLog("연결 수락 실패: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints -= endpointId
            if (result.status.isSuccess) {
                connectedEndpoints += endpointId
                listener.onEndpointConnected(endpointId)
                listener.onLog("연결 완료: ${endpointNames[endpointId] ?: endpointId}")
                listener.onConnectionCountChanged(connectedEndpoints.size)
            } else {
                listener.onLog("연결 실패: ${endpointNames[endpointId] ?: endpointId} (${result.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints -= endpointId
            listener.onEndpointDisconnected(endpointId)
            listener.onConnectionCountChanged(connectedEndpoints.size)
            listener.onLog("연결 해제: ${endpointNames[endpointId] ?: endpointId}")
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            listener.onEndpointFound(endpointId, info.endpointName)
            listener.onLog("주변 기기 발견: ${info.endpointName}")
            if (connectedEndpoints.contains(endpointId) || pendingEndpoints.contains(endpointId)) {
                return
            }
            pendingEndpoints += endpointId
            client.requestConnection(selfName, endpointId, lifecycleCallback)
                .addOnFailureListener {
                    pendingEndpoints -= endpointId
                    listener.onLog("연결 요청 실패: ${it.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            listener.onLog("주변 기기 사라짐: ${endpointNames[endpointId] ?: endpointId}")
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
        if (isAdvertising) {
            listener.onLog("광고는 이미 켜져 있음")
            return
        }
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(selfName, serviceId, lifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertising = true
                listener.onLog("광고 시작: 다른 기기가 나를 찾을 수 있음")
            }
            .addOnFailureListener { listener.onLog("광고 시작 실패: ${it.message}") }
    }

    fun startDiscovery() {
        if (isDiscovering) {
            listener.onLog("탐색은 이미 켜져 있음")
            return
        }
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener {
                isDiscovering = true
                listener.onLog("탐색 시작: 주변 기기를 찾는 중")
            }
            .addOnFailureListener { listener.onLog("탐색 시작 실패: ${it.message}") }
    }

    fun startNearbyMode() {
        listener.onLog("주변 연결 시작")
        startAdvertising()
        startDiscovery()
    }

    fun maintainNearbyMode() {
        if (!isAdvertising) {
            startAdvertising()
        }
        if (!isDiscovering) {
            startDiscovery()
        }
        listener.onConnectionCountChanged(connectedEndpoints.size)
    }

    fun connectedPeerNames(): List<String> {
        return connectedEndpoints.map { endpointId -> endpointNames[endpointId] ?: endpointId }
    }

    fun sendToAll(message: String) {
        if (connectedEndpoints.isEmpty()) {
            listener.onLog("전송할 연결 기기가 없음")
            return
        }
        connectedEndpoints.forEach { endpointId ->
            val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
            client.sendPayload(endpointId, payload)
                .addOnFailureListener { listener.onLog("전송 실패: ${it.message}") }
        }
        listener.onLog("${connectedEndpoints.size}대에 메시지 전송")
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        isAdvertising = false
        isDiscovering = false
        listener.onConnectionCountChanged(0)
    }
}
