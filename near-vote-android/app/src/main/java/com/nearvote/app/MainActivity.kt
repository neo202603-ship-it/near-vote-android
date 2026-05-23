package com.nearvote.app

import android.Manifest
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.nearvote.app.data.NearVoteStore
import com.nearvote.app.data.NearbyPoll
import com.nearvote.app.data.PollTemplate
import com.nearvote.app.data.SharedResult
import com.nearvote.app.data.VoteReceipt
import com.nearvote.app.nearby.NearbyVoteConnectionManager
import com.nearvote.app.protocol.NearVoteMessage
import com.nearvote.app.protocol.NearVoteMessageType
import com.nearvote.app.simulation.LocalVoteSimulator
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity(), NearbyVoteConnectionManager.Listener {
    private lateinit var page: LinearLayout
    private lateinit var logView: TextView
    private lateinit var connectionStatusView: TextView
    private lateinit var nearby: NearbyVoteConnectionManager
    private lateinit var simulator: LocalVoteSimulator
    private lateinit var store: NearVoteStore
    private val handler = Handler(Looper.getMainLooper())
    private val nearbyHeartbeat = object : Runnable {
        override fun run() {
            nearby.maintainNearbyMode()
            updateConnectionStatus()
            handler.postDelayed(this, NEARBY_HEARTBEAT_MS)
        }
    }
    private var selfName = ""
    private var userId = ""
    private var autoConnectEnabled = true
    private var connectedCount = 0
    private var activePoll: NearbyPoll? = null
    private var incomingPoll: NearbyPoll? = null
    private var latestReceipt: VoteReceipt? = null
    private var sharedResult: SharedResult? = null
    private val receivedVotes = linkedMapOf<String, String>()
    private val receivedVoteNames = linkedMapOf<String, String>()
    private val submittedVotes = linkedMapOf<String, String>()
    private val sharedResultPollIds = linkedSetOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        appendLog(if (granted) "권한 준비 완료" else "일부 권한이 꺼져 있음")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NearVoteStore(this)
        selfName = store.loadIdentity { suggestIdentity() }
        userId = store.loadUserId { UUID.randomUUID().toString() }
        autoConnectEnabled = store.isAutoConnectEnabled()
        nearby = NearbyVoteConnectionManager(this, selfName, this)
        simulator = LocalVoteSimulator(selfName)
        requestNearbyPermissions()
        showHome()
        applyAutoConnectSetting()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        connectionStatusView = statusCard(if (autoConnectEnabled) "자동 연결 중" else "자동 연결 꺼짐", connectionStatusText())
        page.addView(connectionStatusView)
        page.addView(outlineButton("내 아이디 관리") { showMyPage() })
        page.addView(outlineButton("설정") { showSettings() })
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
        sharedResult?.let {
            page.addView(outlineButton("공유받은 결과 보기") { showSharedResult(it) })
        }
        page.addView(outlineButton("지난 결과") { showHistory() })
        page.addView(quietButton("개발자 진단") { showDiagnostics() })
    }

    private fun showHistory() {
        val results = store.loadResultHistory()
        setPage()
        page.addView(topBar("지난 결과"))
        if (results.isEmpty()) {
            page.addView(emptyCard("저장된 결과 없음", "결과를 공유받거나 직접 공유하면 여기에 남습니다."))
        } else {
            results.forEach { result ->
                page.addView(actionCard(result.question, "참여자 ${result.participantCount}명 · ${result.resultHash.take(16)}") {
                    showSharedResult(result)
                })
            }
        }
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showMyPage() {
        setPage()
        page.addView(topBar("내 아이디"))
        page.addView(bodyText("아이디는 결과와 참여자 목록에 표시됩니다. 따로 만들지 않아도 제안 아이디를 바로 사용할 수 있습니다."))
        val identityInput = inputBox("내 아이디", selfName)
        page.addView(label("현재 아이디"))
        page.addView(identityInput)
        page.addView(outlineButton("새 아이디 제안") {
            identityInput.setText(suggestIdentity())
        })
        page.addView(primaryButton("저장하기") {
            val nextIdentity = identityInput.text.toString().trim()
            if (nextIdentity.length < 2) {
                Toast.makeText(this, "아이디는 2글자 이상 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            saveIdentity(nextIdentity)
            Toast.makeText(this, "아이디 저장 완료", Toast.LENGTH_SHORT).show()
            showHome()
        })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showSettings() {
        setPage()
        page.addView(topBar("설정"))
        page.addView(statusCard(
            "자동 연결",
            if (autoConnectEnabled) {
                "켜짐 · 앱 실행 후 주변 기기와 자동으로 연결을 유지합니다."
            } else {
                "꺼짐 · 필요할 때 개발자 진단에서 수동으로 연결할 수 있습니다."
            }
        ))
        page.addView(primaryButton(if (autoConnectEnabled) "자동 연결 끄기" else "자동 연결 켜기") {
            setAutoConnectEnabled(!autoConnectEnabled)
            showSettings()
        })
        page.addView(statusCard("내부 사용자 ID", "보이지 않는 고유 ID가 투표 중복 방지와 영수증 검증에 사용됩니다."))
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showCompose(template: PollTemplate? = null) {
        setPage()
        page.addView(topBar("설문 만들기"))
        page.addView(bodyText("질문과 선택지를 입력하고 주변 사람에게 바로 게시합니다."))

        val selectedTemplate = template ?: store.loadTemplates().first()
        val questionInput = inputBox("질문", selectedTemplate.question)
        val optionsInput = inputBox("선택지", selectedTemplate.options.joinToString("\n"), multiLine = true)
        val durationInput = inputBox("제한시간(분)", selectedTemplate.durationMinutes.toString(), numberOnly = true)

        page.addView(label("템플릿"))
        page.addView(outlineButton("템플릿 선택") {
            showTemplatePicker(questionInput.text.toString(), optionsInput.text.toString(), durationInput.text.toString())
        })

        page.addView(label("질문"))
        page.addView(questionInput)
        page.addView(label("선택지"))
        page.addView(optionsInput)
        page.addView(label("제한시간"))
        page.addView(durationInput)

        page.addView(primaryButton("주변에 게시하기") {
            val question = questionInput.text.toString().trim()
            val options = optionsInput.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val durationMinutes = durationInput.text.toString().toIntOrNull() ?: 5
            if (question.isBlank()) {
                Toast.makeText(this, "질문을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            if (options.size < 2) {
                Toast.makeText(this, "선택지는 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            publishPoll(question, options, durationMinutes.coerceIn(1, 60))
        })
        page.addView(outlineButton("템플릿으로 저장") {
            val template = buildTemplateFromInputs(questionInput, optionsInput, durationInput) ?: return@outlineButton
            store.saveTemplate(template)
            Toast.makeText(this, "템플릿 저장 완료", Toast.LENGTH_SHORT).show()
        })
        page.addView(outlineButton("게시 흐름 미리보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showTemplatePicker(
        currentQuestion: String = "점심메뉴는?",
        currentOptions: String = "한식\n분식\n샐러드",
        currentDuration: String = "5"
    ) {
        setPage()
        page.addView(topBar("템플릿 선택"))
        page.addView(bodyText("템플릿을 선택하면 설문 작성 화면에 질문과 선택지가 채워집니다."))
        store.loadTemplates().forEach { template ->
            val source = if (template.builtIn) "기본 템플릿" else "내 템플릿"
            page.addView(actionCard("$source > ${template.title}", template.options.joinToString(" / ")) {
                showCompose(template)
            })
            if (!template.builtIn) {
                page.addView(quietButton("삭제: ${template.title}") {
                    store.deleteTemplate(template.id)
                    Toast.makeText(this, "템플릿 삭제 완료", Toast.LENGTH_SHORT).show()
                    showTemplatePicker(currentQuestion, currentOptions, currentDuration)
                })
            }
        }
        page.addView(outlineButton("작성 화면으로") {
            showCompose(
                PollTemplate(
                    id = "draft",
                    title = currentQuestion.ifBlank { "새 설문" },
                    question = currentQuestion.ifBlank { "점심메뉴는?" },
                    options = currentOptions.lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("한식", "분식", "샐러드") },
                    durationMinutes = currentDuration.toIntOrNull() ?: 5
                )
            )
        })
    }

    private fun buildTemplateFromInputs(
        questionInput: EditText,
        optionsInput: EditText,
        durationInput: EditText
    ): PollTemplate? {
        val question = questionInput.text.toString().trim()
        val options = optionsInput.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (question.isBlank()) {
            Toast.makeText(this, "질문을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return null
        }
        if (options.size < 2) {
            Toast.makeText(this, "선택지는 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
            return null
        }
        return PollTemplate(
            id = "template-${System.currentTimeMillis()}",
            title = question,
            question = question,
            options = options,
            durationMinutes = (durationInput.text.toString().toIntOrNull() ?: 5).coerceIn(1, 60)
        )
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
        val ended = poll.hasEnded()
        setPage()
        page.addView(topBar("게시한 투표"))
        page.addView(infoCard("설문", poll.question, poll.options.joinToString(" / ")))
        page.addView(statusCard(if (ended) "투표 종료" else "투표 진행 중", poll.statusText(connectedCount)))
        if (!ended) {
            page.addView(primaryButton("참여 요청 다시 보내기") { sendPoll(poll) })
            page.addView(label("내 표도 참여할 수 있어요"))
            if (receivedVotes.containsKey(userId)) {
                page.addView(statusCard("이미 참여 완료", receivedVotes[userId].orEmpty()))
            } else {
                poll.options.forEach { option ->
                    page.addView(choicePill(option) { castVote(poll, option) })
                }
            }
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
            page.addView(statusCard("참여자 ${receivedVotes.size}명", receivedVoteNames.values.joinToString(", ")))
        }
        if (receivedVotes.isNotEmpty() && !sharedResultPollIds.contains(poll.id)) {
            page.addView(primaryButton(if (ended) "결과 공유하기" else "지금 결과 공유하기") { shareResultBlock(poll) })
        }
        page.addView(outlineButton("주변 연결 다시 시작") { startNearbyConnectionTest() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showVotePoll(poll: NearbyPoll) {
        setPage()
        page.addView(topBar("투표 참여"))
        page.addView(infoCard("설문", poll.question, "제안자: ${poll.proposerName} · ${poll.remainingText()}"))
        val submitted = submittedVotes[poll.id]
        when {
            sharedResult?.pollId == poll.id -> {
                page.addView(primaryButton("결과 보기") { showSharedResult(sharedResult!!) })
            }
            submitted != null -> {
                page.addView(statusCard("이미 참여 완료", submitted))
                page.addView(bodyText("한 투표에는 한 번만 참여할 수 있습니다."))
            }
            poll.hasEnded() -> {
                page.addView(statusCard("투표 종료", "제한시간이 지나 더 이상 참여할 수 없습니다."))
            }
            else -> {
                page.addView(label("선택지"))
                poll.options.forEach { option ->
                    page.addView(choicePill(option) { castVote(poll, option) })
                }
            }
        }
        page.addView(outlineButton("주변 투표로") { showDiscover() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showVoteSubmitted(poll: NearbyPoll, option: String) {
        setPage()
        page.addView(topBar("투표 완료"))
        page.addView(statusCard("내 표를 보냈습니다", "${poll.question} · $option"))
        val receipt = latestReceipt
        if (receipt == null || receipt.pollId != poll.id) {
            page.addView(bodyText("제안자 기기에 투표 메시지가 전달되면 영수증이 도착합니다."))
        } else {
            page.addView(statusCard("영수증 수신 완료", "내 표 해시 ${receipt.voteHash.take(16)}"))
        }
        sharedResult?.takeIf { it.pollId == poll.id }?.let {
            page.addView(primaryButton("결과 보기") { showSharedResult(it) })
        }
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showSharedResult(result: SharedResult) {
        setPage()
        page.addView(topBar("공유받은 결과"))
        page.addView(infoCard("설문", result.question, "제안자: ${result.proposerName}"))
        page.addView(label("결과"))
        val total = result.counts.values.sum().coerceAtLeast(1)
        result.options.forEach { option ->
            val count = result.counts[option] ?: 0
            page.addView(resultRow(option, count, count * 100 / total))
        }
        page.addView(statusCard(
            if (result.isHashValid()) "검증 완료" else "검증 필요",
            "참여자 ${result.participantCount}명 · 결과 해시 ${result.resultHash.take(16)}"
        ))
        if (result.participantNames.isNotEmpty()) {
            page.addView(statusCard("참여자", result.participantNames.joinToString(", ")))
        }
        (latestReceipt?.takeIf { it.pollId == result.pollId } ?: store.loadReceipt(result.pollId))?.let {
            page.addView(statusCard("내 투표 영수증", it.voteHash.take(16)))
        }
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

    private fun inputBox(
        label: String,
        defaultValue: String,
        multiLine: Boolean = false,
        numberOnly: Boolean = false
    ): EditText {
        return EditText(this).apply {
            hint = label
            setText(defaultValue)
            textSize = 16f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFB8D8C8.toInt())
            inputType = when {
                numberOnly -> InputType.TYPE_CLASS_NUMBER
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiLine) {
                minLines = 3
                gravity = Gravity.TOP
            }
            layoutParams = blockParams()
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

    private fun startNearbyHeartbeat() {
        handler.removeCallbacks(nearbyHeartbeat)
        if (!autoConnectEnabled) return
        nearby.maintainNearbyMode()
        handler.postDelayed(nearbyHeartbeat, NEARBY_HEARTBEAT_MS)
    }

    private fun applyAutoConnectSetting() {
        if (autoConnectEnabled) {
            startNearbyHeartbeat()
        } else {
            handler.removeCallbacks(nearbyHeartbeat)
            nearby.stop()
            connectedCount = 0
            updateConnectionStatus()
        }
    }

    private fun setAutoConnectEnabled(enabled: Boolean) {
        autoConnectEnabled = enabled
        store.saveAutoConnectEnabled(enabled)
        applyAutoConnectSetting()
        Toast.makeText(this, if (enabled) "자동 연결 켜짐" else "자동 연결 꺼짐", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus() {
        runOnUiThread {
            if (::connectionStatusView.isInitialized) {
                connectionStatusView.text = "${if (autoConnectEnabled) "자동 연결 중" else "자동 연결 꺼짐"}\n${connectionStatusText()}"
            }
        }
    }

    private fun connectionStatusText(): String {
        if (!autoConnectEnabled) {
            return "설정에서 자동 연결을 켜면 앱 실행 후 주변 기기와 자동으로 연결합니다."
        }
        return if (connectedCount == 0) {
            "연결된 기기 0대 · 약 ${NEARBY_HEARTBEAT_MS / 1000}초마다 주변 연결 상태를 확인합니다."
        } else {
            "연결된 기기 ${connectedCount}대 · 설문 게시와 투표 참여가 가능합니다."
        }
    }

    private fun runLocalSimulation() {
        simulator.runDemo().forEach { appendLog(it) }
    }

    private fun saveIdentity(nextIdentity: String) {
        store.saveIdentity(nextIdentity)
        selfName = nextIdentity
        resetSessionForIdentity()
    }

    private fun resetSessionForIdentity() {
        handler.removeCallbacksAndMessages(null)
        nearby.stop()
        nearby = NearbyVoteConnectionManager(this, selfName, this)
        simulator = LocalVoteSimulator(selfName)
        connectedCount = 0
        activePoll = null
        incomingPoll = null
        latestReceipt = null
        sharedResult = null
        receivedVotes.clear()
        receivedVoteNames.clear()
        submittedVotes.clear()
        sharedResultPollIds.clear()
        applyAutoConnectSetting()
    }

    private fun suggestIdentity(): String {
        val adjectives = listOf("따뜻한", "빠른", "조용한", "선명한", "든든한", "가벼운", "밝은", "차분한")
        val objects = listOf("머그컵", "가방", "연필", "나침반", "우산", "노트", "램프", "시계")
        val seed = abs((Build.MODEL + System.currentTimeMillis()).hashCode())
        return adjectives[seed % adjectives.size] + objects[(seed / adjectives.size) % objects.size]
    }

    private fun publishPoll(question: String, options: List<String>, durationMinutes: Int) {
        val poll = NearbyPoll(
            id = "poll-${System.currentTimeMillis()}",
            proposerId = userId,
            proposerName = selfName,
            question = question,
            options = options,
            durationMinutes = durationMinutes,
            endAtMillis = System.currentTimeMillis() + durationMinutes * 60_000L
        )
        activePoll = poll
        receivedVotes.clear()
        receivedVoteNames.clear()
        sharedResult = null
        sharedResultPollIds -= poll.id
        startNearbyConnectionTest()
        sendPoll(poll)
        scheduleResultShare(poll)
        showPublishedPoll(poll)
    }

    private fun sendPoll(poll: NearbyPoll) {
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.POLL,
                senderId = userId,
                payloadJson = poll.toPayloadJson()
            ).toJson()
        )
    }

    private fun castVote(poll: NearbyPoll, option: String) {
        if (submittedVotes.containsKey(poll.id) && poll.id != activePoll?.id) {
            Toast.makeText(this, "이미 참여한 투표입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (receivedVotes.containsKey(userId) && poll.id == activePoll?.id) {
            Toast.makeText(this, "이미 참여한 투표입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (poll.hasEnded()) {
            Toast.makeText(this, "종료된 투표입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.VOTE,
                senderId = userId,
                payloadJson = JSONObject()
                    .put("pollId", poll.id)
                    .put("option", option)
                    .put("voterId", userId)
                    .put("voterName", selfName)
                    .toString()
            ).toJson()
        )
        if (poll.id == activePoll?.id) {
            receivedVotes[userId] = option
            receivedVoteNames[userId] = selfName
            showPublishedPoll(poll)
        } else {
            submittedVotes[poll.id] = option
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
                if (poll.proposerId == userId) return
                incomingPoll = poll
                runOnUiThread { showVotePoll(poll) }
            }
            NearVoteMessageType.VOTE -> {
                val payload = JSONObject(message.payloadJson)
                val poll = activePoll ?: return
                if (payload.optString("pollId") != poll.id) return
                val voterId = payload.optString("voterId", message.senderId)
                val voterName = payload.optString("voterName", voterId.take(8))
                val option = payload.optString("option")
                if (option.isBlank()) return
                if (poll.hasEnded()) {
                    appendLog("종료된 투표의 표는 무시함: $voterId")
                    return
                }
                if (receivedVotes.containsKey(voterId)) {
                    appendLog("중복 투표 무시: $voterId")
                    sendReceipt(poll, voterId, voterName, receivedVotes.getValue(voterId))
                    return
                }
                receivedVotes[voterId] = option
                receivedVoteNames[voterId] = voterName
                sendReceipt(poll, voterId, voterName, option)
                runOnUiThread { showPublishedPoll(poll) }
            }
            NearVoteMessageType.RECEIPT -> {
                val payload = JSONObject(message.payloadJson)
                val receipt = VoteReceipt(
                    pollId = payload.getString("pollId"),
                    voterId = payload.getString("voterId"),
                    voterName = payload.optString("voterName", payload.getString("voterId")),
                    voteHash = payload.getString("voteHash")
                )
                if (receipt.voterId == userId) {
                    latestReceipt = receipt
                    store.saveReceipt(receipt)
                    incomingPoll?.takeIf { it.id == receipt.pollId }?.let { poll ->
                        runOnUiThread { showVoteSubmitted(poll, submittedVotes[receipt.pollId] ?: "선택 완료") }
                    }
                }
            }
            NearVoteMessageType.RESULT_BLOCK -> {
                val result = runCatching { SharedResult.fromPayload(message.senderId, message.payloadJson) }.getOrElse {
                    appendLog("결과 블록을 읽지 못함")
                    return
                }
                if (result.proposerId == userId) return
                sharedResult = result
                store.saveResult(result)
                runOnUiThread { showSharedResult(result) }
            }
            else -> Unit
        }
    }

    private fun sendReceipt(poll: NearbyPoll, voterId: String, voterName: String, option: String) {
        val voteHash = hash("${poll.id}:$voterId:$option")
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.RECEIPT,
                senderId = userId,
                payloadJson = JSONObject()
                    .put("pollId", poll.id)
                    .put("voterId", voterId)
                    .put("voterName", voterName)
                    .put("voteHash", voteHash)
                    .toString()
            ).toJson()
        )
    }

    private fun shareResultBlock(poll: NearbyPoll) {
        if (sharedResultPollIds.contains(poll.id)) {
            showSharedResult(sharedResult ?: return)
            return
        }
        val counts = poll.options.associateWith { option ->
            receivedVotes.values.count { it == option }
        }
        val participantIds = receivedVotes.keys.toList()
        val participantNames = participantIds.map { id -> receivedVoteNames[id] ?: id.take(8) }
        val result = SharedResult(
            pollId = poll.id,
            proposerId = userId,
            proposerName = selfName,
            question = poll.question,
            options = poll.options,
            counts = counts,
            participantIds = participantIds,
            participantNames = participantNames,
            participantCount = receivedVotes.size,
            resultHash = SharedResult.computeHash(
                pollId = poll.id,
                question = poll.question,
                options = poll.options,
                counts = counts,
                participantIds = participantIds
            )
        )
        sharedResult = result
        sharedResultPollIds += poll.id
        store.saveResult(result)
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.RESULT_BLOCK,
                senderId = userId,
                payloadJson = result.toPayloadJson()
            ).toJson()
        )
        showSharedResult(result)
    }

    private fun scheduleResultShare(poll: NearbyPoll) {
        val delay = (poll.endAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
        handler.postDelayed({
            if (activePoll?.id == poll.id && !sharedResultPollIds.contains(poll.id)) {
                shareResultBlock(poll)
            }
        }, delay)
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

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NEARBY_HEARTBEAT_MS = 30_000L
    }
}
