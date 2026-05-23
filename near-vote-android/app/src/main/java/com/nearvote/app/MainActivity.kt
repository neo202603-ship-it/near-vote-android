package com.nearvote.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.nearvote.app.nearby.NearbyVoteConnectionManager
import com.nearvote.app.protocol.NearVoteMessage

class MainActivity : ComponentActivity(), NearbyVoteConnectionManager.Listener {
    private lateinit var logView: TextView
    private lateinit var nearby: NearbyVoteConnectionManager
    private val selfName = "NearVote-${Build.MODEL}"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        appendLog(if (granted) "Permissions granted" else "Some permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = NearbyVoteConnectionManager(this, selfName, this)
        requestNearbyPermissions()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val advertiseButton = Button(this).apply {
            text = "광고 시작"
            setOnClickListener { nearby.startAdvertising() }
        }
        val discoverButton = Button(this).apply {
            text = "탐색 시작"
            setOnClickListener { nearby.startDiscovery() }
        }
        val pingButton = Button(this).apply {
            text = "PING 전송"
            setOnClickListener {
                nearby.sendToAll(NearVoteMessage.ping(selfName).toJson())
            }
        }
        logView = TextView(this).apply {
            text = "Near Vote Android PoC\n"
            textSize = 14f
        }

        root.addView(advertiseButton)
        root.addView(discoverButton)
        root.addView(pingButton)
        root.addView(logView)
        setContentView(root)
    }

    override fun onDestroy() {
        nearby.stop()
        super.onDestroy()
    }

    override fun onLog(message: String) = appendLog(message)

    override fun onMessage(endpointId: String, message: String) {
        appendLog("Message from $endpointId: $message")
    }

    override fun onEndpointConnected(endpointId: String) {
        appendLog("Connected endpoint: $endpointId")
    }

    override fun onEndpointDisconnected(endpointId: String) {
        appendLog("Disconnected endpoint: $endpointId")
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            logView.append("\n$message")
        }
    }

    private fun requestNearbyPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT in 29..31) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 32) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions)
        }
    }
}

