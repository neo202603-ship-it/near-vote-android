package com.nearvote.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity(), NearbyVoteConnectionManager.Listener {
    private lateinit var page: LinearLayout
    private var pageScrollView: ScrollView? = null
    private var keyboardLiftTarget: View? = null
    private lateinit var logView: TextView
    private lateinit var connectionStatusView: TextView
    private var topConnectionBadgeContainerView: FrameLayout? = null
    private var topConnectionBadgePulseView: View? = null
    private var topConnectionBadgeView: TextView? = null
    private var compactTitleBarView: LinearLayout? = null
    private var compactTitleTextView: TextView? = null
    private var expandedTitleView: View? = null
    private lateinit var nearby: NearbyVoteConnectionManager
    private lateinit var simulator: LocalVoteSimulator
    private lateinit var store: NearVoteStore
    private val avatarSheet: Bitmap by lazy { BitmapFactory.decodeResource(resources, R.drawable.avatar_sheet) }
    private val handler = Handler(Looper.getMainLooper())
    private val nearbyHeartbeat = object : Runnable {
        override fun run() {
            nearby.maintainNearbyMode()
            updateConnectionStatus()
            handler.postDelayed(this, NEARBY_HEARTBEAT_MS)
        }
    }
    private val nearbyPulse = object : Runnable {
        override fun run() {
            animateConnectionSearchPulse()
            handler.postDelayed(this, NEARBY_PULSE_MS)
        }
    }
    private var selfName = ""
    private var selfAvatarId = 0
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
    private val receivedVoteAvatarsByPoll = linkedMapOf<String, LinkedHashMap<String, Int>>()
    private val voteEndpointIdsByPoll = linkedMapOf<String, LinkedHashMap<String, String>>()
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
    ) {
        val granted = hasNearbyPermissions()
        appendLog(if (granted) "권한 준비 완료" else "일부 권한이 꺼져 있음")
        if (granted) {
            applyAutoConnectSetting()
        } else {
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NearVoteStore(this)
        selfName = store.loadIdentity { suggestIdentity() }
        userId = store.loadUserId { UUID.randomUUID().toString() }
        selfAvatarId = store.loadAvatarId { avatarIdForUser(userId) }
        autoConnectEnabled = store.isAutoConnectEnabled()
        nearby = NearbyVoteConnectionManager(this, selfName, this)
        simulator = LocalVoteSimulator(selfName)
        restoreSessionState()
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
        animateConnectionSearchPulse()
        appendLog("발견: $endpointName ($endpointId)")
    }

    override fun onEndpointConnected(endpointId: String) {
        appendLog("연결됨: $endpointId")
        sendProfile(endpointId)
    }

    override fun onEndpointDisconnected(endpointId: String) {
        appendLog("연결 해제: $endpointId")
    }

    override fun onConnectionCountChanged(count: Int) {
        val countChanged = connectedCount != count
        connectedCount = count
        updateConnectionStatus(animateBadge = countChanged)
    }

    private fun showHome() {
        setPage("홈")
        rememberScreen { showHome() }
        page.addView(header("근거리 투표", "주변 사람들과 투표를 하고 공유 합니다."))
        page.addView(identityCard())
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
            page.addView(sectionTitle("최근 투표"))
            page.addView(resultActionCard(result.question, resultMetadata("참여자 ${result.participantCount}명", "무결성 ${if (result.isHashValid()) "확인" else "오류"}"), result) {
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
                val metadata = mutableListOf(friendlyTime(result.createdAtMillis), "참여자 ${result.participantCount}명")
                winningOptionSummary(result)?.let { metadata += it }
                page.addView(resultActionCard(result.question, resultMetadata(*metadata.toTypedArray()), result) {
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
        var selectedAvatarId = selfAvatarId
        page.addView(label("아바타"))
        page.addView(avatarPicker(selfAvatarId) { selectedAvatarId = it })
        page.addView(label("현재 아이디"))
        page.addView(identityInput)
        page.addView(buttonRow(
            outlineButton("아이디 제안") {
                identityInput.setText(suggestIdentity())
            },
            primaryButton("저장하기") {
                val nextIdentity = identityInput.text.toString().trim()
                if (nextIdentity.length < 2) {
                    Toast.makeText(this, "아이디는 2글자 이상 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                saveIdentity(nextIdentity, selectedAvatarId)
                Toast.makeText(this, "아이디 저장 완료", Toast.LENGTH_SHORT).show()
                showHome()
            }
        ))
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

        val selectedTemplate = template ?: emptyComposeDraft()
        val questionInput = inputBox("질문", selectedTemplate.question)
        val optionEditor = OptionTagEditor(selectedTemplate.options)
        val allowParticipantOptionsInput = CheckBox(this).apply {
            text = "참여자가 선택지 추가 가능"
            textSize = 15f
            setTextColor(0xFF23362D.toInt())
            isChecked = selectedTemplate.allowParticipantOptions
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF176B4D.toInt())
            layoutParams = blockParams()
        }
        val revealSelectionsInput = CheckBox(this).apply {
            text = "결과에서 참여자별 선택 공개"
            textSize = 15f
            setTextColor(0xFF23362D.toInt())
            isChecked = selectedTemplate.revealSelections
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF176B4D.toInt())
            layoutParams = blockParams()
        }
        val durationInput = inputBox("제한시간(초)", selectedTemplate.durationSeconds.toString(), numberOnly = true)
        val hasCustomDuration = !isPresetDuration(selectedTemplate.durationSeconds)
        val extendedDurationChoices = extendedDurationChoices()
        val initialExtendedDurationIndex = closestDurationChoiceIndex(extendedDurationChoices, selectedTemplate.durationSeconds)
        val durationWheel = NumberPicker(this).apply {
            minValue = 0
            maxValue = extendedDurationChoices.lastIndex
            displayedValues = extendedDurationChoices.map { it.second }.toTypedArray()
            value = initialExtendedDurationIndex
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, index ->
                durationInput.setText(extendedDurationChoices[index].first.toString())
            }
        }
        if (hasCustomDuration) {
            durationInput.setText(extendedDurationChoices[initialExtendedDurationIndex].first.toString())
        }
        val customDurationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (hasCustomDuration) View.VISIBLE else View.GONE
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFC6DED1.toInt())
            layoutParams = blockParams()
            addView(durationWheel, LinearLayout.LayoutParams(dp(128), dp(128)))
        }

        page.addView(label("템플릿"))
        page.addView(outlineButton("템플릿 선택") {
            showTemplatePicker(
                questionInput.text.toString(),
                optionEditor.values().joinToString("\n"),
                durationInput.text.toString(),
                allowParticipantOptionsInput.isChecked,
                revealSelectionsInput.isChecked
            )
        })

        page.addView(label("질문"))
        page.addView(questionInput)
        page.addView(label("선택지"))
        page.addView(optionEditor.view)
        page.addView(allowParticipantOptionsInput)
        page.addView(revealSelectionsInput)
        page.addView(label("제한시간"))
        page.addView(durationChoiceGrid(
            durationInput = durationInput,
            onPresetSelected = { customDurationPanel.visibility = View.GONE },
            onCustomSelected = {
                durationInput.setText(extendedDurationChoices[durationWheel.value].first.toString())
                customDurationPanel.visibility = View.VISIBLE
            }
        ))
        page.addView(customDurationPanel)

        val publishButton = primaryButton("게시하기") {
            val question = questionInput.text.toString().trim()
            val options = optionEditor.values()
            val durationSeconds = durationInput.text.toString().toIntOrNull() ?: 300
            if (question.isBlank()) {
                Toast.makeText(this, "질문을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            if (options.size < 2) {
                Toast.makeText(this, "선택지는 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val publish = {
                publishPoll(
                    question,
                    options,
                    durationSeconds.coerceIn(CUSTOM_DURATION_MIN_SECONDS, CUSTOM_DURATION_MAX_SECONDS),
                    allowParticipantOptionsInput.isChecked,
                    revealSelectionsInput.isChecked
                )
            }
            if (connectedCount == 0) {
                confirmPublishingWithoutPeers(publish)
            } else {
                publish()
            }
        }
        val saveTemplateButton = outlineButton("템플릿으로 저장") {
            val template = buildTemplateFromInputs(questionInput, optionEditor.values(), durationInput, allowParticipantOptionsInput.isChecked, revealSelectionsInput.isChecked) ?: return@outlineButton
            store.saveTemplate(template)
            Toast.makeText(this, "템플릿 저장 완료", Toast.LENGTH_SHORT).show()
        }
        page.addView(buttonRow(saveTemplateButton, publishButton))
    }

    private fun showTemplatePicker(
        currentQuestion: String = "",
        currentOptions: String = "",
        currentDuration: String = "300",
        currentAllowParticipantOptions: Boolean = false,
        currentRevealSelections: Boolean = true
    ) {
        setPage("투표")
        rememberScreen { showTemplatePicker(currentQuestion, currentOptions, currentDuration, currentAllowParticipantOptions, currentRevealSelections) }
        page.addView(breadcrumb("홈", "투표", "템플릿 선택"))
        page.addView(topBar("템플릿 선택"))
        page.addView(bodyText("템플릿을 선택하면 투표 작성 화면에 질문과 선택지가 채워집니다."))
        store.loadTemplates().forEach { template ->
            page.addView(templatePickerRow(template, currentQuestion, currentOptions, currentDuration, currentAllowParticipantOptions, currentRevealSelections))
        }
        page.addView(outlineButton("작성 화면으로") {
            showCompose(
                PollTemplate(
                    id = "draft",
                    title = currentQuestion.ifBlank { "새 투표" },
                    question = currentQuestion,
                    options = currentOptions.lines().map { it.trim() }.filter { it.isNotBlank() },
                    durationMinutes = ((currentDuration.toIntOrNull() ?: 300) + 59) / 60,
                    durationSeconds = currentDuration.toIntOrNull() ?: 300,
                    allowParticipantOptions = currentAllowParticipantOptions,
                    revealSelections = currentRevealSelections
                )
            )
        })
    }

    private fun emptyComposeDraft(): PollTemplate {
        return PollTemplate(
            id = "draft",
            title = "새 투표",
            question = "",
            options = emptyList(),
            durationMinutes = 5,
            durationSeconds = 300,
            allowParticipantOptions = false,
            revealSelections = true
        )
    }

    private fun buildTemplateFromInputs(
        questionInput: EditText,
        options: List<String>,
        durationInput: EditText,
        allowParticipantOptions: Boolean,
        revealSelections: Boolean
    ): PollTemplate? {
        val question = questionInput.text.toString().trim()
        if (question.isBlank()) {
            Toast.makeText(this, "질문을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return null
        }
        if (options.size < 2) {
            Toast.makeText(this, "선택지는 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
            return null
        }
        val durationSeconds = (durationInput.text.toString().toIntOrNull() ?: 300)
            .coerceIn(CUSTOM_DURATION_MIN_SECONDS, CUSTOM_DURATION_MAX_SECONDS)
        return PollTemplate(
            id = "template-${System.currentTimeMillis()}",
            title = question,
            question = question,
            options = options,
            durationMinutes = ((durationSeconds + 59) / 60).coerceIn(1, CUSTOM_DURATION_MAX_SECONDS / 60),
            durationSeconds = durationSeconds,
            allowParticipantOptions = allowParticipantOptions,
            revealSelections = revealSelections
        )
    }

    private fun templatePickerRow(
        template: PollTemplate,
        currentQuestion: String,
        currentOptions: String,
        currentDuration: String,
        currentAllowParticipantOptions: Boolean,
        currentRevealSelections: Boolean
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
                    showTemplatePicker(currentQuestion, currentOptions, currentDuration, currentAllowParticipantOptions, currentRevealSelections)
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
                    addView(TextView(context).apply {
                        text = "제한시간 ${formatDurationText(template.durationSeconds)}"
                        textSize = 13f
                        setTextColor(0xFF66776E.toInt())
                    })
                })
                addView(templateTagBar(template).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                    }
                })
                if (template.allowParticipantOptions) {
                    addView(TextView(context).apply {
                        text = "참여자 선택지 추가 허용"
                        textSize = 13f
                        setTextColor(0xFF176B4D.toInt())
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                addView(TextView(context).apply {
                    text = if (template.revealSelections) "선택 내역 공개" else "득표수만 공개"
                    textSize = 13f
                    setTextColor(0xFF66776E.toInt())
                    setPadding(0, dp(6), 0, 0)
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
        page.addView(avatarInfoCard("새 투표 요청", poll.question, "제안자: ${poll.proposerName} · ${poll.remainingText()}", resolvedAvatarId(poll.proposerId, poll.proposerAvatarId)))
        page.addView(countdownCard(poll))
        page.addView(label("선택지 미리보기"))
        page.addView(statusCard("선택지", poll.options.joinToString(" / ")))
        if (poll.allowParticipantOptions) {
            page.addView(bodyText("참여 후 새 선택지를 입력해 바로 투표할 수 있습니다."))
        }
        if (!poll.revealSelections) {
            page.addView(bodyText("결과에는 선택지별 득표수만 공개됩니다."))
        }
        page.addView(bodyText("참여하기를 누르면 선택지 화면으로 이동합니다. 거절하면 이 투표는 홈에서 숨겨집니다."))
        page.addView(buttonRow(
            compactButton("참여하기", BUTTON_PRIMARY) {
                acceptedPollIds += poll.id
                persistSessionState()
                showVotePoll(poll)
            },
            compactButton("거절", BUTTON_OUTLINE) {
                declinedPollIds += poll.id
                incomingPolls.remove(poll.id)
                persistSessionState()
                Toast.makeText(this, "투표 요청을 거절했습니다.", Toast.LENGTH_SHORT).show()
                showHome()
            }
        ))
    }

    private fun showPublishedPoll(poll: NearbyPoll) {
        val ended = poll.hasEnded()
        val receivedVotes = votesFor(poll.id)
        val receivedVoteNames = voteNamesFor(poll.id)
        val receivedVoteAvatars = voteAvatarsFor(poll.id)
        setPage("투표")
        rememberScreen { showPublishedPoll(poll) }
        page.addView(breadcrumb("홈", "투표", "게시한 투표"))
        page.addView(topBar("게시한 투표"))
        page.addView(avatarInfoCard("게시한 투표", poll.question, poll.options.joinToString(" / "), selfAvatarId))
        page.addView(statusCard(if (ended) "투표 종료" else "투표 진행 중", poll.statusText(connectedCount)))
        page.addView(countdownCard(poll))
        if (!ended) {
            page.addView(mySelectionPrompt())
            if (receivedVotes.containsKey(userId)) {
                page.addView(statusCard("이미 참여 완료", receivedVotes[userId].orEmpty()))
            } else {
                poll.options.forEach { option ->
                    page.addView(choicePill(option) { castVote(poll, option) })
                }
                if (poll.allowParticipantOptions) {
                    page.addView(participantOptionComposer(poll))
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
            page.addView(label("참여자 ${receivedVotes.size}명"))
            page.addView(participantTagBar(receivedVoteNames.map { (id, name) ->
                name to (receivedVoteAvatars[id] ?: avatarIdForUser(id))
            }).apply { layoutParams = blockParams() })
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
        page.addView(avatarInfoCard("투표", poll.question, "제안자: ${poll.proposerName} · ${poll.remainingText()}", resolvedAvatarId(poll.proposerId, poll.proposerAvatarId)))
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
                if (poll.allowParticipantOptions) {
                    page.addView(participantOptionComposer(poll))
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
        page.addView(avatarInfoCard("투표", result.question, resultMetadata("제안자: ${result.proposerName}", friendlyTime(result.createdAtMillis)), resolvedAvatarId(result.proposerId, result.proposerAvatarId)))
        page.addView(label("결과"))
        val total = result.counts.values.sum().coerceAtLeast(1)
        rankedResultOptions(result).forEach { option ->
            val count = result.counts[option] ?: 0
            val participants = if (result.participantSelections.isNotEmpty()) {
                result.participantIds.mapIndexedNotNull { index, participantId ->
                    val selected = result.participantSelections[participantId] == option
                    if (selected) {
                        val name = result.participantNames.getOrNull(index) ?: participantId.take(8)
                        name to (result.participantAvatarIds[participantId] ?: avatarIdForUser(participantId))
                    } else {
                        null
                    }
                }
            } else {
                emptyList()
            }
            page.addView(resultRow(option, count, count * 100 / total, participants))
        }
        if (result.participantSelections.isEmpty() && result.participantNames.isNotEmpty()) {
            page.addView(participantTagBar(result.participantNames.mapIndexed { index, name ->
                val id = result.participantIds.getOrNull(index).orEmpty()
                name to (result.participantAvatarIds[id] ?: avatarIdForUser(id))
            }).apply {
                layoutParams = blockParams()
            })
        }
        if (!result.revealSelections) {
            page.addView(bodyText("이 투표는 참여자별 선택 내역을 공개하지 않습니다."))
        }
        page.addView(resultActions(result))
        page.addView(verificationBarcodePanel(
            result = result,
            receipt = latestReceipt?.takeIf { it.pollId == result.pollId } ?: store.loadReceipt(result.pollId)
        ))
    }

    private fun resultActions(result: SharedResult): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = blockParams()
            addView(resultIconButton("재시도", ResultAction.REVOTE, 0xFF176B4D.toInt()) {
                showCompose(resultAsTemplate(result, "draft-result-${System.currentTimeMillis()}"))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                    rightMargin = dp(8)
                }
            })
            addView(resultIconButton("템플릿 저장", ResultAction.SAVE_TEMPLATE, 0xFFB37A19.toInt()) {
                store.saveTemplate(resultAsTemplate(result, "template-${System.currentTimeMillis()}"))
                Toast.makeText(this@MainActivity, "템플릿 저장 완료", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                    leftMargin = dp(8)
                }
            })
        }
    }

    private fun participantOptionComposer(poll: NearbyPoll): LinearLayout {
        val optionInput = inputBox("새 선택지 입력", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                rightMargin = dp(8)
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    keyboardLiftTarget = view
                    view.postDelayed({ liftParticipantOptionInput() }, KEYBOARD_SCROLL_DELAY_MS)
                } else if (keyboardLiftTarget === view) {
                    keyboardLiftTarget = null
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = blockParams()
            addView(TextView(context).apply {
                text = "새 선택지로 투표"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF526158.toInt())
                setPadding(dp(2), dp(10), 0, dp(7))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(optionInput)
                addView(compactButton("제출", BUTTON_CHOICE) {
                    val suggestion = normalizedOption(optionInput.text.toString())
                    when {
                        suggestion.isBlank() -> Toast.makeText(this@MainActivity, "선택지를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                        suggestion.length > MAX_OPTION_LENGTH -> Toast.makeText(this@MainActivity, "선택지는 ${MAX_OPTION_LENGTH}자 이하로 입력해 주세요.", Toast.LENGTH_SHORT).show()
                        suggestion !in poll.options && poll.options.size >= MAX_POLL_OPTION_COUNT -> Toast.makeText(this@MainActivity, "선택지는 최대 ${MAX_POLL_OPTION_COUNT}개까지 추가할 수 있습니다.", Toast.LENGTH_SHORT).show()
                        else -> castVote(poll, suggestion)
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(70), dp(48))
                })
            })
        }
    }

    private fun resultAsTemplate(result: SharedResult, id: String): PollTemplate {
        val durationSeconds = result.durationSeconds.coerceIn(CUSTOM_DURATION_MIN_SECONDS, CUSTOM_DURATION_MAX_SECONDS)
        return PollTemplate(
            id = id,
            title = result.question,
            question = result.question,
            options = result.options,
            durationMinutes = ((durationSeconds + 59) / 60).coerceAtLeast(1),
            durationSeconds = durationSeconds,
            allowParticipantOptions = result.allowParticipantOptions,
            revealSelections = result.revealSelections
        )
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
        page.addView(statusCard("무결성 확인", "영수증 ${preview.receiptCount}건 · 결과 해시 ${preview.resultHash}"))
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
        keyboardLiftTarget = null
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F1.toInt())
            clipToPadding = false
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCompactTitleBar(scrollY)
            }
        }
        pageScrollView = scroll
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
                val keyboardBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val keyboardScrollSpace = if (keyboardBottom > 0) keyboardBottom + dp(24) else 0
                page.setPadding(
                    pageSidePadding,
                    pageBaseTopPadding + statusTop,
                    pageSidePadding,
                    pageBottomPadding + keyboardScrollSpace
                )
                compactTitleBarView?.setPadding(dp(18), dp(12) + statusTop, dp(18), dp(12))
                if (keyboardBottom > 0 && keyboardLiftTarget?.hasFocus() == true) {
                    scroll.post { liftParticipantOptionInput() }
                }
                insets
            }
            post { ViewCompat.requestApplyInsets(this) }
        })
        root.addView(bottomMenu(selectedMenu))
        setContentView(root)
    }

    private fun liftParticipantOptionInput() {
        val target = keyboardLiftTarget?.takeIf { it.hasFocus() } ?: return
        pageScrollView?.let { scroll ->
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height + dp(24)), true)
            scroll.smoothScrollTo(0, page.height)
        }
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

    private inner class OptionTagEditor(initialOptions: List<String>) {
        private val options = initialOptions.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        private var editingIndex: Int? = null
        private val tags = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        private val newOptionInput = inputBox("선택지 추가", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                rightMargin = dp(8)
            }
        }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = blockParams()
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tags)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(newOptionInput)
                addView(compactButton("추가", BUTTON_CHOICE) { submitOption() }.apply {
                    updateButton = this
                    layoutParams = LinearLayout.LayoutParams(dp(70), dp(48))
                })
            })
        }
        private lateinit var updateButton: Button

        init {
            render()
        }

        fun values(): List<String> = options.toList()

        private fun submitOption() {
            val candidate = newOptionInput.text.toString().trim()
            if (candidate.isBlank()) {
                Toast.makeText(this@MainActivity, "선택지를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            val selectedIndex = editingIndex
            if (options.any { it == candidate } && (selectedIndex == null || options[selectedIndex] != candidate)) {
                Toast.makeText(this@MainActivity, "이미 있는 선택지입니다.", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedIndex == null) {
                options += candidate
            } else {
                options[selectedIndex] = candidate
            }
            resetEditor()
            render()
        }

        private fun selectForEdit(index: Int) {
            editingIndex = index
            newOptionInput.setText(options[index])
            newOptionInput.selectAll()
            newOptionInput.hint = "선택지 수정"
            updateButton.text = "수정"
        }

        private fun resetEditor() {
            editingIndex = null
            newOptionInput.text.clear()
            newOptionInput.hint = "선택지 추가"
            updateButton.text = "추가"
        }

        private fun render() {
            tags.removeAllViews()
            options.forEachIndexed { index, option ->
                tags.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(5), dp(7), dp(5))
                    background = rounded(0xFFEAF4EF.toInt(), 20, 0xFFC6DED1.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(44)
                    ).apply {
                        rightMargin = dp(8)
                    }
                    addView(TextView(context).apply {
                        text = option
                        textSize = 15f
                        setTextColor(0xFF123126.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 0, dp(10), 0)
                        setOnClickListener { selectForEdit(index) }
                    })
                    addView(TextView(context).apply {
                        text = "×"
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(0xFF8B1E1E.toInt())
                        setOnClickListener {
                            options.removeAt(index)
                            if (editingIndex == index) {
                                resetEditor()
                            } else if (editingIndex != null && editingIndex!! > index) {
                                editingIndex = editingIndex!! - 1
                            }
                            render()
                        }
                    })
                })
            }
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
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(profileAvatarButton())
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
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                compactTitleTextView = this
            })
            addView(profileAvatarButton(compact = true))
            compactTitleBarView = this
        }
    }

    private fun profileAvatarButton(compact: Boolean = false): FrameLayout {
        val size = if (compact) dp(44) else dp(48)
        val tileSize = if (compact) dp(39) else dp(42)
        return FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "내 아이디 관리"
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { showMyPage() }
            addView(AvatarTileView(selfAvatarId).apply {
                layoutParams = FrameLayout.LayoutParams(tileSize, tileSize, Gravity.CENTER)
            })
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
            menuItem("홈", NavigationIcon.HOME, selected == "홈") { showHome() },
            menuItem("투표", NavigationIcon.CREATE, selected == "투표") { showCompose() },
            menuItem("결과", NavigationIcon.RESULTS, selected == "결과") { showHistory() },
            menuItem("설정", NavigationIcon.SETTINGS, selected == "설정") { showSettings() }
        )
        return LinearLayout(this).apply {
            val sidePadding = dp(12)
            val topPadding = dp(5)
            val baseBottomPadding = dp(8)
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
                layoutParams = LinearLayout.LayoutParams(0, dp(67), 1f).apply {
                    rightMargin = dp(6)
                }
                buttons.forEach { button ->
                    button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
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

    private fun menuItem(label: String, icon: NavigationIcon, selected: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(View(context).apply {
                background = rounded(if (selected) 0xFF176B4D.toInt() else 0x00000000, 2)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(3)).apply {
                    bottomMargin = dp(5)
                }
            })
            addView(NavigationThumbnailView(icon, selected).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(32)).apply {
                    bottomMargin = dp(3)
                }
            })
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) 0xFF176B4D.toInt() else 0xFF617168.toInt())
            })
            setOnClickListener { onClick() }
        }
    }

    private fun resultIconButton(label: String, icon: ResultAction, tint: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFD8E2DA.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(ResultActionIconView(icon, tint).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    rightMargin = dp(9)
                }
            })
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF23362D.toInt())
            })
            setOnClickListener { onClick() }
        }
    }

    private fun topConnectionBadge(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(54))
            clipChildren = false
            setOnClickListener { showConnectionPopup() }
            addView(View(context).apply {
                alpha = 0f
                background = connectionPulseBackground()
                layoutParams = FrameLayout.LayoutParams(dp(44), dp(38), Gravity.CENTER)
                topConnectionBadgePulseView = this
            })
            addView(FrameLayout(context).apply {
                background = rounded(connectionBadgeBackgroundColor(), 18, connectionBadgeStrokeColor(), 2)
                layoutParams = FrameLayout.LayoutParams(dp(44), dp(38), Gravity.CENTER)
                addView(TextView(context).apply {
                    text = topConnectionBadgeText()
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(connectionBadgeTextColor())
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    topConnectionBadgeView = this
                })
                topConnectionBadgeContainerView = this
            })
        }
    }

    private fun showConnectionPopup() {
        val peers = nearby.connectedPeerNames()
        val message = if (peers.isEmpty()) {
            if (autoConnectEnabled) {
                "연결된 상대가 없습니다.\n현재 참여 가능 인원은 나를 포함해 1명입니다."
            } else {
                "연결된 상대가 없습니다.\n설정에서 자동 연결을 켜면 주변 기기를 찾습니다."
            }
        } else {
            peers.joinToString(separator = "\n") { name -> "• $name" }
        }
        AlertDialog.Builder(this)
            .setTitle(if (autoConnectEnabled) "참여 가능 ${participantReadyCount()}/${NearbyVoteConnectionManager.MAX_CONNECTIONS + 1}명" else "자동 연결 꺼짐")
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

    private fun resultActionCard(title: String, subtitle: String, result: SharedResult, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE0E7DD.toInt())
            setOnClickListener { onClick() }
            layoutParams = blockParams()
            addView(AvatarTileView(resolvedAvatarId(result.proposerId, result.proposerAvatarId)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(AVATAR_CARD_SIZE), dp(AVATAR_CARD_SIZE)).apply {
                    rightMargin = dp(12)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    textSize = 17f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
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

    private fun identityCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            layoutParams = blockParams()
            setOnClickListener { showMyPage() }
            addView(AvatarTileView(selfAvatarId).apply {
                layoutParams = LinearLayout.LayoutParams(dp(AVATAR_CARD_SIZE), dp(AVATAR_CARD_SIZE)).apply {
                    rightMargin = dp(14)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "내 아이디"
                    textSize = 13f
                    setTextColor(0xFF647268.toInt())
                })
                addView(TextView(context).apply {
                    text = selfName
                    textSize = 20f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(3), 0, dp(3))
                })
                addView(TextView(context).apply {
                    text = "결과와 참여자 목록에 표시됩니다."
                    textSize = 13f
                    setTextColor(0xFF526158.toInt())
                })
            })
            addView(TextView(context).apply {
                text = "›"
                textSize = 28f
                setTextColor(0xFF8AA093.toInt())
            })
        }
    }

    private fun avatarInfoCard(title: String, value: String, caption: String, avatarId: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 14)
            layoutParams = blockParams()
            addView(AvatarTileView(avatarId).apply {
                layoutParams = LinearLayout.LayoutParams(dp(AVATAR_CARD_SIZE), dp(AVATAR_CARD_SIZE)).apply {
                    rightMargin = dp(14)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    textSize = 13f
                    setTextColor(0xFF647268.toInt())
                })
                addView(TextView(context).apply {
                    text = value
                    textSize = 20f
                    setTextColor(0xFF10251D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(3), 0, dp(3))
                })
                addView(TextView(context).apply {
                    text = caption
                    textSize = 13f
                    setTextColor(0xFF526158.toInt())
                })
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
        participants: List<Pair<String, Int>> = emptyList()
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

    private fun participantTagBar(participants: List<Pair<String, Int>>): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                participants.forEach { (participant, avatarId) ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(5), dp(4), dp(12), dp(4))
                        background = rounded(0xFFEAF4EF.toInt(), 20, 0xFFC6DED1.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            rightMargin = dp(8)
                        }
                        addView(AvatarTileView(avatarId).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                                rightMargin = dp(5)
                            }
                        })
                        addView(TextView(context).apply {
                            text = participant
                            textSize = 13f
                            setTextColor(0xFF245341.toInt())
                        })
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

    private fun avatarPicker(initialAvatarId: Int, onSelected: (Int) -> Unit): LinearLayout {
        val choices = mutableListOf<AvatarTileView>()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE0E7DD.toInt())
            layoutParams = blockParams()
            repeat(AVATAR_ROW_COUNT) { row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    repeat(AVATAR_COLUMN_COUNT) { column ->
                        val avatarId = row * AVATAR_COLUMN_COUNT + column
                        val tile = AvatarTileView(avatarId, avatarId == initialAvatarId).apply {
                            setOnClickListener {
                                choices.forEach { choice -> choice.setChosen(false) }
                                setChosen(true)
                                onSelected(avatarId)
                            }
                        }
                        choices += tile
                        addView(tile, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                            if (column < AVATAR_COLUMN_COUNT - 1) {
                                rightMargin = dp(5)
                            }
                            if (row < AVATAR_ROW_COUNT - 1) {
                                bottomMargin = dp(5)
                            }
                        })
                    }
                })
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

    private fun mySelectionPrompt(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0xFFE9F4FF.toInt(), 14, 0xFF91BBE5.toInt(), 2)
            layoutParams = blockParams()
            addView(TextView(context).apply {
                text = "나의 참여"
                textSize = 13f
                setTextColor(0xFF356A9E.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "나의 선택은?"
                textSize = 19f
                setTextColor(0xFF153F68.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun durationChoiceGrid(
        durationInput: EditText,
        onPresetSelected: () -> Unit,
        onCustomSelected: () -> Unit
    ): LinearLayout {
        val choices = mutableListOf<Pair<Button, Int?>>()
        fun presetButton(label: String, seconds: Int): Button {
            return compactButton(label, BUTTON_CHOICE) {
                durationInput.setText(seconds.toString())
                onPresetSelected()
                highlightDurationChoice(choices, seconds)
            }.also { choices += it to seconds }
        }
        val customButton = compactButton("+", BUTTON_OUTLINE) {
            onCustomSelected()
            highlightDurationChoice(choices, null)
        }
        val buttons = listOf(
            presetButton("30초", 30),
            presetButton("1분", 60),
            presetButton("5분", 300),
            presetButton("10분", 600),
            presetButton("15분", 900),
            presetButton("30분", 1800),
            customButton.also { choices += it to null }
        )
        val selectedSeconds = durationInput.text.toString().toIntOrNull()
            ?.takeIf { isPresetDuration(it) }
        highlightDurationChoice(choices, selectedSeconds)
        return compactButtonRow(*buttons.toTypedArray())
    }

    private fun highlightDurationChoice(choices: List<Pair<Button, Int?>>, selectedSeconds: Int?) {
        choices.forEach { (button, seconds) ->
            val selected = seconds == selectedSeconds
            button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF176B4D.toInt())
            button.background = if (selected) {
                rounded(0xFF176B4D.toInt(), 12, 0xFF176B4D.toInt())
            } else {
                rounded(0xFFEAF4EF.toInt(), 12, 0xFFB8D8C8.toInt())
            }
        }
    }

    private fun isPresetDuration(durationSeconds: Int): Boolean {
        return durationSeconds in listOf(30, 60, 300, 600, 900, 1800)
    }

    private fun formatDurationText(durationSeconds: Int): String {
        val seconds = durationSeconds.coerceAtLeast(0)
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainingSeconds = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
            hours > 0 -> "${hours}시간"
            minutes > 0 && remainingSeconds > 0 -> "${minutes}분 ${remainingSeconds}초"
            minutes > 0 -> "${minutes}분"
            else -> "${remainingSeconds}초"
        }
    }

    private fun extendedDurationChoices(): List<Pair<Int, String>> {
        val choices = mutableListOf<Pair<Int, String>>()
        (10..50 step 10).forEach { seconds -> choices += seconds to "${seconds}초" }
        (1..10).forEach { minutes -> choices += minutes * 60 to "${minutes}분" }
        (20..50 step 10).forEach { minutes -> choices += minutes * 60 to "${minutes}분" }
        (60..480 step 30).forEach { minutes ->
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            val label = if (remainingMinutes == 0) "${hours}시간" else "${hours}시간 ${remainingMinutes}분"
            choices += minutes * 60 to label
        }
        return choices
    }

    private fun closestDurationChoiceIndex(choices: List<Pair<Int, String>>, durationSeconds: Int): Int {
        return choices.indices.minByOrNull { index -> abs(choices[index].first - durationSeconds) } ?: 0
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

    private fun syncCurrentSessionToPeer(endpointId: String, peerId: String) {
        handler.postDelayed({
            sharedResultsByPoll.values
                .filter { result -> isMyResult(result) && peerId in result.participantIds }
                .forEach { result -> sendResultBlock(result, listOf(endpointId)) }
            val runningPolls = visibleActivePolls().filter { poll -> !poll.hasEnded() }
            if (runningPolls.isNotEmpty()) {
                appendLog("새 연결 기기에 진행 중인 투표 ${runningPolls.size}건을 자동 전달")
                runningPolls.forEach { poll -> sendPoll(poll, endpointId) }
            }
        }, CONNECTION_SYNC_DELAY_MS)
    }

    private fun startNearbyHeartbeat() {
        handler.removeCallbacks(nearbyHeartbeat)
        handler.removeCallbacks(nearbyPulse)
        if (!autoConnectEnabled) return
        animateConnectionSearchPulse()
        nearby.restartNearbyMode()
        handler.postDelayed(nearbyHeartbeat, NEARBY_HEARTBEAT_MS)
        handler.postDelayed(nearbyPulse, NEARBY_PULSE_MS)
    }

    private fun applyAutoConnectSetting() {
        if (autoConnectEnabled) {
            if (hasNearbyPermissions()) {
                startNearbyHeartbeat()
            } else {
                requestNearbyPermissions()
            }
        } else {
            handler.removeCallbacks(nearbyHeartbeat)
            handler.removeCallbacks(nearbyPulse)
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

    private fun updateConnectionStatus(animateBadge: Boolean = false) {
        runOnUiThread {
            if (::connectionStatusView.isInitialized) {
                connectionStatusView.text = connectionBadgeText()
                connectionStatusView.setTextColor(connectionBadgeTextColor())
                connectionStatusView.background = rounded(connectionBadgeBackgroundColor(), 24, connectionBadgeStrokeColor(), 2)
            }
            topConnectionBadgeContainerView?.background =
                rounded(connectionBadgeBackgroundColor(), 18, connectionBadgeStrokeColor(), 2)
            topConnectionBadgePulseView?.let { pulse ->
                pulse.background = connectionPulseBackground()
                if (!autoConnectEnabled) {
                    pulse.animate().cancel()
                    pulse.alpha = 0f
                }
            }
            topConnectionBadgeView?.let { badge ->
                val nextText = topConnectionBadgeText()
                badge.setTextColor(connectionBadgeTextColor())
                if (animateBadge && badge.text.toString() != nextText) {
                    animateConnectionBadge(badge, nextText)
                } else {
                    badge.animate().cancel()
                    badge.translationY = 0f
                    badge.alpha = 1f
                    badge.text = nextText
                }
            }
        }
    }

    private fun animateConnectionSearchPulse() {
        if (!autoConnectEnabled) return
        runOnUiThread {
            topConnectionBadgePulseView?.let { pulse ->
                pulse.animate().cancel()
                pulse.background = connectionPulseBackground()
                pulse.scaleX = 1f
                pulse.scaleY = 1f
                pulse.alpha = 0.84f
                pulse.animate()
                    .scaleX(1.34f)
                    .scaleY(1.3f)
                    .alpha(0.26f)
                    .setDuration(500L)
                    .withEndAction {
                        pulse.animate()
                            .scaleX(1.1f)
                            .scaleY(1.08f)
                            .alpha(0.7f)
                            .setDuration(340L)
                            .withEndAction {
                                pulse.animate()
                                    .scaleX(1.28f)
                                    .scaleY(1.24f)
                                    .alpha(0f)
                                    .setDuration(420L)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun animateConnectionBadge(badge: TextView, nextText: String) {
        badge.animate().cancel()
        badge.translationY = 0f
        badge.alpha = 1f
        badge.animate()
            .translationY(-dp(12).toFloat())
            .alpha(0f)
            .setDuration(130L)
            .withEndAction {
                badge.text = nextText
                badge.translationY = dp(12).toFloat()
                badge.alpha = 0f
                badge.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(150L)
                    .start()
            }
            .start()
    }

    private fun topConnectionBadgeText(): String {
        return when {
            !autoConnectEnabled -> "🚫"
            !hasNearbyPermissions() -> "!"
            else -> participantReadyCount().toString()
        }
    }

    private fun connectionBadgeTextColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFF5F6661.toInt()
            !hasNearbyPermissions() -> 0xFF8B1E1E.toInt()
            connectedCount == 0 -> 0xFF8B1E1E.toInt()
            else -> 0xFF174C8B.toInt()
        }
    }

    private fun connectionBadgeBackgroundColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFFE9ECE9.toInt()
            !hasNearbyPermissions() -> 0xFFFFE8E8.toInt()
            connectedCount == 0 -> 0xFFFFE8E8.toInt()
            else -> 0xFFE7F1FF.toInt()
        }
    }

    private fun connectionBadgeStrokeColor(): Int {
        return when {
            !autoConnectEnabled -> 0xFF9AA39C.toInt()
            !hasNearbyPermissions() -> 0xFFD76A6A.toInt()
            connectedCount == 0 -> 0xFFD76A6A.toInt()
            else -> 0xFF5B91D9.toInt()
        }
    }

    private fun connectionPulseBackground(): GradientDrawable {
        val fillColor = when {
            !autoConnectEnabled -> 0x00000000
            connectedCount == 0 -> 0x22D76A6A
            else -> 0x225B91D9
        }
        return rounded(fillColor, 18, connectionBadgeStrokeColor(), 3)
    }

    private fun connectionBadgeText(): String {
        if (!autoConnectEnabled) {
            return "자동 연결 꺼짐\n설정에서 자동 연결을 켜면 주변 기기를 찾습니다."
        }
        if (!hasNearbyPermissions()) {
            return "권한 필요\n주변 기기 연결 권한을 허용해야 투표를 공유할 수 있습니다."
        }
        if (connectedCount == 0) {
            return "나만 접속 중\n${connectionStatusText()}"
        }
        val peerNames = nearby.connectedPeerNames().takeIf { it.isNotEmpty() }?.joinToString(", ")
        return "참여 가능 ${participantReadyCount()}명\n연결된 상대 ${connectedCount}/${NearbyVoteConnectionManager.MAX_CONNECTIONS}명${peerNames?.let { " · $it" }.orEmpty()}"
    }

    private fun connectionStatusText(): String {
        if (!autoConnectEnabled) {
            return "설정에서 자동 연결을 켜면 앱 실행 후 주변 기기와 자동으로 연결합니다."
        }
        if (!hasNearbyPermissions()) {
            return "주변 기기 검색 권한이 필요합니다. 권한을 허용한 뒤 자동 연결이 시작됩니다."
        }
        return if (connectedCount == 0) {
            "현재 나만 참여 가능합니다 · 약 ${NEARBY_HEARTBEAT_MS / 1000}초마다 주변 연결 상태를 확인합니다."
        } else {
            val peerNames = nearby.connectedPeerNames().takeIf { it.isNotEmpty() }?.joinToString(", ")
            "참여 가능 ${participantReadyCount()}명 · 연결된 상대 ${connectedCount}/${NearbyVoteConnectionManager.MAX_CONNECTIONS}명${peerNames?.let { " · $it" }.orEmpty()}"
        }
    }

    private fun participantReadyCount(): Int {
        return if (autoConnectEnabled && hasNearbyPermissions()) connectedCount + 1 else 0
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

    private fun voteAvatarsFor(pollId: String): LinkedHashMap<String, Int> {
        return receivedVoteAvatarsByPoll.getOrPut(pollId) { linkedMapOf() }
    }

    private fun isMyResult(result: SharedResult): Boolean {
        return result.proposerId == userId
    }

    private fun resultMetadata(vararg details: String): String {
        return details.joinToString(" · ")
    }

    private fun rankedResultOptions(result: SharedResult): List<String> {
        return result.options.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { result.counts[it.value] ?: 0 }.thenBy { it.index })
            .map { it.value }
    }

    private fun winningOptionSummary(result: SharedResult): String? {
        val winningCount = result.counts.values.maxOrNull() ?: return null
        if (winningCount <= 0) {
            return null
        }
        val winningOptions = rankedResultOptions(result).filter { option -> result.counts[option] == winningCount }
        val label = if (winningOptions.size == 1) "1위" else "공동 1위"
        return "$label ${winningOptions.joinToString(", ")}"
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

    private fun persistSessionState() {
        val state = JSONObject()
            .put("activePolls", serializePolls(activePolls))
            .put("incomingPolls", serializePolls(incomingPolls))
            .put("receivedVotes", serializeNestedStrings(receivedVotesByPoll))
            .put("receivedVoteNames", serializeNestedStrings(receivedVoteNamesByPoll))
            .put("receivedVoteAvatars", serializeNestedInts(receivedVoteAvatarsByPoll))
            .put("submittedVotes", JSONObject(submittedVotes as Map<*, *>))
            .put("acceptedPollIds", JSONArray(acceptedPollIds.toList()))
            .put("declinedPollIds", JSONArray(declinedPollIds.toList()))
            .put("sharedResults", JSONArray(sharedResultsByPoll.values.map { it.toHistoryJson() }))
        latestReceipt?.let { state.put("latestReceipt", it.toJson()) }
        store.saveSessionState(state.toString())
    }

    private fun restoreSessionState() {
        val state = runCatching { JSONObject(store.loadSessionState() ?: return) }.getOrNull() ?: return
        restorePolls(state.optJSONObject("activePolls"), activePolls)
        restorePolls(state.optJSONObject("incomingPolls"), incomingPolls)
        restoreNestedStrings(state.optJSONObject("receivedVotes"), receivedVotesByPoll)
        restoreNestedStrings(state.optJSONObject("receivedVoteNames"), receivedVoteNamesByPoll)
        restoreNestedInts(state.optJSONObject("receivedVoteAvatars"), receivedVoteAvatarsByPoll)
        readStringMap(state.optJSONObject("submittedVotes")).forEach { (pollId, option) -> submittedVotes[pollId] = option }
        readStringSet(state.optJSONArray("acceptedPollIds")).forEach { acceptedPollIds += it }
        readStringSet(state.optJSONArray("declinedPollIds")).forEach { declinedPollIds += it }
        val results = state.optJSONArray("sharedResults")
        if (results != null) {
            for (index in 0 until results.length()) {
                runCatching { SharedResult.fromHistoryJson(results.getJSONObject(index)) }.getOrNull()?.let { result ->
                    if (result.isHashValid()) {
                        sharedResultsByPoll[result.pollId] = result
                        sharedResultPollIds += result.pollId
                        sharedResult = result
                    }
                }
            }
        }
        state.optJSONObject("latestReceipt")?.let { latestReceipt = runCatching { VoteReceipt.fromJson(it) }.getOrNull() }
        activePolls.values.forEach { poll ->
            voteEndpointIdsByPoll.putIfAbsent(poll.id, linkedMapOf())
            if (!sharedResultPollIds.contains(poll.id)) {
                scheduleResultShare(poll)
            }
        }
        seenIncomingPollIds += incomingPolls.keys
        seenResultPollIds += sharedResultsByPoll.keys
    }

    private fun serializePolls(polls: Map<String, NearbyPoll>): JSONObject {
        return JSONObject().apply {
            polls.forEach { (pollId, poll) ->
                put(pollId, JSONObject().put("proposerId", poll.proposerId).put("payload", poll.toPayloadJson()))
            }
        }
    }

    private fun restorePolls(raw: JSONObject?, target: LinkedHashMap<String, NearbyPoll>) {
        if (raw == null) return
        raw.keys().forEach { pollId ->
            val stored = raw.optJSONObject(pollId) ?: return@forEach
            val poll = runCatching {
                NearbyPoll.fromPayload(stored.getString("proposerId"), stored.getString("payload"))
            }.getOrNull() ?: return@forEach
            if (!poll.hasEnded() || target === activePolls) {
                target[poll.id] = poll
            }
        }
    }

    private fun serializeNestedStrings(values: Map<String, LinkedHashMap<String, String>>): JSONObject {
        return JSONObject().apply { values.forEach { (key, map) -> put(key, JSONObject(map as Map<*, *>)) } }
    }

    private fun serializeNestedInts(values: Map<String, LinkedHashMap<String, Int>>): JSONObject {
        return JSONObject().apply { values.forEach { (key, map) -> put(key, JSONObject(map as Map<*, *>)) } }
    }

    private fun restoreNestedStrings(raw: JSONObject?, target: LinkedHashMap<String, LinkedHashMap<String, String>>) {
        if (raw == null) return
        raw.keys().forEach { key -> target[key] = LinkedHashMap(readStringMap(raw.optJSONObject(key))) }
    }

    private fun restoreNestedInts(raw: JSONObject?, target: LinkedHashMap<String, LinkedHashMap<String, Int>>) {
        if (raw == null) return
        raw.keys().forEach { key ->
            val source = raw.optJSONObject(key) ?: return@forEach
            target[key] = linkedMapOf<String, Int>().apply {
                source.keys().forEach { nestedKey -> put(nestedKey, source.optInt(nestedKey)) }
            }
        }
    }

    private fun readStringMap(raw: JSONObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        return raw.keys().asSequence().associateWith { key -> raw.optString(key) }
    }

    private fun readStringSet(raw: JSONArray?): Set<String> {
        if (raw == null) return emptySet()
        return (0 until raw.length()).map { index -> raw.getString(index) }.toSet()
    }

    private fun saveIdentity(nextIdentity: String, avatarId: Int) {
        store.saveIdentity(nextIdentity)
        store.saveAvatarId(avatarId)
        selfName = nextIdentity
        selfAvatarId = avatarId
        reconnectForIdentityUpdate()
    }

    private fun reconnectForIdentityUpdate() {
        handler.removeCallbacks(nearbyHeartbeat)
        handler.removeCallbacks(nearbyPulse)
        nearby.stop()
        nearby = NearbyVoteConnectionManager(this, selfName, this)
        simulator = LocalVoteSimulator(selfName)
        connectedCount = 0
        activePolls.replaceAll { _, poll -> poll.copy(proposerName = selfName, proposerAvatarId = selfAvatarId) }
        persistSessionState()
        applyAutoConnectSetting()
    }

    private fun suggestIdentity(): String {
        val adjectives = listOf("따뜻한", "빠른", "조용한", "선명한", "든든한", "가벼운", "밝은", "차분한")
        val objects = listOf("머그컵", "가방", "연필", "나침반", "우산", "노트", "램프", "시계")
        val seed = abs((Build.MODEL + System.currentTimeMillis()).hashCode())
        return adjectives[seed % adjectives.size] + objects[(seed / adjectives.size) % objects.size]
    }

    private fun avatarIdForUser(id: String): Int {
        return Math.floorMod(id.hashCode(), AVATAR_COUNT)
    }

    private fun resolvedAvatarId(id: String, avatarId: Int): Int {
        return if (avatarId in 0 until AVATAR_COUNT) avatarId else avatarIdForUser(id)
    }

    private fun publishPoll(question: String, options: List<String>, durationSeconds: Int, allowParticipantOptions: Boolean, revealSelections: Boolean) {
        val durationMinutes = ((durationSeconds + 59) / 60).coerceAtLeast(1)
        val poll = NearbyPoll(
            id = "poll-${UUID.randomUUID()}",
            proposerId = userId,
            proposerName = selfName,
            proposerAvatarId = selfAvatarId,
            question = question,
            options = options,
            durationMinutes = durationMinutes,
            durationSeconds = durationSeconds,
            endAtMillis = System.currentTimeMillis() + durationSeconds * 1_000L,
            allowParticipantOptions = allowParticipantOptions,
            revealSelections = revealSelections
        )
        activePolls[poll.id] = poll
        receivedVotesByPoll[poll.id] = linkedMapOf()
        receivedVoteNamesByPoll[poll.id] = linkedMapOf()
        receivedVoteAvatarsByPoll[poll.id] = linkedMapOf()
        voteEndpointIdsByPoll[poll.id] = linkedMapOf()
        sharedResult = null
        sharedResultPollIds -= poll.id
        seenResultPollIds -= poll.id
        startNearbyConnectionTest()
        sendPoll(poll)
        scheduleResultShare(poll)
        persistSessionState()
        showPublishedPoll(poll)
    }

    private fun confirmPublishingWithoutPeers(onConfirm: () -> Unit) {
        val connectionHint = if (autoConnectEnabled) {
            "새로 연결되는 기기에는 진행 중인 투표가 자동 전달됩니다."
        } else {
            "자동 연결이 꺼져 있어, 먼저 연결을 켜는 편이 좋습니다."
        }
        AlertDialog.Builder(this)
            .setTitle("접속자 없이 게시할까요?")
            .setMessage("현재 연결된 접속자가 없습니다.\n투표 제한시간은 게시하는 즉시 시작됩니다.\n\n$connectionHint")
            .setPositiveButton("그래도 게시") { _, _ -> onConfirm() }
            .setNegativeButton("기다리기", null)
            .show()
    }

    private fun sendPoll(poll: NearbyPoll, endpointId: String? = null) {
        val message = NearVoteMessage(
            type = NearVoteMessageType.POLL,
            senderId = userId,
            payloadJson = poll.toPayloadJson()
        ).toJson()
        if (endpointId == null) {
            nearby.sendToAll(message)
        } else {
            nearby.sendTo(endpointId, message)
        }
    }

    private fun normalizedOption(option: String): String {
        return option.trim().replace(Regex("\\s+"), " ")
    }

    private fun addSuggestedOptionIfNeeded(poll: NearbyPoll, option: String): NearbyPoll {
        if (option in poll.options) return poll
        val updatedPoll = poll.copy(options = poll.options + option)
        activePolls[poll.id] = updatedPoll
        sendPoll(updatedPoll)
        persistSessionState()
        appendLog("참여자 선택지 추가: $option")
        return updatedPoll
    }

    private fun castVote(poll: NearbyPoll, option: String) {
        val submittedOption = normalizedOption(option)
        val isPublishedByMe = activePolls.containsKey(poll.id)
        val receivedVotes = votesFor(poll.id)
        val receivedVoteNames = voteNamesFor(poll.id)
        val receivedVoteAvatars = voteAvatarsFor(poll.id)
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
        if (submittedOption.isBlank() || submittedOption.length > MAX_OPTION_LENGTH) {
            Toast.makeText(this, "선택지를 확인해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val newOption = submittedOption !in poll.options
        if (newOption && !poll.allowParticipantOptions) {
            Toast.makeText(this, "새 선택지를 추가할 수 없는 투표입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (newOption && poll.options.size >= MAX_POLL_OPTION_COUNT) {
            Toast.makeText(this, "선택지는 최대 ${MAX_POLL_OPTION_COUNT}개까지 추가할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        nearby.sendToAll(
            NearVoteMessage(
                type = NearVoteMessageType.VOTE,
                senderId = userId,
                payloadJson = JSONObject()
                    .put("pollId", poll.id)
                    .put("option", submittedOption)
                    .put("voterId", userId)
                    .put("voterName", selfName)
                    .put("voterAvatarId", selfAvatarId)
                    .toString()
            ).toJson()
        )
        if (isPublishedByMe) {
            val updatedPoll = addSuggestedOptionIfNeeded(poll, submittedOption)
            receivedVotes[userId] = submittedOption
            receivedVoteNames[userId] = selfName
            receivedVoteAvatars[userId] = selfAvatarId
            saveLocalReceipt(updatedPoll, submittedOption)
            persistSessionState()
            showPublishedPoll(updatedPoll)
        } else {
            submittedVotes[poll.id] = submittedOption
            persistSessionState()
            showVoteSubmitted(poll, submittedOption)
        }
    }

    private fun handleNearbyMessage(endpointId: String, rawMessage: String) {
        val message = runCatching { NearVoteMessage.fromJson(rawMessage) }.getOrElse {
            appendLog("알 수 없는 메시지 형식")
            return
        }
        when (message.type) {
            NearVoteMessageType.PROFILE -> {
                val profile = runCatching { JSONObject(message.payloadJson) }.getOrElse { return }
                val peerId = profile.optString("userId", message.senderId)
                val peerName = profile.optString("name", peerId.take(8))
                if (peerId != message.senderId) {
                    appendLog("발신자와 다른 사용자 ID의 프로필은 무시함")
                    return
                }
                nearby.identifyPeer(endpointId, peerId, peerName)
                syncCurrentSessionToPeer(endpointId, peerId)
                updateConnectionStatus()
            }
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
                persistSessionState()
                if (alreadyKnown || submittedVotes.containsKey(poll.id)) {
                    appendLog("이미 받은 투표 갱신: ${poll.question}")
                    return
                }
                runOnUiThread { showPollInvitation(poll) }
            }
            NearVoteMessageType.VOTE -> {
                val payload = JSONObject(message.payloadJson)
                var poll = activePolls[payload.optString("pollId")] ?: return
                val receivedVotes = votesFor(poll.id)
                val receivedVoteNames = voteNamesFor(poll.id)
                val receivedVoteAvatars = voteAvatarsFor(poll.id)
                val voterId = payload.optString("voterId", message.senderId)
                val voterName = payload.optString("voterName", voterId.take(8))
                val voterAvatarId = payload.optInt("voterAvatarId", avatarIdForUser(voterId))
                val option = normalizedOption(payload.optString("option"))
                if (option.isBlank()) return
                if (voterId != message.senderId) {
                    appendLog("발신자와 다른 사용자 ID의 표는 무시함")
                    return
                }
                nearby.identifyPeer(endpointId, voterId, voterName)
                if (poll.hasEnded()) {
                    appendLog("종료된 투표의 표는 무시함: $voterId")
                    return
                }
                if (receivedVotes.containsKey(voterId)) {
                    appendLog("중복 투표 무시: $voterId")
                    sendReceipt(endpointId, poll, voterId, voterName, receivedVotes.getValue(voterId))
                    return
                }
                val newOption = option !in poll.options
                if (newOption && (!poll.allowParticipantOptions || option.length > MAX_OPTION_LENGTH || poll.options.size >= MAX_POLL_OPTION_COUNT)) {
                    appendLog("허용되지 않은 새 선택지 무시: $option")
                    return
                }
                if (newOption) {
                    poll = addSuggestedOptionIfNeeded(poll, option)
                }
                receivedVotes[voterId] = option
                receivedVoteNames[voterId] = voterName
                receivedVoteAvatars[voterId] = voterAvatarId
                voteEndpointIdsByPoll.getOrPut(poll.id) { linkedMapOf() }[voterId] = endpointId
                sendReceipt(endpointId, poll, voterId, voterName, option)
                persistSessionState()
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
                    val submitted = submittedVotes[receipt.pollId] ?: return
                    if (receipt.voteHash != hash("${receipt.pollId}:$userId:$submitted")) {
                        appendLog("유효하지 않은 영수증은 무시함")
                        return
                    }
                    latestReceipt = receipt
                    store.saveReceipt(receipt)
                    persistSessionState()
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
                if (!result.isHashValid()) {
                    appendLog("무결성 확인에 실패한 결과는 저장하지 않음: ${result.question}")
                    return
                }
                val alreadyKnown = !seenResultPollIds.add(result.pollId)
                sharedResult = result
                sharedResultsByPoll[result.pollId] = result
                incomingPolls.remove(result.pollId)
                store.saveResult(result)
                persistSessionState()
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

    private fun sendProfile(endpointId: String) {
        nearby.sendTo(
            endpointId,
            NearVoteMessage(
                type = NearVoteMessageType.PROFILE,
                senderId = userId,
                payloadJson = JSONObject()
                    .put("userId", userId)
                    .put("name", selfName)
                    .put("avatarId", selfAvatarId)
                    .toString()
            ).toJson()
        )
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
        val receivedVoteAvatars = voteAvatarsFor(poll.id)
        val counts = poll.options.associateWith { option ->
            receivedVotes.values.count { it == option }
        }
        val participantIds = receivedVotes.keys.toList()
        val participantNames = participantIds.map { id -> receivedVoteNames[id] ?: id.take(8) }
        val participantAvatarIds = participantIds.associateWith { id ->
            receivedVoteAvatars[id] ?: avatarIdForUser(id)
        }
        val participantSelections = if (poll.revealSelections) {
            participantIds.associateWith { id -> receivedVotes.getValue(id) }
        } else {
            emptyMap()
        }
        val result = SharedResult(
            pollId = poll.id,
            proposerId = userId,
            proposerName = selfName,
            proposerAvatarId = selfAvatarId,
            question = poll.question,
            options = poll.options,
            counts = counts,
            participantIds = participantIds,
            participantNames = participantNames,
            participantAvatarIds = participantAvatarIds,
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
            ),
            durationSeconds = poll.durationSeconds,
            allowParticipantOptions = poll.allowParticipantOptions,
            revealSelections = poll.revealSelections
        )
        sharedResult = result
        sharedResultsByPoll[poll.id] = result
        sharedResultPollIds += poll.id
        store.saveResult(result)
        persistSessionState()
        val participantEndpoints = participantIds.mapNotNull { participantId ->
            voteEndpointIdsByPoll[poll.id]?.get(participantId) ?: nearby.endpointForPeer(participantId)
        }.distinct()
        sendResultBlock(result, participantEndpoints)
        showSharedResult(result)
    }

    private fun endPollAndShareResult(poll: NearbyPoll) {
        val endedPoll = poll.copy(endAtMillis = System.currentTimeMillis())
        activePolls[poll.id] = endedPoll
        persistSessionState()
        shareResultBlock(endedPoll)
    }

    private fun sendResultBlock(result: SharedResult, endpointIds: List<String>) {
        appendLog("결과 블록 전송")
        val message = NearVoteMessage(
            type = NearVoteMessageType.RESULT_BLOCK,
            senderId = userId,
            payloadJson = result.toPayloadJson()
        ).toJson()
        endpointIds.forEach { endpointId -> nearby.sendTo(endpointId, message) }
    }

    private fun scheduleResultShare(poll: NearbyPoll) {
        val delay = (poll.endAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
        handler.postDelayed({
            if (activePolls.containsKey(poll.id) && !sharedResultPollIds.contains(poll.id)) {
                shareResultBlock(activePolls.getValue(poll.id))
            }
        }, delay)
    }

    private fun requestNearbyPermissions() {
        permissionLauncher.launch(nearbyPermissions())
    }

    private fun hasNearbyPermissions(): Boolean {
        return nearbyPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun nearbyPermissions(): Array<String> {
        return buildList {
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

    private enum class NavigationIcon {
        HOME,
        CREATE,
        RESULTS,
        SETTINGS
    }

    private enum class ResultAction {
        REVOTE,
        SAVE_TEMPLATE
    }

    private inner class ResultActionIconView(
        private val action: ResultAction,
        private val tint: Int
    ) : View(this) {
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF8E4.toInt()
            style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width / 38f
            val sy = height / 38f
            fun x(value: Float) = value * sx
            fun y(value: Float) = value * sy

            canvas.drawCircle(x(19f), y(19f), x(18f), innerPaint)

            when (action) {
                ResultAction.REVOTE -> {
                    val arrow = Path().apply {
                        moveTo(x(23f), y(12f))
                        cubicTo(x(14f), y(9f), x(9f), y(15f), x(10f), y(21f))
                        cubicTo(x(11f), y(28f), x(19f), y(31f), x(25f), y(27f))
                    }
                    canvas.drawPath(arrow, iconPaint)
                    val head = Path().apply {
                        moveTo(x(22f), y(11f))
                        lineTo(x(27f), y(13f))
                        lineTo(x(23f), y(17f))
                    }
                    canvas.drawPath(head, iconPaint)
                }
                ResultAction.SAVE_TEMPLATE -> {
                    val bookmark = Path().apply {
                        moveTo(x(13f), y(9f))
                        lineTo(x(25f), y(9f))
                        lineTo(x(25f), y(29f))
                        lineTo(x(19f), y(24f))
                        lineTo(x(13f), y(29f))
                        close()
                    }
                    canvas.drawPath(bookmark, iconPaint)
                }
            }
        }
    }

    private inner class NavigationThumbnailView(
        private val icon: NavigationIcon,
        private val selected: Boolean
    ) : View(this) {
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) 0xFFE5F2EB.toInt() else 0xFFF0F3F0.toInt()
            style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) 0xFF176B4D.toInt() else 0xFF526158.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width / 34f
            val sy = height / 32f
            fun x(value: Float) = value * sx
            fun y(value: Float) = value * sy

            canvas.drawRoundRect(
                RectF(x(2f), y(1f), x(32f), y(31f)),
                dp(9).toFloat(),
                dp(9).toFloat(),
                tilePaint
            )
            when (icon) {
                NavigationIcon.HOME -> {
                    val roof = Path().apply {
                        moveTo(x(9f), y(16f))
                        lineTo(x(17f), y(9f))
                        lineTo(x(25f), y(16f))
                    }
                    canvas.drawPath(roof, iconPaint)
                    canvas.drawLine(x(11f), y(15f), x(11f), y(23f), iconPaint)
                    canvas.drawLine(x(23f), y(15f), x(23f), y(23f), iconPaint)
                    canvas.drawLine(x(11f), y(23f), x(23f), y(23f), iconPaint)
                    canvas.drawLine(x(17f), y(19f), x(17f), y(23f), iconPaint)
                }
                NavigationIcon.CREATE -> {
                    canvas.drawRoundRect(RectF(x(10f), y(7f), x(24f), y(25f)), x(2f), y(2f), iconPaint)
                    canvas.drawLine(x(17f), y(12f), x(17f), y(20f), iconPaint)
                    canvas.drawLine(x(13f), y(16f), x(21f), y(16f), iconPaint)
                }
                NavigationIcon.RESULTS -> {
                    canvas.drawLine(x(10f), y(23f), x(25f), y(23f), iconPaint)
                    canvas.drawLine(x(12f), y(23f), x(12f), y(17f), iconPaint)
                    canvas.drawLine(x(17f), y(23f), x(17f), y(11f), iconPaint)
                    canvas.drawLine(x(22f), y(23f), x(22f), y(14f), iconPaint)
                }
                NavigationIcon.SETTINGS -> {
                    canvas.drawLine(x(10f), y(12f), x(24f), y(12f), iconPaint)
                    canvas.drawLine(x(10f), y(20f), x(24f), y(20f), iconPaint)
                    canvas.drawCircle(x(15f), y(12f), x(2f), tilePaint)
                    canvas.drawCircle(x(15f), y(12f), x(2f), iconPaint)
                    canvas.drawCircle(x(20f), y(20f), x(2f), tilePaint)
                    canvas.drawCircle(x(20f), y(20f), x(2f), iconPaint)
                }
            }
        }
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
            timePaint.textSize = dp(if (compact) 11 else 17).toFloat()

            val size = width.coerceAtMost(height).toFloat()
            val inset = stroke / 2f + dp(4)
            val bounds = RectF(inset, inset, size - inset, size - inset)
            val totalMillis = (poll.durationSeconds * 1_000L).coerceAtLeast(1L)
            val remainingMillis = (poll.endAtMillis - System.currentTimeMillis()).coerceIn(0L, totalMillis)
            val ratio = remainingMillis.toFloat() / totalMillis.toFloat()
            val isUrgent = remainingMillis <= URGENT_COUNTDOWN_MS

            progressPaint.color = if (isUrgent) 0xFFD33737.toInt() else 0xFF176B4D.toInt()
            timePaint.color = if (isUrgent) 0xFFC82424.toInt() else 0xFF10251D.toInt()

            canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
            canvas.drawArc(bounds, -90f, 360f * ratio, false, progressPaint)
            canvas.drawText(formatRemaining(remainingMillis), width / 2f, height / 2f + dp(5), timePaint)

            if (remainingMillis > 0L) {
                postInvalidateDelayed(1_000L)
            }
        }
    }

    private inner class AvatarTileView(
        private val avatarId: Int,
        chosen: Boolean = false
    ) : View(this) {
        private var isChosen = chosen
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF176B4D.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }

        fun setChosen(chosen: Boolean) {
            isChosen = chosen
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val normalizedId = avatarId.coerceIn(0, AVATAR_COUNT - 1)
            val sourceWidth = avatarSheet.width / AVATAR_COLUMN_COUNT
            val sourceHeight = avatarSheet.height / AVATAR_ROW_COUNT
            val sourceColumn = normalizedId % AVATAR_COLUMN_COUNT
            val sourceRow = normalizedId / AVATAR_COLUMN_COUNT
            val source = Rect(
                sourceColumn * sourceWidth,
                sourceRow * sourceHeight,
                (sourceColumn + 1) * sourceWidth,
                (sourceRow + 1) * sourceHeight
            )
            val inset = dp(6).toFloat()
            val edge = width.coerceAtMost(height).toFloat() - inset * 2
            val left = (width - edge) / 2f
            val top = (height - edge) / 2f
            val target = RectF(left, top, left + edge, top + edge)
            canvas.drawBitmap(avatarSheet, source, target, tilePaint)
            if (isChosen) {
                canvas.drawRoundRect(target, dp(12).toFloat(), dp(12).toFloat(), selectionPaint)
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
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else if (minutes > 0) {
            "%d:%02d".format(minutes, seconds)
        } else {
            "$seconds"
        }
    }

    companion object {
        private const val NEARBY_HEARTBEAT_MS = 10_000L
        private const val NEARBY_PULSE_MS = 30_000L
        private const val CONNECTION_SYNC_DELAY_MS = 500L
        private const val KEYBOARD_SCROLL_DELAY_MS = 180L
        private const val URGENT_COUNTDOWN_MS = 10_000L
        private const val AVATAR_COLUMN_COUNT = 5
        private const val AVATAR_ROW_COUNT = 4
        private const val AVATAR_COUNT = AVATAR_COLUMN_COUNT * AVATAR_ROW_COUNT
        private const val AVATAR_CARD_SIZE = 56
        private const val CUSTOM_DURATION_MIN_SECONDS = 10
        private const val CUSTOM_DURATION_MAX_SECONDS = 8 * 60 * 60
        private const val MAX_OPTION_LENGTH = 30
        private const val MAX_POLL_OPTION_COUNT = 20
        private const val BUTTON_PRIMARY = 1
        private const val BUTTON_OUTLINE = 2
        private const val BUTTON_QUIET = 3
        private const val BUTTON_CHOICE = 4
    }
}
