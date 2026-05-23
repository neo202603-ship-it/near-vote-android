package com.nearvote.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.nearvote.app.nearby.NearbyVoteConnectionManager
import com.nearvote.app.protocol.NearVoteMessage
import com.nearvote.app.simulation.LocalVoteSimulator

class MainActivity : ComponentActivity(), NearbyVoteConnectionManager.Listener {
    private lateinit var logView: TextView
    private lateinit var nearby: NearbyVoteConnectionManager
    private lateinit var simulator: LocalVoteSimulator
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
        simulator = LocalVoteSimulator(selfName)
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
        val simulationButton = Button(this).apply {
            text = "로컬 시뮬레이션 실행"
            setOnClickListener { runLocalSimulation() }
        }
        val clearButton = Button(this).apply {
            text = "로그 지우기"
            setOnClickListener {
                logView.text = "Near Vote Android PoC\n내 아이디: $selfName\n"
            }
        }
        logView = TextView(this).apply {
            text = "Near Vote Android PoC\n내 아이디: $selfName\n"
            textSize = 14f
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logView)
        }

        root.addView(advertiseButton)
        root.addView(discoverButton)
        root.addView(pingButton)
        root.addView(simulationButton)
        root.addView(clearButton)
        root.addView(scrollView)
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

    private fun runLocalSimulation() {
        simulator.runDemo().forEach { appendLog(it) }
    }

    private fun requestNearbyPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions)
        }
    }
}
