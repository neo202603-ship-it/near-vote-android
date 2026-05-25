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
    private val peerIds = mutableMapOf<String, String>()
    private val peerDisplayNames = mutableMapOf<String, String>()
    private var isAdvertising = false
    private var isDiscovering = false

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            endpointNames[endpointId] = connectionInfo.endpointName
            listener.onLog("${connectionInfo.endpointName} 연결 요청 수신")
            val isKnownPeer = connectedEndpoints.contains(endpointId) || pendingEndpoints.contains(endpointId)
            if (!isKnownPeer && connectionSlotsUsed() >= MAX_CONNECTIONS) {
                client.rejectConnection(endpointId)
                listener.onLog("연결 제한 ${MAX_CONNECTIONS}대 도달: ${connectionInfo.endpointName} 요청 거절")
                return
            }
            pendingEndpoints += endpointId
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { listener.onLog("연결 수락 실패: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints -= endpointId
            if (result.status.isSuccess) {
                if (!connectedEndpoints.contains(endpointId) && activeEndpoints().size >= MAX_CONNECTIONS) {
                    client.disconnectFromEndpoint(endpointId)
                    listener.onLog("연결 제한 ${MAX_CONNECTIONS}대 도달: ${endpointNames[endpointId] ?: endpointId} 연결 종료")
                    return
                }
                connectedEndpoints += endpointId
                listener.onEndpointConnected(endpointId)
                listener.onLog("연결 완료: ${endpointNames[endpointId] ?: endpointId}")
            } else {
                listener.onLog("연결 실패: ${endpointNames[endpointId] ?: endpointId} (${result.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints -= endpointId
            peerIds.remove(endpointId)
            peerDisplayNames.remove(endpointId)
            listener.onEndpointDisconnected(endpointId)
            notifyConnectionCount()
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
            if (connectionSlotsUsed() >= MAX_CONNECTIONS) {
                listener.onLog("연결 제한 ${MAX_CONNECTIONS}대 도달: 새 연결을 기다리지 않음")
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

    fun restartNearbyMode() {
        listener.onLog("주변 연결 초기화 후 다시 시작")
        stop()
        startNearbyMode()
    }

    fun maintainNearbyMode() {
        if (!isAdvertising) {
            startAdvertising()
        }
        if (!isDiscovering) {
            startDiscovery()
        }
        notifyConnectionCount()
    }

    fun connectedPeerNames(): List<String> {
        return verifiedEndpoints().map { endpointId ->
            peerDisplayNames[endpointId] ?: endpointNames[endpointId] ?: endpointId
        }
    }

    fun identifyPeer(endpointId: String, peerId: String, displayName: String) {
        peerIds[endpointId] = peerId
        peerDisplayNames[endpointId] = displayName
        removeDuplicateConnections(endpointId, peerId)
        notifyConnectionCount()
    }

    fun endpointForPeer(peerId: String): String? {
        return verifiedEndpoints().firstOrNull { endpointId -> peerIds[endpointId] == peerId }
    }

    fun sendToAll(message: String) {
        val endpoints = verifiedEndpoints()
        if (endpoints.isEmpty()) {
            listener.onLog("전송할 연결 기기가 없음")
            return
        }
        endpoints.forEach { endpointId ->
            val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
            client.sendPayload(endpointId, payload)
                .addOnFailureListener { listener.onLog("전송 실패: ${it.message}") }
        }
        listener.onLog("${endpoints.size}대에 메시지 전송")
    }

    fun sendTo(endpointId: String, message: String) {
        if (!connectedEndpoints.contains(endpointId)) {
            listener.onLog("대상 기기가 연결되어 있지 않음: ${endpointNames[endpointId] ?: endpointId}")
            return
        }
        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        client.sendPayload(endpointId, payload)
            .addOnSuccessListener { listener.onLog("${endpointNames[endpointId] ?: endpointId}에 메시지 전송") }
            .addOnFailureListener { listener.onLog("전송 실패: ${it.message}") }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        peerIds.clear()
        peerDisplayNames.clear()
        isAdvertising = false
        isDiscovering = false
        listener.onConnectionCountChanged(0)
    }

    private fun activeEndpoints(): List<String> {
        return connectedEndpoints.distinctBy { endpointId -> peerIds[endpointId] ?: endpointId }
    }

    private fun verifiedEndpoints(): List<String> {
        return connectedEndpoints
            .filter { endpointId -> peerIds.containsKey(endpointId) }
            .distinctBy { endpointId -> peerIds.getValue(endpointId) }
    }

    private fun connectionSlotsUsed(): Int {
        return activeEndpoints().size + pendingEndpoints.size
    }

    private fun removeDuplicateConnections(newEndpointId: String, peerId: String) {
        val newName = peerDisplayNames[newEndpointId] ?: endpointNames[newEndpointId] ?: peerId
        val duplicates = connectedEndpoints.filter { endpointId ->
            endpointId != newEndpointId && peerIds[endpointId] == peerId
        }
        duplicates.forEach { endpointId ->
            connectedEndpoints -= endpointId
            client.disconnectFromEndpoint(endpointId)
            listener.onLog("중복 연결 정리: $newName")
        }
    }

    private fun notifyConnectionCount() {
        listener.onConnectionCountChanged(verifiedEndpoints().size)
    }

    companion object {
        const val MAX_CONNECTIONS = 20
    }
}
