package com.nearvote.app

import android.Manifest
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var topConnectionBadgeView: TextView? = null
    private var compactTitleBarView: LinearLayout? = null
    private var compactTitleTextView: TextView? = null
    private var expandedTitleView: View? = null
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
    private val activePolls = linkedMapOf<String, NearbyPoll>()
    private val incomingPolls = linkedMapOf<String, NearbyPoll>()
    private var latestReceipt: VoteReceipt? = null
    private var sharedResult: SharedResult? = null
    private val sharedResultsByPoll = linkedMapOf<String, SharedResult>()
    private val receivedVotesByPoll = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val receivedVoteNamesByPoll = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val submittedVotes = linkedMapOf<String, String>()
    private val acceptedPollIds = linkedSetOf<String>()
    private val declinedPollIds = linkedSetOf<String>()
    private val sharedResultPollIds = linkedSetOf<String>()
    private val seenIncomingPollIds = linkedSetOf<String>()
    private val seenResultPollIds = linkedSetOf<String>()
    private val screenBackStack = ArrayDeque<() -> Unit>()
    private var currentScreen: (() -> Unit)? = null
    private var restoringScreen = false

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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBackInApp()
            }
        })
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
        setPage("홈")
        rememberScreen { showHome() }
        page.addView(breadcrumb("홈"))
        page.addView(header("근거리 투표", "가까이 있는 사람들과 바로 투표를 열고 결과를 나눠 갖습니다."))
        page.addView(infoCard("내 아이디", selfName, "결과와 참여자 목록에 표시됩니다."))
        addCurrentSessionCards()
    }

    private fun addCurrentSessionCards() {
        var hasSession = false
        visibleActivePolls().forEach { poll ->
            hasSession = true
            val receivedVotes = votesFor(poll.id)
            val subtitle = if (poll.hasEnded()) {
                "투표 종료 · 참여자 ${receivedVotes.size}명"
            } else {
                "${poll.remainingText()} · 참여자 ${receivedVotes.size}명 · 연결 ${connectedCount}대"
            }
            page.addView(pollActionCard("📝 ${poll.question}", subtitle, poll) { showPublishedPoll(poll) })
        }
        visibleIncomingPolls().forEach { poll ->
            hasSession = true
            val submitted = submittedVotes[poll.id]
            val accepted = acceptedPollIds.contains(poll.id)
            val subtitle = when {
                submitted != null -> "내 선택: $submitted"
                accepted -> "${poll.proposerName} 제안 · ${poll.remainingText()}"
                else -> "${poll.proposerName}님의 참여 요청 · 수락 후 투표 가능"
            }
            page.addView(pollActionCard("📥 ${poll.question}", subtitle, poll) {
                if (accepted || submitted != null) {
                    showVotePoll(poll)
                } else {
                    showPollInvitation(poll)
                }
            })
        }
        sharedResult?.let { result ->
            hasSession = true
            page.addView(actionCard("${resultOwnershipIcon(result)} 최근 결과: ${result.question}", "${resultOwnershipLabel(result)} · 참여자 ${result.participantCount}명 · 검증 ${if (result.isHashValid()) "완료" else "필요"}") {
                showSharedResult(result)
            })
        }
        if (!hasSession) {
            val hint = if (connectedCount == 0) {
                "가까운 기기와 연결되면 받은 투표가 여기에 표시됩니다."
            } else {
                "새 투표를 만들거나 근처에서 게시된 투표를 기다릴 수 있습니다."
            }
            page.addView(emptyCard("진행 중인 투표 없음", hint))
        }
    }

    private fun showHistory() {
        val results = store.loadResultHistory()
        setPage("결과")
        rememberScreen { showHistory() }
        page.addView(breadcrumb("홈", "결과"))
        page.addView(topBar("지난 결과"))
        if (results.isEmpty()) {
            page.addView(emptyCard("저장된 결과 없음", "결과를 공유받거나 직접 공유하면 여기에 남습니다."))
            page.addView(primaryButton("투표 만들기") { showCompose() })
        } else {
            results.forEach { result ->
                page.addView(actionCard("${resultOwnershipIcon(result)} ${result.question}", "${resultOwnershipLabel(result)} · ${friendlyTime(result.createdAtMillis)} · 참여자 ${result.participantCount}명") {
                    showSharedResult(result)
                })
            }
        }
    }

    private fun showMyPage() {
        setPage("설정")
        rememberScreen { showMyPage() }
        page.addView(breadcrumb("홈", "설정", "내 아이디"))
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
    }

    private fun showSettings() {
        setPage("설정")
        rememberScreen { showSettings() }
        page.addView(breadcrumb("홈", "설정"))
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
        page.addView(outlineButton("내 아이디 관리") { showMyPage() })
        page.addView(quietButton("고급 진단") { showDiagnostics() })
        page.addView(versionFooter())
    }

    private fun showCompose(template: PollTemplate? = null) {
        setPage("투표")
        rememberScreen { showCompose(template) }
        page.addView(breadcrumb("홈", "투표", "투표 만들기"))
        page.addView(topBar("투표 만들기"))
        page.addView(bodyText("질문과 선택지를 입력하고 주변 사람에게 바로 게시합니다."))

        val selectedTemplate = template ?: store.loadTemplates().first()
        val questionInput = inputBox("질문", selectedTemplate.question)
        val optionsInput = inputBox("선택지", selectedTemplate.options.joinToString("\n"), multiLine = true)
        val durationInput = inputBox("제한시간(초)", selectedTemplate.durationSeconds.toString(), numberOnly = true)

        page.addView(label("템플릿"))
        page.addView(outlineButton("템플릿 선택") {
            showTemplatePicker(questionInput.text.toString(), optionsInput.text.toString(), durationInput.text.toString())
        })

        page.addView(label("질문"))
        page.addView(questionInput)
        page.addView(label("선택지"))
        page.addView(optionsInput)
        page.addView(label("제한시간"))
        page.addView(durationChoiceGrid(durationInput))
        page.addView(label("직접 입력(초)"))
        page.addView(durationInput)

        val publishButton = primaryButton("게시하기") {
            val question = questionInput.text.toString().trim()
            val options = optionsInput.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val durationSeconds = durationInput.text.toString().toIntOrNull() ?: 300
            if (question.isBlank()) {
                Toast.makeText(this, "질문을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            if (options.size < 2) {
                Toast.makeText(this, "선택지는 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            publishPoll(question, options, durationSeconds.coerceIn(30, 3_600))
        }
        val saveTemplateButton = outlineButton("템플릿으로 저장") {
            val template = buildTemplateFromInputs(questionInput, optionsInput, durationInput) ?: return@outlineButton
            store.saveTemplate(template)
            Toast.makeText(this, "템플릿 저장 완료", Toast.LENGTH_SHORT).show()
        }
        page.addView(buttonRow(saveTemplateButton, publishButton))
    }

    private fun showTemplatePicker(
        currentQuestion: String = "점심메뉴는?",
        currentOptions: String = "한식\n분식\n샐러드",
        currentDuration: String = "300"
    ) {
        setPage("투표")
        rememberScreen { showTemplatePicker(currentQuestion, currentOptions, currentDuration) }
        page.addView(breadcrumb("홈", "투표", "템플릿 선택"))
        page.addView(topBar("템플릿 선택"))
        page.addView(bodyText("템플릿을 선택하면 투표 작성 화면에 질문과 선택지가 채워집니다."))
        store.loadTemplates().forEach { template ->
            page.addView(templatePickerRow(template, currentQuestion, currentOptions, currentDuration))
        }
        page.addView(outlineButton("작성 화면으로") {
            showCompose(
                PollTemplate(
                    id = "draft",
                    title = currentQuestion.ifBlank { "새 투표" },
                    question = currentQuestion.ifBlank { "점심메뉴는?" },
                    options = currentOptions.lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("한식", "분식", "샐러드") },
                    durationMinutes = ((currentDuration.toIntOrNull() ?: 300) + 59) / 60,
                    durationSeconds = currentDuration.toIntOrNull() ?: 300
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
        val durationSeconds = (durationInput.text.toString().toIntOrNull() ?: 300).coerceIn(30, 3_600)
        return PollTemplate(
            id = "template-${System.currentTimeMillis()}",
            title = question,
            question = question,
            options = options,
            durationMinutes = ((durationSeconds + 59) / 60).coerceIn(1, 60),
            durationSeconds = durationSeconds
        )
    }

    private fun templatePickerRow(
        template: PollTemplate,
        currentQuestion: String,
        currentOptions: String,
        currentDuration: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = blockParams()
        }
        val card = templateCard(template) {
            showCompose(template)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(card)
        if (!template.builtIn) {
            val deleteButton = Button(this).apply {
                text = "삭제"
                isAllCaps = false
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0xFFB3261E.toInt(), 14)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(72)).apply {
                    leftMargin = dp(8)
                }
                setOnClickListener {
                    store.deleteTemplate(template.id)
                    Toast.makeText(this@MainActivity, "템플릿 삭제 완료", Toast.LENGTH_SHORT).show()
                    showTemplatePicker(currentQuestion, currentOptions, currentDuration)
                }
            }
            var downX = 0f
            var swiped = false
            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        swiped = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (downX - event.x > dp(44)) {
                            deleteButton.visibility = View.VISIBLE
                            swiped = true
                            true
                        } else if (event.x - downX > dp(28)) {
                            deleteButton.visibility = View.GONE
                            false
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> swiped
                    else -> false
                }
            }
            row.addView(deleteButton)
        }
        return row
    }

    private fun templateCard(template: PollTemplate, onClick: () -> Unit): LinearLayout {
        val icon = if (template.builtIn) "☆" else "👤"
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
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = "$icon ${template.title}"
                        textSize = 18f
                        setTextColor(0xFF10251D.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(tagPill("${template.durationSeconds}초"))
                })
                addView(templateTagBar(template).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                    }
                })
            })
            addView(TextView(context).apply {
                text = "›"
                textSize = 28f
                setTextColor(0xFF8AA093.toInt())
            })
        }
    }

    private fun showDiscover() {
        setPage("투표")
        rememberScreen { showDiscover() }
        val polls = visibleIncomingPolls()
        page.addView(breadcrumb("홈", "투표", "진행중인 투표"))
        page.addView(topBar("진행중인 투표(${polls.size}건)"))
        if (polls.isEmpty()) {
            page.addView(statusCard(if (autoConnectEnabled) "자동 연결 대기 중" else "자동 연결 꺼짐", connectionStatusText()))
            page.addView(emptyCard("아직 받은 투표 없음", "근처 사용자가 투표를 게시하면 참여 요청으로 먼저 표시됩니다."))
        } else {
            polls.forEach { poll ->
                val submitted = submittedVotes[poll.id]
                val accepted = acceptedPollIds.contains(poll.id)
                val subtitle = when {
                    submitted != null -> "내 선택: $submitted"
                    accepted -> "${poll.proposerName} 제안 · ${poll.remainingText()}"
                    else -> "${poll.proposerName}님의 참여 요청 · 수락 후 투표 가능"
                }
                page.addView(actionCard(poll.question, subtitle) {
                    if (accepted || submitted != null) {
                        showVotePoll(poll)
                    } else {
                        showPollInvitation(poll)
                    }
                })
            }
        }
        page.addView(buttonRow(
            compactButton("새 투표 만들기", BUTTON_PRIMARY) { showCompose() },
            compactButton("연결 확인", BUTTON_OUTLINE) { showDiagnostics() }
        ))
    }

    private fun showPollInvitation(poll: NearbyPoll) {
        setPage("투표")
        rememberScreen { showPollInvitation(poll) }
        page.addView(breadcrumb("홈", "투표", "참여 요청"))
        page.addView(topBar("참여 요청"))
        page.addView(infoCard("새 투표 요청", poll.question, "제안자: ${poll.proposerName} · ${poll.remainingText()}"))
        page.addView(countdownCard(poll))
        page.addView(label("선택지 미리보기"))
        page.addView(statusCard("선택지", poll.options.joinToString(" / ")))
        page.addView(bodyText("참여하기를 누르면 선택지 화면으로 이동합니다. 거절하면 이 투표는 홈에서 숨겨집니다."))
        page.addView(buttonRow(
            compactButton("참여하기", BUTTON_PRIMARY) {
                acceptedPollIds += poll.id
                showVotePoll(poll)
            },
            compactButton("거절", BUTTON_OUTLINE) {
                declinedPollIds += poll.id
                incomingPolls.remove(poll.id)
                Toast.makeText(this, "투표 요청을 거절했습니다.", Toast.LENGTH_SHORT).show()
                showHome()
            }
        ))
    }

    private fun showPublishedPoll(poll: NearbyPoll) {
        val ended = poll.hasEnded()
        val receivedVotes = votesFor(poll.id)
        val receivedVoteNames = voteNamesFor(poll.id)
        setPage("투표")
        rememberScreen { showPublishedPoll(poll) }
        page.addView(breadcrumb("홈", "투표", "게시한 투표"))
        page.addView(topBar("게시한 투표"))
        page.addView(infoCard("투표", poll.question, poll.options.joinToString(" / ")))
        page.addView(statusCard(if (ended) "투표 종료" else "투표 진행 중", poll.statusText(connectedCount)))
        page.addView(countdownCard(poll))
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
        if (!sharedResultPollIds.contains(poll.id)) {
            page.addView(primaryButton("투표 종료") { endPollAndShareResult(poll) })
        }
    }

    private fun showVotePoll(poll: NearbyPoll) {
        setPage("투표")
        rememberScreen { showVotePoll(poll) }
        page.addView(breadcrumb("홈", "투표", "투표 참여"))
        page.addView(topBar("투표 참여"))
        page.addView(infoCard("투표", poll.question, "제안자: ${poll.proposerName} · ${poll.remainingText()}"))
        page.addView(countdownCard(poll))
        val submitted = submittedVotes[poll.id]
        when {
            sharedResultsByPoll[poll.id] != null -> {
                page.addView(primaryButton("결과 보기") { showSharedResult(sharedResultsByPoll.getValue(poll.id)) })
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
    }

    private fun showVoteSubmitted(poll: NearbyPoll, option: String) {
        setPage("투표")
        rememberScreen { showVoteSubmitted(poll, option) }
        page.addView(breadcrumb("홈", "투표", "투표 완료"))
        page.addView(topBar("투표 완료"))
        page.addView(statusCard("내 표를 보냈습니다", "${poll.question} · $option"))
        val receipt = latestReceipt
        if (receipt == null || receipt.pollId != poll.id) {
            page.addView(bodyText("제안자 기기에 투표 메시지가 전달되면 영수증이 도착합니다."))
        } else {
            page.addView(statusCard("영수증 수신 완료", "내 표 해시 ${receipt.voteHash.take(16)}"))
        }
        sharedResultsByPoll[poll.id]?.let {
            page.addView(primaryButton("결과 보기") { showSharedResult(it) })
        }
    }

    private fun showSharedResult(result: SharedResult) {
        setPage("결과")
        rememberScreen { showSharedResult(result) }
        page.addView(breadcrumb("홈", "결과", if (isMyResult(result)) "내가 만든 결과" else "공유받은 결과"))
        page.addView(topBar(if (isMyResult(result)) "내가 만든 결과" else "공유받은 결과"))
        page.addView(infoCard("투표", result.question, "${resultOwnershipLabel(result)} · 제안자: ${result.proposerName} · ${friendlyTime(result.createdAtMillis)}"))
        page.addView(label("결과"))
        val total = result.counts.values.sum().coerceAtLeast(1)
        result.options.forEach { option ->
            val count = result.counts[option] ?: 0
            val participants = if (result.participantSelections.isNotEmpty()) {
                result.participantIds.mapIndexedNotNull { index, participantId ->
                    val selected = result.participantSelections[participantId] == option
                    if (selected) result.participantNames.getOrNull(index) ?: participantId.take(8) else null
                }
            } else {
                emptyList()
            }
            page.addView(resultRow(option, count, count * 100 / total, participants))
        }
        if (result.participantSelections.isEmpty() && result.participantNames.isNotEmpty()) {
            page.addView(participantTagBar(result.participantNames).apply {
                layoutParams = blockParams()
            })
        }
        page.addView(verificationBarcodePanel(
            result = result,
            receipt = latestReceipt?.takeIf { it.pollId == result.pollId } ?: store.loadReceipt(result.pollId)
        ))
    }

    private fun showSimulationResult() {
        val preview = simulator.preview()
        setPage("투표")
        rememberScreen { showSimulationResult() }
        page.addView(breadcrumb("홈", "투표", "미리보기"))
        page.addView(topBar("투표 결과"))
        page.addView(infoCard("투표", preview.question, preview.options.joinToString(" / ")))
        page.addView(infoCard("참여자", "${preview.participantIds.size}명", preview.participantIds.joinToString(", ")))
        page.addView(label("결과"))
        preview.resultLines.forEach { result ->
            page.addView(resultRow(result.option, result.count, result.percent))
        }
        page.addView(statusCard("검증 완료", "영수증 ${preview.receiptCount}건 · 결과 해시 ${preview.resultHash}"))
        page.addView(primaryButton("다시 미리보기") { showSimulationResult() })
    }

    private fun showDiagnostics(runSimulation: Boolean = false, autoStart: Boolean = false) {
        setPage("설정")
        rememberScreen { showDiagnostics(runSimulation, autoStart) }
        page.addView(breadcrumb("홈", "설정", "고급 진단"))
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

    private fun setPage(selectedMenu: String) {
        val pageSidePadding = dp(18)
        val pageBaseTopPadding = dp(20)
        val pageBottomPadding = dp(28)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6F1.toInt())
        }
        compactTitleBarView = null
        compactTitleTextView = null
        expandedTitleView = null
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F1.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCompactTitleBar(scrollY)
            }
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pageSidePadding, pageBaseTopPadding + systemStatusTopInset(), pageSidePadding, pageBottomPadding)
        }
        scroll.addView(page)
        root.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(scroll)
            addView(compactTitleBar())
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                page.setPadding(pageSidePadding, pageBaseTopPadding + statusTop, pageSidePadding, pageBottomPadding)
                compactTitleBarView?.setPadding(dp(18), dp(12) + statusTop, dp(18), dp(12))
                insets
            }
            post { ViewCompat.requestApplyInsets(this) }
        })
        root.addView(bottomMenu(selectedMenu))
        setContentView(root)
    }

    private fun rememberScreen(renderer: () -> Unit) {
        if (!restoringScreen) {
            currentScreen?.let { screenBackStack.addLast(it) }
        }
        currentScreen = renderer
    }

    private fun goBackInApp() {
        val previous = if (screenBackStack.isEmpty()) null else screenBackStack.removeLast()
        if (previous == null) {
            if (currentScreen != null) {
                restoringScreen = true
                showHome()
                restoringScreen = false
            }
            return
        }
        restoringScreen = true
        previous()
        restoringScreen = false
    }

    private fun header(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 31f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(compactButton("투표 생성", BUTTON_PRIMARY) { showCompose() }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(104), dp(42)).apply {
                        leftMargin = dp(10)
                    }
                })
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

    private fun versionFooter(): TextView {
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        return TextView(this).apply {
            text = "현재 버전 v$versionName"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF647268.toInt())
            setPadding(0, dp(24), 0, dp(8))
            layoutParams = blockParams()
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
            addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            })
            compactTitleTextView?.text = title
            expandedTitleView = this
        }
    }

    private fun compactTitleBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(18), dp(12) + systemStatusTopInset(), dp(18), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 0, 0xFFD8E2DA.toInt(), 1)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            addView(TextView(context).apply {
                textSize = 18f
                setTextColor(0xFF10251D.toInt())
                setTypeface(typeface, Typeface.BOLD)
                compactTitleTextView = this
            })
            compactTitleBarView = this
        }
    }

    private fun updateCompactTitleBar(scrollY: Int) {
        val titleView = expandedTitleView ?: run {
            compactTitleBarView?.visibility = View.GONE
            return
        }
        val shouldShow = titleView.bottom > 0 && scrollY >= titleView.bottom
        compactTitleBarView?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun bottomMenu(selected: String): LinearLayout {
        val buttons = listOf(
            menuItem("홈", "⌂", selected == "홈") { showHome() },
            menuItem("투표", "+", selected == "투표") { showCompose() },
            menuItem("결과", "▤", selected == "결과") { showHistory() },
            menuItem("설정", "⚙", selected == "설정") { showSettings() }
        )
        return LinearLayout(this).apply {
            val sidePadding = dp(12)
            val topPadding = dp(8)
            val baseBottomPadding = dp(12)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sidePadding, topPadding, sidePadding, baseBottomPadding + systemNavigationBottomInset())
            background = rounded(0xFFFFFFFF.toInt(), 0, 0xFFD8E2DA.toInt(), 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    rightMargin = dp(8)
                }
                buttons.forEachIndexed { index, button ->
                    button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index < buttons.lastIndex) {
                            rightMargin = dp(8)
                        }
                    }
                    addView(button)
                }
            })
            addView(topConnectionBadge())
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(sidePadding, topPadding, sidePadding, baseBottomPadding + navigationBottom)
                insets
            }
            post { ViewCompat.requestApplyInsets(this) }
        }
    }

    private fun menuItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$icon\n$label"
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF526158.toInt())
            background = if (selected) {
                rounded(0xFF176B4D.toInt(), 14)
            } else {
                rounded(0xFFE9EEE9.toInt(), 14)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun topConnectionBadge(): TextView {
        return TextView(this).apply {
            text = topConnectionBadgeText()
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(connectionBadgeTextColor())
            background = rounded(connectionBadgeBackgroundColor(), 18, connectionBadgeStrokeColor(), 2)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(38))
            setOnClickListener { showConnectionPopup() }
            topConnectionBadgeView = this
        }
    }

    private fun showConnectionPopup() {
        val peers = nearby.connectedPeerNames()
        val message = if (peers.isEmpty()) {
            if (autoConnectEnabled) {
                "현재 연결된 기기가 없습니다.\n앱은 주변 기기를 자동으로 찾고 있습니다."
            } else {
                "현재 연결된 기기가 없습니다.\n설정에서 자동 연결을 켜면 주변 기기를 찾습니다."
            }
        } else {
            peers.joinToString(separator = "\n") { name -> "• $name" }
        }
        AlertDialog.Builder(this)
            .setTitle(if (autoConnectEnabled) "접속자 ${connectedCount}명" else "자동 연결 꺼짐")
            .setMessage(message)
            .setPositiveButton(if (autoConnectEnabled) "연결 끄기" else "연결 켜기") { _, _ ->
                setAutoConnectEnabled(!autoConnectEnabled)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun breadcrumb(vararg items: String): TextView {
        return TextView(this).apply {
            text = items.joinToString(" > ")
            textSize = 13f
            setTextColor(0xFF647268.toInt())
            setPadding(dp(2), 0, 0, dp(12))
            layoutParams = blockParams()
        }
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

    private fun pollActionCard(title: String, subtitle: String, poll: NearbyPoll, onClick: () -> Unit): LinearLayout {
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
                setPadding(dp(16), dp(16), dp(10), dp(16))
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
            addView(CountdownRingView(poll, compact = true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                    rightMargin = dp(10)
                }
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
            setTextColor(connectionBadgeTextColor())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(connectionBadgeBackgroundColor(), 24, connectionBadgeStrokeColor(), 2)
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

    private fun resultRow(
        option: String,
        count: Int,
        percent: Int,
        participants: List<String> = emptyList()
    ): LinearLayout {
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
            if (participants.isNotEmpty()) {
                addView(participantTagBar(participants).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(12)
                    }
                })
            }
        }
    }

    private fun participantTagBar(participants: List<String>): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                participants.forEach { participant ->
                    addView(TextView(context).apply {
                        text = "#$participant"
                        textSize = 13f
                        setTextColor(0xFF245341.toInt())
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = rounded(0xFFEAF4EF.toInt(), 18, 0xFFC6DED1.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            rightMargin = dp(8)
                        }
                    })
                }
            })
        }
    }

    private fun templateTagBar(template: PollTemplate): HorizontalScrollView {
        return tagBar(template.options)
    }

    private fun tagBar(tags: List<String>): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tags.forEach { tag ->
                    addView(tagPill(tag))
                }
            })
        }
    }

    private fun tagPill(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF245341.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFFEAF4EF.toInt(), 18, 0xFFC6DED1.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(8)
            }
        }
    }

    private fun verificationBarcodePanel(result: SharedResult, receipt: VoteReceipt?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(
                if (result.isHashValid()) 0xFFF4FBF7.toInt() else 0xFFFFF3F3.toInt(),
                16,
                if (result.isHashValid()) 0xFFB7DCC9.toInt() else 0xFFE0B2B2.toInt()
            )
            layoutParams = blockParams()
            addView(BarcodeView("result:${result.resultHash}:${result.isHashValid()}").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(58)
                )
            })
            receipt?.let {
                addView(BarcodeView("receipt:${it.voteHash}").apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(58)
                    ).apply {
                        topMargin = dp(12)
                    }
                })
            }
        }
    }

    private fun countdownCard(poll: NearbyPoll): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFD8E2DA.toInt())
            layoutParams = blockParams()
            addView(CountdownRingView(poll).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                    rightMargin = dp(16)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = if (poll.hasEnded()) "투표 시간이 종료되었습니다" else "남은 시간"
                    textSize = 18f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = if (poll.hasEnded()) {
                        "결과 공유를 기다리거나 직접 결과를 공유할 수 있습니다."
                    } else {
                        "원형 표시가 줄어들수록 마감 시간이 가까워집니다."
                    }
                    textSize = 14f
                    setTextColor(0xFF526158.toInt())
                    setPadding(0, dp(6), 0, 0)
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

    private fun durationChoiceGrid(durationInput: EditText): LinearLayout {
        return compactButtonRow(
            compactButton("30초", BUTTON_CHOICE) { durationInput.setText("30") },
            compactButton("1분", BUTTON_CHOICE) { durationInput.setText("60") },
            compactButton("5분", BUTTON_CHOICE) { durationInput.setText("300") },
            compactButton("10분", BUTTON_CHOICE) { durationInput.setText("600") },
            compactButton("15분", BUTTON_CHOICE) { durationInput.setText("900") },
            compactButton("30분", BUTTON_CHOICE) { durationInput.setText("1800") }
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

    private fun compactButtonRow(vararg buttons: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = blockParams()
            buttons.forEachIndexed { index, button ->
                button.textSize = 12f
                button.setPadding(0, 0, 0, 0)
                button.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (index < buttons.lastIndex) {
                        rightMargin = dp(4)
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
            if (sharedResultsByPoll.isNotEmpty()) {
                appendLog("새 연결 기기에 결과 ${sharedResultsByPoll.size}건을 자동 전달")
                sharedResultsByPoll.values.forEach { result -> sendResultBlock(result) }
            }
            val runningPolls = visibleActivePolls().filter { poll -> !poll.hasEnded() }
            if (runningPolls.isNotEmpty()) {
                appendLog("새 연결 기기에 진행 중인 투표 ${runningPolls.size}건을 자동 전달")
                runningPolls.forEach { poll -> sendPoll(poll) }
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
                connectionStatusView.setTextColor(connectionBadgeTextColor())
                connectionStatusView.background = rounded(connectionBadgeBackgroundColor(), 24, connectionBadgeStrokeColor(), 2)
            }
            topConnectionBadgeView?.let { badge ->
                badge.text = topConnectionBadgeText()
                badge.setTextColor(connectionBadgeTextColor())
                badge.background = rounded(connectionBadgeBackgroundColor(), 18, connectionBadgeStrokeColor(), 2)
            }
        }
    }

    private fun topConnectionBadgeText(): String {
        return if (autoConnectEnabled) connectedCount.toString() else "🚫"
    }

    private fun connectionBadgeTextColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFF5F6661.toInt()
            connectedCount == 0 -> 0xFF8B1E1E.toInt()
            else -> 0xFF174C8B.toInt()
        }
    }

    private fun connectionBadgeBackgroundColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFFE9ECE9.toInt()
            connectedCount == 0 -> 0xFFFFE8E8.toInt()
            else -> 0xFFE7F1FF.toInt()
        }
    }

    private fun connectionBadgeStrokeColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFF9AA39C.toInt()
            connectedCount == 0 -> 0xFFD76A6A.toInt()
            else -> 0xFF5B91D9.toInt()
        }
    }

    private fun connectionBadgeText(): String {
        if (!autoConnectEnabled) {
            return "자동 연결 꺼짐\n설정에서 자동 연결을 켜면 주변 기기를 찾습니다."
        }
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
            "연결된 기기 ${connectedCount}대${peerNames?.let { " · $it" }.orEmpty()} · 투표 게시와 참여가 가능합니다."
        }
    }

    private fun visibleIncomingPolls(): List<NearbyPoll> {
        return incomingPolls.values
            .filter { poll ->
                !poll.hasEnded() &&
                    !declinedPollIds.contains(poll.id) &&
                    !sharedResultsByPoll.containsKey(poll.id)
            }
            .sortedBy { it.endAtMillis }
    }

    private fun visibleActivePolls(): List<NearbyPoll> {
        return activePolls.values
            .filter { poll -> !poll.hasEnded() }
            .sortedBy { it.endAtMillis }
    }

    private fun votesFor(pollId: String): LinkedHashMap<String, String> {
        return receivedVotesByPoll.getOrPut(pollId) { linkedMapOf() }
    }

    private fun voteNamesFor(pollId: String): LinkedHashMap<String, String> {
        return receivedVoteNamesByPoll.getOrPut(pollId) { linkedMapOf() }
    }

    private fun isMyResult(result: SharedResult): Boolean {
        return result.proposerId == userId
    }

    private fun resultOwnershipLabel(result: SharedResult): String {
        return if (isMyResult(result)) "내가 만든 투표" else "공유받은 투표"
    }

    private fun resultOwnershipIcon(result: SharedResult): String {
        return if (isMyResult(result)) "📝" else "📥"
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
        activePolls.clear()
        incomingPolls.clear()
        latestReceipt = null
        sharedResult = null
        sharedResultsByPoll.clear()
        receivedVotesByPoll.clear()
        receivedVoteNamesByPoll.clear()
        submittedVotes.clear()
        acceptedPollIds.clear()
        declinedPollIds.clear()
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

    private fun publishPoll(question: String, options: List<String>, durationSeconds: Int) {
        val durationMinutes = ((durationSeconds + 59) / 60).coerceAtLeast(1)
        val poll = NearbyPoll(
            id = "poll-${System.currentTimeMillis()}",
            proposerId = userId,
            proposerName = selfName,
            question = question,
            options = options,
            durationMinutes = durationMinutes,
            durationSeconds = durationSeconds,
            endAtMillis = System.currentTimeMillis() + durationSeconds * 1_000L
        )
        activePolls[poll.id] = poll
        receivedVotesByPoll[poll.id] = linkedMapOf()
        receivedVoteNamesByPoll[poll.id] = linkedMapOf()
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
        val isPublishedByMe = activePolls.containsKey(poll.id)
        val receivedVotes = votesFor(poll.id)
        val receivedVoteNames = voteNamesFor(poll.id)
        if (submittedVotes.containsKey(poll.id) && !isPublishedByMe) {
            Toast.makeText(this, "이미 참여한 투표입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (receivedVotes.containsKey(userId) && isPublishedByMe) {
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
        if (isPublishedByMe) {
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
                    appendLog("투표 메시지를 읽지 못함")
                    return
                }
                if (poll.proposerId == userId) return
                if (declinedPollIds.contains(poll.id)) {
                    appendLog("거절한 투표는 무시함: ${poll.question}")
                    return
                }
                val alreadyKnown = !seenIncomingPollIds.add(poll.id)
                incomingPolls[poll.id] = poll
                if (alreadyKnown || submittedVotes.containsKey(poll.id)) {
                    appendLog("이미 받은 투표 갱신: ${poll.question}")
                    return
                }
                runOnUiThread { showPollInvitation(poll) }
            }
            NearVoteMessageType.VOTE -> {
                val payload = JSONObject(message.payloadJson)
                val poll = activePolls[payload.optString("pollId")] ?: return
                val receivedVotes = votesFor(poll.id)
                val receivedVoteNames = voteNamesFor(poll.id)
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
                    incomingPolls[receipt.pollId]?.let { poll ->
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
                if (declinedPollIds.contains(result.pollId)) {
                    appendLog("거절한 투표의 결과는 무시함: ${result.question}")
                    return
                }
                val alreadyKnown = !seenResultPollIds.add(result.pollId)
                sharedResult = result
                sharedResultsByPoll[result.pollId] = result
                incomingPolls.remove(result.pollId)
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
            showSharedResult(sharedResultsByPoll[poll.id] ?: return)
            return
        }
        val receivedVotes = votesFor(poll.id)
        val receivedVoteNames = voteNamesFor(poll.id)
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
        sharedResultsByPoll[poll.id] = result
        sharedResultPollIds += poll.id
        store.saveResult(result)
        sendResultBlock(result)
        showSharedResult(result)
    }

    private fun endPollAndShareResult(poll: NearbyPoll) {
        val endedPoll = poll.copy(endAtMillis = System.currentTimeMillis())
        activePolls[poll.id] = endedPoll
        shareResultBlock(endedPoll)
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
            if (activePolls.containsKey(poll.id) && !sharedResultPollIds.contains(poll.id)) {
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

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) {
                setStroke(dp(strokeWidth), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun systemNavigationBottomInset(): Int {
        return resources.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: dp(16)
    }

    private fun systemStatusTopInset(): Int {
        return resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: dp(24)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inner class CountdownRingView(
        private val poll: NearbyPoll,
        private val compact: Boolean = false
    ) : View(this) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE7ECE5.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF176B4D.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF647268.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF10251D.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = dp(if (compact) 5 else 8).toFloat()
            trackPaint.strokeWidth = stroke
            progressPaint.strokeWidth = stroke
            titlePaint.textSize = dp(if (compact) 8 else 11).toFloat()
            timePaint.textSize = dp(if (compact) 11 else 17).toFloat()

            val size = width.coerceAtMost(height).toFloat()
            val inset = stroke / 2f + dp(4)
            val bounds = RectF(inset, inset, size - inset, size - inset)
            val totalMillis = (poll.durationSeconds * 1_000L).coerceAtLeast(1L)
            val remainingMillis = (poll.endAtMillis - System.currentTimeMillis()).coerceIn(0L, totalMillis)
            val ratio = remainingMillis.toFloat() / totalMillis.toFloat()

            canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
            canvas.drawArc(bounds, -90f, 360f * ratio, false, progressPaint)
            if (compact) {
                canvas.drawText(formatRemaining(remainingMillis), width / 2f, height / 2f + dp(4), timePaint)
            } else {
                canvas.drawText("남은", width / 2f, height / 2f - dp(8), titlePaint)
                canvas.drawText(formatRemaining(remainingMillis), width / 2f, height / 2f + dp(15), timePaint)
            }

            if (remainingMillis > 0L) {
                postInvalidateDelayed(1_000L)
            }
        }
    }

    private inner class BarcodeView(private val value: String) : View(this) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF10251D.toInt()
            style = Paint.Style.FILL
        }
        private val guardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF176B4D.toInt()
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val quietZone = dp(8).toFloat()
            val top = dp(6).toFloat()
            val bottom = height - dp(6).toFloat()
            val availableWidth = (width - quietZone * 2).coerceAtLeast(1f)
            val encoded = hash(value)
            val units = encoded.length * 2 + 6
            val unitWidth = availableWidth / units
            var x = quietZone

            repeat(2) {
                canvas.drawRect(x, top, x + unitWidth, bottom, guardPaint)
                x += unitWidth * 1.5f
            }

            encoded.forEachIndexed { index, char ->
                val nibble = char.digitToIntOrNull(16) ?: 0
                val barUnits = 0.7f + (nibble % 4) * 0.35f
                val barTop = top + if (index % 5 == 0) 0f else dp(5).toFloat()
                canvas.drawRect(x, barTop, x + unitWidth * barUnits, bottom, barPaint)
                x += unitWidth * 2
            }

            repeat(2) {
                canvas.drawRect(x, top, x + unitWidth, bottom, guardPaint)
                x += unitWidth * 1.5f
            }
        }
    }

    private fun formatRemaining(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "%d:%02d".format(minutes, seconds)
        } else {
            "${seconds}초"
        }
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
