package com.nearvote.app

import android.Manifest
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private lateinit var root: LinearLayout
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
        showHome()
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

    private fun showHome() {
        setRoot()
        root.addView(title("Near Vote"))
        root.addView(body("서버 없이 가까운 사람끼리 설문을 만들고, 투표 영수증과 결과 원장을 나눠 갖는 앱입니다."))
        root.addView(section("내 아이디"))
        root.addView(valueBox(selfName))
        root.addView(primaryButton("새 설문 만들기") { showComposePreview() })
        root.addView(primaryButton("주변 투표 찾기") { showDiscoverPreview() })
        root.addView(secondaryButton("로컬 시뮬레이션 보기") { showSimulationResult() })
        root.addView(secondaryButton("개발자 진단") { showDiagnostics() })
    }

    private fun showComposePreview() {
        setRoot()
        root.addView(topBar("설문 작성"))
        root.addView(section("템플릿"))
        root.addView(valueBox("점심메뉴는?\n한식 / 분식 / 샐러드"))
        root.addView(section("제한시간"))
        root.addView(valueBox("5분"))
        root.addView(primaryButton("로컬 시뮬레이션으로 게시") { showSimulationResult() })
        root.addView(secondaryButton("홈으로") { showHome() })
    }

    private fun showDiscoverPreview() {
        setRoot()
        root.addView(topBar("주변 투표"))
        root.addView(body("현재 기기가 1대라 실제 주변 투표 목록은 아직 확인할 수 없습니다. 두 번째 Android 기기에서는 이 화면에서 Nearby 탐색 결과를 표시합니다."))
        root.addView(primaryButton("탐색 시작") {
            nearby.startDiscovery()
            showDiagnostics()
        })
        root.addView(secondaryButton("로컬 시뮬레이션 보기") { showSimulationResult() })
        root.addView(secondaryButton("홈으로") { showHome() })
    }

    private fun showSimulationResult() {
        val preview = simulator.preview()
        setRoot()
        root.addView(topBar("시뮬레이션 결과"))
        root.addView(section(preview.question))
        root.addView(body("선택지: ${preview.options.joinToString(" / ")}"))
        root.addView(section("참여자 ${preview.participantIds.size}명"))
        root.addView(body(preview.participantIds.joinToString(", ")))
        root.addView(section("결과"))
        preview.resultLines.forEach { root.addView(valueBox(it)) }
        root.addView(body("영수증 ${preview.receiptCount}건, 결과 해시 ${preview.resultHash}"))
        root.addView(primaryButton("다시 실행") { showSimulationResult() })
        root.addView(secondaryButton("상세 로그 보기") { showDiagnostics(runSimulation = true) })
        root.addView(secondaryButton("홈으로") { showHome() })
    }

    private fun showDiagnostics(runSimulation: Boolean = false) {
        setRoot()
        root.addView(topBar("개발자 진단"))
        root.addView(body("실제 Nearby 연결과 원장 메시지 payload를 확인하는 개발용 화면입니다."))
        root.addView(primaryButton("광고 시작") { nearby.startAdvertising() })
        root.addView(primaryButton("탐색 시작") { nearby.startDiscovery() })
        root.addView(secondaryButton("PING 전송") {
            nearby.sendToAll(NearVoteMessage.ping(selfName).toJson())
        })
        root.addView(secondaryButton("로컬 시뮬레이션 실행") { runLocalSimulation() })
        root.addView(secondaryButton("로그 지우기") {
            logView.text = "Near Vote Android PoC\n내 아이디: $selfName\n"
        })
        logView = TextView(this).apply {
            text = "Near Vote Android PoC\n내 아이디: $selfName\n"
            textSize = 14f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logView)
        })
        if (runSimulation) {
            runLocalSimulation()
        }
    }

    private fun setRoot() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        setContentView(root)
    }

    private fun topBar(text: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("‹") { showHome() })
            addView(title(text).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
    }

    private fun body(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 4, 0, 12)
        }
    }

    private fun valueBox(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 18, 24, 18)
            setBackgroundColor(0xFFEAF4EF.toInt())
        }
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button {
        return primaryButton(text, onClick)
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            if (::logView.isInitialized) {
                logView.append("\n$message")
            }
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
