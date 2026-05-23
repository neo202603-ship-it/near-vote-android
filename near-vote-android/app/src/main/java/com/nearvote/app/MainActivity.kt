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
import com.nearvote.app.protocol.NearVoteMessageType
import com.nearvote.app.simulation.LocalVoteSimulator
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity(), NearbyVoteConnectionManager.Listener {
    private lateinit var page: LinearLayout
    private lateinit var logView: TextView
    private lateinit var connectionStatusView: TextView
    private lateinit var nearby: NearbyVoteConnectionManager
    private lateinit var simulator: LocalVoteSimulator
    private val selfName = "NearVote-${Build.MODEL}"
    private var connectedCount = 0
    private var activePoll: NearbyPoll? = null
    private var incomingPoll: NearbyPoll? = null
    private val receivedVotes = linkedMapOf<String, String>()

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
        handleNearbyMessage(message)
    }

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        appendLog("발견: $endpointName ($endpointId)")
    }

    override fun onEndpointConnected(endpointId: String) {
        appendLog("연결됨: $endpointId")
    }

    override fun onEndpointDisconnected(endpointId: String) {
        appendLog("연결 해제: $endpointId")
    }

    override fun onConnectionCountChanged(count: Int) {
        connectedCount = count
        updateConnectionStatus()
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
        activePoll?.let { poll ->
            page.addView(outlineButton("게시한 투표 보기") { showPublishedPoll(poll) })
        }
        incomingPoll?.let { poll ->
            page.addView(outlineButton("받은 투표 참여하기") { showVotePoll(poll) })
        }
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
        page.addView(primaryButton("주변에 게시하기") { publishDemoPoll() })
        page.addView(outlineButton("게시 흐름 미리보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showDiscover() {
        setPage()
        page.addView(topBar("참여할 투표 찾기"))
        val poll = incomingPoll
        if (poll == null) {
            page.addView(emptyCard("아직 찾은 투표 없음", "두 기기에서 주변 연결 시작을 누른 뒤, 다른 기기에서 설문을 게시해보세요."))
        } else {
            page.addView(infoCard("받은 설문", poll.question, poll.options.joinToString(" / ")))
            page.addView(primaryButton("투표 참여하기") { showVotePoll(poll) })
        }
        page.addView(primaryButton("주변 연결 시작") { showDiagnostics(autoStart = true) })
        page.addView(outlineButton("테스트 투표 참여해보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showPublishedPoll(poll: NearbyPoll) {
        setPage()
        page.addView(topBar("게시한 투표"))
        page.addView(infoCard("설문", poll.question, poll.options.joinToString(" / ")))
        page.addView(statusCard("게시 완료", "연결된 기기 ${connectedCount}대에 참여 요청을 보냈습니다."))
        page.addView(primaryButton("참여 요청 다시 보내기") { sendPoll(poll) })
        page.addView(label("내 표도 참여할 수 있어요"))
        poll.options.forEach { option ->
            page.addView(choicePill(option) { castVote(poll, option) })
        }
        page.addView(label("현재 집계"))
        if (receivedVotes.isEmpty()) {
            page.addView(emptyCard("아직 투표 없음", "참여자가 선택하면 여기에 집계됩니다."))
        } else {
            poll.options.forEach { option ->
                val count = receivedVotes.values.count { it == option }
                val percent = count * 100 / receivedVotes.size
                page.addView(resultRow(option, count, percent))
            }
            page.addView(statusCard("참여자 ${receivedVotes.size}명", receivedVotes.keys.joinToString(", ")))
        }
        page.addView(outlineButton("주변 연결 다시 시작") { startNearbyConnectionTest() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showVotePoll(poll: NearbyPoll) {
        setPage()
        page.addView(topBar("투표 참여"))
        page.addView(infoCard("설문", poll.question, "제안자: ${poll.proposerId}"))
        page.addView(label("선택지"))
        poll.options.forEach { option ->
            page.addView(choicePill(option) { castVote(poll, option) })
        }
        page.addView(outlineButton("주변 투표로") { showDiscover() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showVoteSubmitted(poll: NearbyPoll, option: String) {
        setPage()
        page.addView(topBar("투표 완료"))
        page.addView(statusCard("내 표를 보냈습니다", "${poll.question} · $option"))
        page.addView(bodyText("제안자 기기에 투표 메시지가 전달되면 결과 집계에 반영됩니다."))
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

    private fun showDiagnostics(runSimulation: Boolean = false, autoStart: Boolean = false) {
        setPage()
        page.addView(topBar("개발자 진단"))
        connectionStatusView = statusCard("연결된 기기 ${connectedCount}대", "두 기기에서 모두 아래의 주변 연결 시작을 누르세요.")
        page.addView(connectionStatusView)
        page.addView(primaryButton("주변 연결 시작") { startNearbyConnectionTest() })
        page.addView(outlineButton("테스트 메시지 보내기") {
            nearby.sendToAll(NearVoteMessage.ping(selfName).toJson())
        })
        page.addView(outlineButton("광고만 시작") { nearby.startAdvertising() })
        page.addView(outlineButton("탐색만 시작") { nearby.startDiscovery() })
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
        if (autoStart) {
            startNearbyConnectionTest()
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

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF526158.toInt())
            setPadding(0, dp(4), 0, dp(12))
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

    private fun choicePill(text: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xFFEAF4EF.toInt(), 24)
            if (onClick != null) {
                setOnClickListener { onClick() }
            }
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

    private fun startNearbyConnectionTest() {
        appendLog("양쪽 기기에서 이 버튼을 누르면 서로를 찾고 연결을 시도합니다.")
        nearby.startNearbyMode()
        updateConnectionStatus()
    }

    private fun updateConnectionStatus() {
        runOnUiThread {
            if (::connectionStatusView.isInitialized) {
                connectionStatusView.text = if (connectedCount == 0) {
                    "연결된 기기 0대\n두 기기에서 모두 주변 연결 시작을 누른 뒤 잠시 기다리세요."
                } else {
                    "연결된 기기 ${connectedCount}대\n이제 테스트 메시지 보내기로 수신 로그를 확인할 수 있습니다."
                }
            }
        }
    }

    private fun runLocalSimulation() {
        simulator.runDemo().forEach { appendLog(it) }
    }

    private fun publishDemoPoll() {
        val poll = NearbyPoll(
            id = "poll-${System.currentTimeMillis()}",
            proposerId = selfName,
            question = "점심메뉴는?",
            options = listOf("한식", "분식", "샐러드")
        )
        activePoll = poll
        receivedVotes.clear()
        startNearbyConnectionTest()
        sendPoll(poll)
        showPublishedPoll(poll)
    }

    private fun sendPoll(poll: NearbyPoll) {
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.POLL,
                senderId = selfName,
                payloadJson = poll.toPayloadJson()
            ).toJson()
        )
    }

    private fun castVote(poll: NearbyPoll, option: String) {
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.VOTE,
                senderId = selfName,
                payloadJson = JSONObject()
                    .put("pollId", poll.id)
                    .put("option", option)
                    .put("voterId", selfName)
                    .toString()
            ).toJson()
        )
        if (poll.id == activePoll?.id) {
            receivedVotes[selfName] = option
            showPublishedPoll(poll)
        } else {
            showVoteSubmitted(poll, option)
        }
    }

    private fun handleNearbyMessage(rawMessage: String) {
        val message = runCatching { NearVoteMessage.fromJson(rawMessage) }.getOrElse {
            appendLog("알 수 없는 메시지 형식")
            return
        }
        when (message.type) {
            NearVoteMessageType.POLL -> {
                val poll = runCatching { NearbyPoll.fromPayload(message.senderId, message.payloadJson) }.getOrElse {
                    appendLog("설문 메시지를 읽지 못함")
                    return
                }
                if (poll.proposerId == selfName) return
                incomingPoll = poll
                runOnUiThread { showVotePoll(poll) }
            }
            NearVoteMessageType.VOTE -> {
                val payload = JSONObject(message.payloadJson)
                val poll = activePoll ?: return
                if (payload.optString("pollId") != poll.id) return
                val voterId = payload.optString("voterId", message.senderId)
                val option = payload.optString("option")
                if (option.isBlank()) return
                receivedVotes[voterId] = option
                runOnUiThread { showPublishedPoll(poll) }
            }
            else -> Unit
        }
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

    private data class NearbyPoll(
        val id: String,
        val proposerId: String,
        val question: String,
        val options: List<String>
    ) {
        fun toPayloadJson(): String {
            return JSONObject()
                .put("pollId", id)
                .put("question", question)
                .put("options", JSONArray(options))
                .toString()
        }

        companion object {
            fun fromPayload(proposerId: String, payloadJson: String): NearbyPoll {
                val payload = JSONObject(payloadJson)
                val optionsArray = payload.getJSONArray("options")
                val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
                return NearbyPoll(
                    id = payload.getString("pollId"),
                    proposerId = proposerId,
                    question = payload.getString("question"),
                    options = options
                )
            }
        }
    }
}
