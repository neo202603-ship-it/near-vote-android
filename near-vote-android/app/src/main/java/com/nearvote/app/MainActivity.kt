package com.nearvote.app

import android.Manifest
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
    private lateinit var page: LinearLayout
    private lateinit var logView: TextView
    private lateinit var nearby: NearbyVoteConnectionManager
    private lateinit var simulator: LocalVoteSimulator
    private val selfName = "NearVote-${Build.MODEL}"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        appendLog(if (granted) "권한 준비 완료" else "일부 권한이 꺼져 있음")
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
        appendLog("$endpointId 에서 메시지 수신: $message")
    }

    override fun onEndpointConnected(endpointId: String) {
        appendLog("연결됨: $endpointId")
    }

    override fun onEndpointDisconnected(endpointId: String) {
        appendLog("연결 해제: $endpointId")
    }

    private fun showHome() {
        setPage()
        page.addView(header("근거리 투표", "가까이 있는 사람들과 바로 설문을 열고 결과를 나눠 갖습니다."))
        page.addView(infoCard("내 아이디", selfName, "결과와 참여자 목록에 표시됩니다."))
        page.addView(actionCard("설문 만들기", "질문과 선택지를 정하고 주변 사람에게 참여 요청을 보냅니다.") {
            showCompose()
        })
        page.addView(actionCard("참여할 투표 찾기", "근처에서 진행 중인 투표를 찾습니다.") {
            showDiscover()
        })
        page.addView(statusCard("지금은 한 기기 테스트 중", "실제 연결 전까지는 시뮬레이션으로 설문 흐름을 확인합니다."))
        page.addView(outlineButton("예상 결과 미리보기") { showSimulationResult() })
        page.addView(quietButton("개발자 진단") { showDiagnostics() })
    }

    private fun showCompose() {
        setPage()
        page.addView(topBar("설문 만들기"))
        page.addView(infoCard("질문", "점심메뉴는?", "웹 프로토타입의 기본 템플릿을 Android 화면으로 가져온 상태입니다."))
        page.addView(label("선택지"))
        listOf("한식", "분식", "샐러드").forEach { option ->
            page.addView(choicePill(option))
        }
        page.addView(infoCard("제한시간", "5분", "게시하면 가까운 참여자에게 투표 요청을 보냅니다."))
        page.addView(primaryButton("게시 흐름 미리보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showDiscover() {
        setPage()
        page.addView(topBar("참여할 투표 찾기"))
        page.addView(emptyCard("아직 찾은 투표 없음", "두 번째 Android 기기가 생기면 이곳에 주변 투표가 표시됩니다."))
        page.addView(primaryButton("주변에서 찾기") {
            nearby.startDiscovery()
            showDiagnostics()
        })
        page.addView(outlineButton("테스트 투표 참여해보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showSimulationResult() {
        val preview = simulator.preview()
        setPage()
        page.addView(topBar("투표 결과"))
        page.addView(infoCard("설문", preview.question, preview.options.joinToString(" / ")))
        page.addView(infoCard("참여자", "${preview.participantIds.size}명", preview.participantIds.joinToString(", ")))
        page.addView(label("결과"))
        preview.resultLines.forEach { result ->
            page.addView(resultRow(result.option, result.count, result.percent))
        }
        page.addView(statusCard("검증 완료", "영수증 ${preview.receiptCount}건 · 결과 해시 ${preview.resultHash}"))
        page.addView(primaryButton("다시 미리보기") { showSimulationResult() })
        page.addView(outlineButton("상세 로그 보기") { showDiagnostics(runSimulation = true) })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showDiagnostics(runSimulation: Boolean = false) {
        setPage()
        page.addView(topBar("개발자 진단"))
        page.addView(statusCard("Nearby 연결 확인", "실제 기기 2대 테스트 때 사용하는 광고, 탐색, 메시지 로그입니다."))
        page.addView(primaryButton("광고 시작") { nearby.startAdvertising() })
        page.addView(primaryButton("탐색 시작") { nearby.startDiscovery() })
        page.addView(outlineButton("PING 전송") {
            nearby.sendToAll(NearVoteMessage.ping(selfName).toJson())
        })
        page.addView(outlineButton("로컬 시뮬레이션 로그 실행") { runLocalSimulation() })
        page.addView(quietButton("로그 지우기") {
            logView.text = "Near Vote Android PoC\n내 아이디: $selfName\n"
        })
        logView = TextView(this).apply {
            text = "Near Vote Android PoC\n내 아이디: $selfName\n"
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFFF7F8F5.toInt(), 12)
        }
        page.addView(logView.apply { layoutParams = blockParams() })
        if (runSimulation) {
            runLocalSimulation()
        }
    }

    private fun setPage() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF3F7F2.toInt())
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun header(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
            addView(TextView(context).apply {
                text = title
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 16f
                setTextColor(0xFF526158.toInt())
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun topBar(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(Button(context).apply {
                text = "‹"
                textSize = 22f
                setOnClickListener { showHome() }
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(48))
            })
            addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
            })
        }
    }

    private fun actionCard(title: String, subtitle: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF123126.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
    }

    private fun infoCard(title: String, value: String, caption: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            layoutParams = blockParams()
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF647268.toInt())
            })
            addView(TextView(context).apply {
                text = value
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(context).apply {
                text = caption
                textSize = 14f
                setTextColor(0xFF526158.toInt())
            })
        }
    }

    private fun statusCard(title: String, subtitle: String): TextView {
        return TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 15f
            setTextColor(0xFF294237.toInt())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFFE3F1EA.toInt(), 14)
            layoutParams = blockParams()
        }
    }

    private fun emptyCard(title: String, subtitle: String): TextView {
        return TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(42), dp(24), dp(42))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            layoutParams = blockParams()
        }
    }

    private fun resultRow(option: String, count: Int, percent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            layoutParams = blockParams()
            addView(TextView(context).apply {
                text = "$option  ${count}명 · $percent%"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(context).apply {
                background = rounded(0xFF7DB79D.toInt(), 8)
                layoutParams = LinearLayout.LayoutParams(
                    dp(8 + percent * 2),
                    dp(8)
                ).apply {
                    topMargin = dp(10)
                }
            })
        }
    }

    private fun choicePill(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xFFEAF4EF.toInt(), 24)
            layoutParams = blockParams()
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        }
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF176B4D.toInt(), 12)
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
    }

    private fun outlineButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(0xFF176B4D.toInt())
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFB8D8C8.toInt())
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
    }

    private fun quietButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(0xFF526158.toInt())
            background = rounded(0xFFE9EEE9.toInt(), 12)
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
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

    private fun blockParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
