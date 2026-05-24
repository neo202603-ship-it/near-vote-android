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
import android.widget.FrameLayout
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val seenIncomingPollIds = linkedSetOf<String>()
    private val seenResultPollIds = linkedSetOf<String>()

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
        handleNearbyMessage(endpointId, message)
    }

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        appendLog("발견: $endpointName ($endpointId)")
    }

    override fun onEndpointConnected(endpointId: String) {
        appendLog("연결됨: $endpointId")
        syncCurrentSessionToPeers()
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
        page.addView(topMenu("홈"))
        page.addView(infoCard("내 아이디", selfName, "결과와 참여자 목록에 표시됩니다."))
        page.addView(sectionTitle("현재 상태"))
        connectionStatusView = connectionBadge()
        page.addView(connectionStatusView)
        addCurrentSessionCards()
        page.addView(sectionTitle("주요 작업"))
        page.addView(actionCard("설문 만들기", "질문과 선택지를 정하고 주변 사람에게 참여 요청을 보냅니다.") {
            showCompose()
        })
        page.addView(actionCard("참여할 투표 찾기", "근처에서 진행 중인 투표를 찾습니다.") {
            showDiscover()
        })
        page.addView(sectionTitle("기록과 도구"))
        page.addView(buttonRow(
            compactButton("지난 결과", BUTTON_OUTLINE) { showHistory() },
            compactButton("미리보기", BUTTON_QUIET) { showSimulationResult() }
        ))
        page.addView(quietButton("고급 진단") { showDiagnostics() })
    }

    private fun addCurrentSessionCards() {
        var hasSession = false
        activePoll?.let { poll ->
            hasSession = true
            val subtitle = if (poll.hasEnded()) {
                "투표 종료 · 참여자 ${receivedVotes.size}명"
            } else {
                "${poll.remainingText()} · 참여자 ${receivedVotes.size}명 · 연결 ${connectedCount}대"
            }
            page.addView(actionCard("게시 중: ${poll.question}", subtitle) { showPublishedPoll(poll) })
        }
        incomingPoll?.let { poll ->
            hasSession = true
            val submitted = submittedVotes[poll.id]
            val subtitle = submitted?.let { "내 선택: $it" } ?: "${poll.proposerName} 제안 · ${poll.remainingText()}"
            page.addView(actionCard("받은 투표: ${poll.question}", subtitle) { showVotePoll(poll) })
        }
        sharedResult?.let { result ->
            hasSession = true
            page.addView(actionCard("최근 결과: ${result.question}", "참여자 ${result.participantCount}명 · 검증 ${if (result.isHashValid()) "완료" else "필요"}") {
                showSharedResult(result)
            })
        }
        if (!hasSession) {
            val hint = if (connectedCount == 0) {
                "가까운 기기와 연결되면 받은 투표가 여기에 표시됩니다."
            } else {
                "새 설문을 만들거나 근처에서 게시된 투표를 기다릴 수 있습니다."
            }
            page.addView(emptyCard("진행 중인 투표 없음", hint))
        }
    }

    private fun showHistory() {
        val results = store.loadResultHistory()
        setPage()
        page.addView(topBar("지난 결과"))
        page.addView(topMenu("결과"))
        if (results.isEmpty()) {
            page.addView(emptyCard("저장된 결과 없음", "결과를 공유받거나 직접 공유하면 여기에 남습니다."))
            page.addView(primaryButton("설문 만들기") { showCompose() })
        } else {
            results.forEach { result ->
                page.addView(actionCard(result.question, "${friendlyTime(result.createdAtMillis)} · 참여자 ${result.participantCount}명 · ${result.resultHash.take(12)}") {
                    showSharedResult(result)
                })
            }
        }
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showMyPage() {
        setPage()
        page.addView(topBar("내 아이디"))
        page.addView(topMenu("설정"))
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
        page.addView(topMenu("설정"))
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
        page.addView(outlineButton("내 아이디 관리") { showMyPage() })
        page.addView(statusCard("내부 사용자 ID", "보이지 않는 고유 ID가 투표 중복 방지와 영수증 검증에 사용됩니다."))
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showCompose(template: PollTemplate? = null) {
        setPage()
        page.addView(topBar("설문 만들기"))
        page.addView(topMenu("투표"))
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
        page.addView(durationChoiceRow(durationInput))
        page.addView(label("직접 입력"))
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
        page.addView(topMenu("투표"))
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
        page.addView(topMenu("투표"))
        val poll = incomingPoll
        if (poll == null) {
            page.addView(statusCard(if (autoConnectEnabled) "자동 연결 대기 중" else "자동 연결 꺼짐", connectionStatusText()))
            page.addView(emptyCard("아직 받은 투표 없음", "근처 사용자가 설문을 게시하면 자동으로 투표 참여 화면이 열립니다."))
        } else {
            page.addView(infoCard("받은 설문", poll.question, poll.options.joinToString(" / ")))
            page.addView(primaryButton("투표 참여하기") { showVotePoll(poll) })
        }
        page.addView(buttonRow(
            compactButton("새 설문 만들기", BUTTON_PRIMARY) { showCompose() },
            compactButton("연결 확인", BUTTON_OUTLINE) { showDiagnostics() }
        ))
        page.addView(quietButton("테스트 투표 미리보기") { showSimulationResult() })
        page.addView(outlineButton("홈으로") { showHome() })
    }

    private fun showPublishedPoll(poll: NearbyPoll) {
        val ended = poll.hasEnded()
        setPage()
        page.addView(topBar("게시한 투표"))
        page.addView(topMenu("투표"))
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
        page.addView(topMenu("투표"))
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
        page.addView(topMenu("투표"))
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
        page.addView(topMenu("결과"))
        page.addView(infoCard("설문", result.question, "제안자: ${result.proposerName} · ${friendlyTime(result.createdAtMillis)}"))
        page.addView(label("결과"))
        val total = result.counts.values.sum().coerceAtLeast(1)
        result.options.forEach { option ->
            val count = result.counts[option] ?: 0
            page.addView(resultRow(option, count, count * 100 / total))
        }
        page.addView(label("참여자 ${result.participantCount}명 · 선택 내역"))
        if (result.participantSelections.isNotEmpty()) {
            result.options.forEach { option ->
                val names = result.participantIds.mapIndexedNotNull { index, participantId ->
                    if (result.participantSelections[participantId] == option) {
                        result.participantNames.getOrNull(index) ?: participantId.take(8)
                    } else {
                        null
                    }
                }
                if (names.isNotEmpty()) {
                    page.addView(statusCard(option, names.joinToString(", ")))
                }
            }
        } else {
            page.addView(statusCard(
                "참여자",
                result.participantNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "참여자 목록 없음"
            ))
        }
        page.addView(label("검증 정보"))
        page.addView(statusCard(
            if (result.isHashValid()) "검증 완료" else "검증 필요",
            "결과 해시 ${result.resultHash.take(16)}"
        ))
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
        connectionStatusView = statusCard("연결 상태", connectionStatusText())
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
            setBackgroundColor(0xFFF4F6F1.toInt())
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
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
                textSize = 31f
                setTextColor(0xFF10251D.toInt())
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

    private fun topMenu(selected: String): LinearLayout {
        return buttonRow(
            compactButton("홈", if (selected == "홈") BUTTON_PRIMARY else BUTTON_QUIET) { showHome() },
            compactButton("투표", if (selected == "투표") BUTTON_PRIMARY else BUTTON_QUIET) { showCompose() },
            compactButton("결과", if (selected == "결과") BUTTON_PRIMARY else BUTTON_QUIET) { showHistory() },
            compactButton("설정", if (selected == "설정") BUTTON_PRIMARY else BUTTON_QUIET) { showSettings() }
        )
    }

    private fun actionCard(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), 0)
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE0E7DD.toInt())
            setOnClickListener { onClick() }
            layoutParams = blockParams()
            addView(View(context).apply {
                background = rounded(0xFF176B4D.toInt(), 16)
                layoutParams = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(12), dp(16))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    textSize = 18f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 14f
                    setTextColor(0xFF526158.toInt())
                    setPadding(0, dp(5), 0, 0)
                })
            })
            addView(TextView(context).apply {
                text = "›"
                textSize = 28f
                setTextColor(0xFF8AA093.toInt())
            })
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

    private fun connectionBadge(): TextView {
        return TextView(this).apply {
            text = connectionBadgeText()
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (connectedCount == 0) 0xFF8B1E1E.toInt() else 0xFF174C8B.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(
                if (connectedCount == 0) 0xFFFFE8E8.toInt() else 0xFFE7F1FF.toInt(),
                24,
                if (connectedCount == 0) 0xFFF0B7B7.toInt() else 0xFFB7D0F5.toInt()
            )
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
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE0E7DD.toInt())
            layoutParams = blockParams()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = option
                    textSize = 17f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "${count}명 · $percent%"
                    textSize = 15f
                    setTextColor(0xFF176B4D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(0xFFE7ECE5.toInt(), 8)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                    topMargin = dp(10)
                }
                addView(View(context).apply {
                    background = rounded(0xFF3D8B67.toInt(), 8)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, percent.coerceIn(0, 100).toFloat())
                })
                addView(FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - percent.coerceIn(0, 100)).toFloat())
                })
            })
        }
    }

    private fun choicePill(text: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF123126.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xFFEAF4EF.toInt(), 24, 0xFFC6DED1.toInt())
            if (onClick != null) {
                setOnClickListener { onClick() }
            }
            layoutParams = blockParams()
        }
    }

    private fun durationChoiceRow(durationInput: EditText): LinearLayout {
        return buttonRow(
            compactButton("5분", BUTTON_CHOICE) { durationInput.setText("5") },
            compactButton("10분", BUTTON_CHOICE) { durationInput.setText("10") },
            compactButton("15분", BUTTON_CHOICE) { durationInput.setText("15") },
            compactButton("30분", BUTTON_CHOICE) { durationInput.setText("30") }
        )
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF526158.toInt())
            setPadding(dp(2), dp(18), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = blockParams()
            buttons.forEachIndexed { index, button ->
                button.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index < buttons.lastIndex) {
                        rightMargin = dp(8)
                    }
                }
                addView(button)
            }
        }
    }

    private fun compactButton(text: String, style: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            setTextColor(
                when (style) {
                    BUTTON_PRIMARY -> 0xFFFFFFFF.toInt()
                    BUTTON_QUIET -> 0xFF526158.toInt()
                    else -> 0xFF176B4D.toInt()
                }
            )
            background = when (style) {
                BUTTON_PRIMARY -> rounded(0xFF176B4D.toInt(), 12)
                BUTTON_QUIET -> rounded(0xFFE9EEE9.toInt(), 12)
                BUTTON_CHOICE -> rounded(0xFFEAF4EF.toInt(), 12, 0xFFB8D8C8.toInt())
                else -> rounded(0xFFFFFFFF.toInt(), 12, 0xFFB8D8C8.toInt())
            }
            setOnClickListener { onClick() }
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
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF176B4D.toInt(), 14)
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
    }

    private fun outlineButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(0xFF176B4D.toInt())
            background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFB8D8C8.toInt())
            setOnClickListener { onClick() }
            layoutParams = blockParams()
        }
    }

    private fun quietButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(0xFF526158.toInt())
            background = rounded(0xFFE9EEE9.toInt(), 14)
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

    private fun syncCurrentSessionToPeers() {
        handler.postDelayed({
            val result = sharedResult
            val poll = activePoll
            when {
                result != null -> sendResultBlock(result)
                poll != null && !poll.hasEnded() -> {
                    appendLog("새 연결 기기에 진행 중인 투표를 자동 전달")
                    sendPoll(poll)
                }
            }
        }, CONNECTION_SYNC_DELAY_MS)
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
                connectionStatusView.text = connectionBadgeText()
                connectionStatusView.setTextColor(if (connectedCount == 0) 0xFF8B1E1E.toInt() else 0xFF174C8B.toInt())
                connectionStatusView.background = rounded(
                    if (connectedCount == 0) 0xFFFFE8E8.toInt() else 0xFFE7F1FF.toInt(),
                    24,
                    if (connectedCount == 0) 0xFFF0B7B7.toInt() else 0xFFB7D0F5.toInt()
                )
            }
        }
    }

    private fun connectionBadgeText(): String {
        if (connectedCount == 0) {
            return "접속자 없음\n${connectionStatusText()}"
        }
        val peerNames = nearby.connectedPeerNames().takeIf { it.isNotEmpty() }?.joinToString(", ")
        return "접속자 있음 · 참여가능\n연결된 기기 ${connectedCount}대${peerNames?.let { " · $it" }.orEmpty()}"
    }

    private fun connectionStatusText(): String {
        if (!autoConnectEnabled) {
            return "설정에서 자동 연결을 켜면 앱 실행 후 주변 기기와 자동으로 연결합니다."
        }
        return if (connectedCount == 0) {
            "연결된 기기 0대 · 약 ${NEARBY_HEARTBEAT_MS / 1000}초마다 주변 연결 상태를 확인합니다."
        } else {
            val peerNames = nearby.connectedPeerNames().takeIf { it.isNotEmpty() }?.joinToString(", ")
            "연결된 기기 ${connectedCount}대${peerNames?.let { " · $it" }.orEmpty()} · 설문 게시와 투표 참여가 가능합니다."
        }
    }

    private fun friendlyTime(timestampMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - timestampMillis).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "방금 전"
            diff < hour -> "${diff / minute}분 전"
            diff < day -> "${diff / hour}시간 전"
            diff < 2 * day -> SimpleDateFormat("어제 HH:mm", Locale.KOREA).format(Date(timestampMillis))
            diff < 7 * day -> SimpleDateFormat("E HH:mm", Locale.KOREA).format(Date(timestampMillis))
            else -> SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(timestampMillis))
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
        seenIncomingPollIds.clear()
        seenResultPollIds.clear()
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
        seenResultPollIds -= poll.id
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
            saveLocalReceipt(poll, option)
            showPublishedPoll(poll)
        } else {
            submittedVotes[poll.id] = option
            showVoteSubmitted(poll, option)
        }
    }

    private fun handleNearbyMessage(endpointId: String, rawMessage: String) {
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
                val alreadyKnown = !seenIncomingPollIds.add(poll.id)
                incomingPoll = poll
                if (alreadyKnown || submittedVotes.containsKey(poll.id)) {
                    appendLog("이미 받은 설문 갱신: ${poll.question}")
                    return
                }
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
                    sendReceipt(endpointId, poll, voterId, voterName, receivedVotes.getValue(voterId))
                    return
                }
                receivedVotes[voterId] = option
                receivedVoteNames[voterId] = voterName
                sendReceipt(endpointId, poll, voterId, voterName, option)
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
                val alreadyKnown = !seenResultPollIds.add(result.pollId)
                sharedResult = result
                store.saveResult(result)
                if (alreadyKnown) {
                    appendLog("이미 받은 결과 블록 갱신: ${result.question}")
                    return
                }
                runOnUiThread { showSharedResult(result) }
            }
            else -> Unit
        }
    }

    private fun saveLocalReceipt(poll: NearbyPoll, option: String) {
        val receipt = VoteReceipt(
            pollId = poll.id,
            voterId = userId,
            voterName = selfName,
            voteHash = hash("${poll.id}:$userId:$option")
        )
        latestReceipt = receipt
        store.saveReceipt(receipt)
    }

    private fun sendReceipt(endpointId: String, poll: NearbyPoll, voterId: String, voterName: String, option: String) {
        val voteHash = hash("${poll.id}:$voterId:$option")
        nearby.sendTo(
            endpointId,
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
        val participantSelections = participantIds.associateWith { id -> receivedVotes.getValue(id) }
        val result = SharedResult(
            pollId = poll.id,
            proposerId = userId,
            proposerName = selfName,
            question = poll.question,
            options = poll.options,
            counts = counts,
            participantIds = participantIds,
            participantNames = participantNames,
            participantSelections = participantSelections,
            participantCount = receivedVotes.size,
            createdAtMillis = System.currentTimeMillis(),
            resultHash = SharedResult.computeHash(
                pollId = poll.id,
                question = poll.question,
                options = poll.options,
                counts = counts,
                participantIds = participantIds,
                participantSelections = participantSelections
            )
        )
        sharedResult = result
        sharedResultPollIds += poll.id
        store.saveResult(result)
        sendResultBlock(result)
        showSharedResult(result)
    }

    private fun sendResultBlock(result: SharedResult) {
        appendLog("결과 블록 전송")
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.RESULT_BLOCK,
                senderId = userId,
                payloadJson = result.toPayloadJson()
            ).toJson()
        )
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
        private const val CONNECTION_SYNC_DELAY_MS = 500L
        private const val BUTTON_PRIMARY = 1
        private const val BUTTON_OUTLINE = 2
        private const val BUTTON_QUIET = 3
        private const val BUTTON_CHOICE = 4
    }
}
