package com.example.animastor.ui

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.animastor.R
import com.example.animastor.databinding.FragmentAiAssistantBinding
import com.example.animastor.repository.AiChatDone
import com.example.animastor.repository.AiChatError
import com.example.animastor.repository.AiChatRequest
import com.example.animastor.repository.AiMessage
import com.example.animastor.repository.BookData
import com.example.animastor.repository.ChatSessionApi
import com.example.animastor.repository.ResourceInvalidations
import com.example.animastor.repository.ToolCallResult
import com.example.animastor.repository.resolveLanguage
import com.example.animastor.repository.AiChatStream
import com.example.animastor.repository.AiChatStream.sourceBadgeKey
import com.example.animastor.repository.AiChatStream.streamErrorKey
import com.example.animastor.repository.unitIndex
import com.example.animastor.network.AiChatSseClient
import com.example.animastor.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AiAssistantFragment : Fragment(R.layout.fragment_ai_assistant) {

    companion object {
        private const val ARG_BOOK_ID = "book_id"
        private const val ARG_CREATE_MODE = "create_mode"
        private const val TAG = "AiAssistantFragment"
        private const val REQ_CODE_MIC_PERMISSION = 1001

        fun newInstance(bookId: String? = null, createMode: Boolean = false): AiAssistantFragment {
            return AiAssistantFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_ID, bookId)
                    putBoolean(ARG_CREATE_MODE, createMode)
                }
            }
        }
    }

    private val generateViewModel: GenerateViewModel by activityViewModels()
    private var argBookId: String? = null
    private var argCreateMode: Boolean = false

    private var binding: FragmentAiAssistantBinding? = null
    private val adapter = ChatAdapter()
    private val messages = mutableListOf<ChatMessage>()
    private val apiMessages = mutableListOf<AiMessage>()

    private val sessions = mutableListOf<ChatSessionApi>()
    private var currentSessionId: String? = null
    private var currentMode = AssistantMode.default()
    private var sessionLoadJob: Job? = null
    private var welcomeJob: Job? = null
    private var pendingSessionId: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceInputClickCount = 0
    private var isRecognizing = false
    private var isListening = false
    private var typingAnimJob: Job? = null
    private var bookData: BookData? = null
    private var bookDataLoadAttempted = false

    // ── AI chat streaming (LLM Sharing Phase 3, web parity AiAssistantPage) ──
    private val aiChatStream by lazy { AiChatSseClient(RetrofitClient.streamHttpClient) }
    private var aiSending = false
    private var streamedText = ""
    private var currentSource: String? = null
    private var assistantMsgIndex = -1
    private var streamSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        argBookId = arguments?.getString(ARG_BOOK_ID)
        argCreateMode = arguments?.getBoolean(ARG_CREATE_MODE, false) ?: false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentAiAssistantBinding.bind(view)
        val b = binding ?: return
        barBinding = b.positionBar

        b.messageList.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        b.messageList.adapter = adapter

        b.micButton.setOnClickListener { startVoiceInput() }
        b.sendButton.setOnClickListener {
            if (aiSending) stopAiStream() else sendAiMessage()
        }
        b.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendAiMessage()
                true
            } else false
        }
        b.newChatButton.setOnClickListener { startNewSession() }
        b.backButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.sessionListButton.setOnClickListener { showSessionListDialog() }

        // Build mode chips
        buildModeChips()

        if (argCreateMode && generateViewModel.bookId.isBlank()) {
            messages.clear()
            apiMessages.clear()
            val msg = ChatMessage(text = getString(R.string.ai_creation_welcome), isUser = false)
            messages.add(msg)
            adapter.submitList(messages.toList())
            binding?.messageList?.post {
                binding?.messageList?.smoothScrollToPosition(messages.size - 1)
            }
        } else {
            // Snapshot current book state before AI edits
            generateViewModel.snapshotCurrentBook()
            // Load sessions from backend
            loadSessionsFromBackend()
        }

        setupSpeechRecognizer()
        observePosition()
        loadBookDataAsync()
    }

    private fun themeColor(attrRes: Int): Int {
        return MaterialColors.getColor(requireContext(), attrRes, 0)
    }

    private fun buildModeChips() {
        val container = binding?.modeContainer ?: return
        container.removeAllViews()
        val ctx = container.context
        val modeIcons = mapOf(
            AssistantMode.CONVERSATION to R.drawable.ic_sparkle_outline,
            AssistantMode.IMPORT to R.drawable.ic_download,
            AssistantMode.EDIT to R.drawable.ic_edit,
            AssistantMode.DIRECTOR to R.drawable.ic_map,
            AssistantMode.EXTRACTION to R.drawable.ic_file,
            AssistantMode.VALIDATION to R.drawable.ic_check,
        )
        val activeBg = themeColor(com.google.android.material.R.attr.colorPrimary)
        val activeFg = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        val inactiveBg = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val inactiveFg = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        AssistantMode.ALL.forEach { mode ->
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_mode_chip, container, false) as MaterialButton
            chip.text = getString(mode.titleRes)
            chip.setIconResource(modeIcons[mode] ?: R.drawable.ic_sparkle_outline)
            val isActive = mode == currentMode
            chip.backgroundTintList = ColorStateList.valueOf(if (isActive) activeBg else inactiveBg)
            chip.setTextColor(if (isActive) activeFg else inactiveFg)
            chip.iconTint = ColorStateList.valueOf(if (isActive) activeFg else inactiveFg)
            if (mode.soon) {
                // Future feature: visible but dimmed + non-interactive —
                // must never send a request. Parity: web chip--mode-soon.
                chip.isEnabled = false
                chip.alpha = 0.45f
                chip.text = getString(mode.titleRes) + " · " + getString(R.string.ai_mode_soon)
                chip.setOnClickListener(null)
            } else {
                chip.setOnClickListener {
                    switchMode(mode)
                }
            }
            container.addView(chip)
        }
    }

    private fun startNewSession() {
        sessionLoadJob?.cancel()
        welcomeJob?.cancel()
        messages.clear()
        apiMessages.clear()
        currentMode = AssistantMode.default()
        currentSessionId = null
        pendingSessionId = null
        buildModeChips()
        addWelcomeMessage()
        val bookId = generateViewModel.bookId.takeIf { it.isNotBlank() }
            ?: argBookId?.takeIf { it.isNotBlank() }
        if (bookId != null) {
            pendingSessionId = "__creating__"
            lifecycleScope.launch {
                runCatching {
                    val session = generateViewModel.repository.createSession(
                        bookId = bookId,
                        title = getString(R.string.ai_new_chat),
                        topicId = "book",
                        mode = currentMode.id
                    )
                    currentSessionId = session.sessionId
                    pendingSessionId = null
                    sessions.add(0, session)
                }.onFailure {
                    Log.e(TAG, "Failed to create session", it)
                    pendingSessionId = null
                }
            }
        }
    }

    private fun observePosition() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SharedPositionManager.current.collect { pos ->
                    updateContextBar(pos)
                }
            }
        }
    }

    private var barBinding: com.example.animastor.databinding.IncludePositionBarBinding? = null

    private fun positionLabel(): TextView? = barBinding?.positionLabel

    private fun loadBookDataAsync() {
        val bookId = generateViewModel.bookId.takeIf { it.isNotBlank() } ?: return
        bookDataLoadAttempted = true
        lifecycleScope.launch {
            // Reload recovery layer: bounded backoff + connectivity fast path.
            // On final failure the previous snapshot (if any) stays.
            val result = generateViewModel.resilientReloader.reload {
                generateViewModel.repository.getBook(bookId)
            }
            if (result is com.example.animastor.repository.ResilientReloader.Result.Success) {
                bookData = result.value
            }
            updateContextBar(SharedPositionManager.current.value)
        }
    }

    private fun updateContextBar(pos: ActivePosition) {
        if (binding == null) return
        if (pos.chapterId != null && bookData == null && generateViewModel.bookId.isNotBlank() && !bookDataLoadAttempted) {
            loadBookDataAsync()
            positionLabel()?.text = ""
            return
        }
        if (pos.chapterId != null && bookData != null) {
            val ch = bookData?.chapters?.firstOrNull { it.chapter_id == pos.chapterId }
            val sc = ch?.scenes?.firstOrNull { it.scene_id == pos.sceneId }
            val scIdx = sc?.display_index ?: 0
            val isSpecial = ch?.is_special == true
                val uIdx = bookData?.unitIndex(pos.chapterId, pos.sceneId, pos.unitIndex) ?: 0
                val chTitle = ch?.chapter_title?.takeIf { it.isNotBlank() }
                val scTitle = sc?.scene_title?.takeIf { it.isNotBlank() }
                val chLabel = if (isSpecial) {
                    chTitle ?: (ch?.type?.replaceFirstChar { it.uppercase() } ?: "")
                } else if (chTitle != null) {
                    chTitle
                } else if (ch?.display_number != null) {
                    "${getString(R.string.navigate_chapter)} ${ch.display_number}"
                } else {
                    ""
                }
                val scLabel = if (scIdx > 0) "${getString(R.string.navigate_scene)} $scIdx" else ""
                val unitLabel = if (uIdx > 0) "${getString(R.string.navigate_unit)} $uIdx" else ""
                if (chLabel.isEmpty() && scLabel.isEmpty()) {
                    positionLabel()?.text = getString(R.string.navigate_no_position)
                    return
                }
                val fullLabel = if (isSpecial) {
                    if (scTitle != null) "$chLabel / $scLabel — $scTitle / $unitLabel"
                    else "$chLabel / $scLabel / $unitLabel"
                } else if (chTitle != null && scTitle != null) {
                    // chTitle already in chLabel, use chLabel directly
                    "$chLabel / $scLabel — $scTitle / $unitLabel"
                } else if (scTitle != null) {
                    "$chLabel / $scLabel — $scTitle / $unitLabel"
                } else {
                    "$chLabel / $scLabel / $unitLabel"
                }
                positionLabel()?.text = fullLabel
                return
        }
        positionLabel()?.text = getString(R.string.navigate_no_position)
    }

    private fun switchMode(mode: AssistantMode) {
        if (mode.soon) return // future feature — chip disabled, never sends a request
        if (currentMode == mode) return
        currentMode = mode
        buildModeChips()

        val text = getString(R.string.ai_mode_switch, getString(mode.titleRes), getString(mode.descriptionRes))
        val modeMsg = ChatMessage(text = text, isUser = false)
        messages.add(modeMsg)
        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (isListening) {
                        isListening = false
                        isRecognizing = false
                        activity?.runOnUiThread {
                            binding?.micButton?.setImageResource(R.drawable.ic_mic)
                            binding?.inputField?.setFocusable(true)
                            binding?.inputField?.setFocusableInTouchMode(true)
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        activity?.runOnUiThread {
                            binding?.inputField?.setText(matches[0])
                        }
                    }
                    stopListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun loadSessionsFromBackend() {
        val bookId = generateViewModel.bookId.takeIf { it.isNotBlank() }
            ?: argBookId?.takeIf { it.isNotBlank() }
        if (bookId == null) {
            startNewSession()
            return
        }
        lifecycleScope.launch {
            val loaded = runCatching { generateViewModel.repository.listSessions(bookId) }
                .onFailure { Log.e(TAG, "Failed to load sessions", it) }
                .getOrDefault(emptyList())
            sessions.clear()
            sessions.addAll(loaded)
            val lastSession = sessions.firstOrNull()
            if (lastSession != null) {
                restoreSessionFromApi(lastSession)
            } else {
                startNewSession()
            }
        }
    }

    private fun restoreSessionFromApi(session: ChatSessionApi) {
        sessionLoadJob?.cancel()
        welcomeJob?.cancel()
        currentSessionId = session.sessionId
        messages.clear()
        apiMessages.clear()
        adapter.submitList(messages.toList())
        sessionLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded || binding == null) return@launch
            val msgs = runCatching {
                generateViewModel.repository.getSessionMessages(session.sessionId)
            }.onFailure { e ->
                Log.e(TAG, "Failed to load session messages", e)
            }.getOrDefault(emptyList())
            for (m in msgs) {
                val chatMsg = ChatMessage(text = m.message, isUser = m.role == "user")
                messages.add(chatMsg)
                apiMessages.add(AiMessage(role = m.role, content = m.message))
            }
            // Add position context after loading history
            addContextualPosition()
            if (isAdded) {
                adapter.submitList(messages.toList())
                scrollToBottom()
            }
        }
    }

    /** Add a system message with current position context. */
    private suspend fun addContextualPosition() {
        val pos = SharedPositionManager.current.value
        if (pos.chapterId == null) return
        val bid = generateViewModel.bookId.takeIf { it.isNotBlank() }
            ?: argBookId?.takeIf { it.isNotBlank() } ?: return
        val bd = runCatching { generateViewModel.repository.getBook(bid) }.getOrNull() ?: return
        val ch = bd.chapters?.firstOrNull { it.chapter_id == pos.chapterId }
        val sc = ch?.scenes?.firstOrNull { it.scene_id == pos.sceneId }
        val scIdx = sc?.display_index ?: 0
        val isSpecial = ch?.is_special == true
                val chName = if (isSpecial) {
                    ch?.chapter_title?.takeIf { it.isNotBlank() } ?: ch?.type?.replaceFirstChar { it.uppercase() }
                } else if (ch?.chapter_title != null) {
                    ch.chapter_title
                } else if (ch?.display_number != null) {
                    "${getString(R.string.navigate_chapter)} ${ch.display_number}"
                } else {
                    pos.chapterId
                }
        val scName = if (scIdx > 0) {
            val titleSuffix = if (sc?.scene_title != null) " — ${sc.scene_title}" else ""
            "${getString(R.string.navigate_scene)} $scIdx$titleSuffix"
        } else {
            pos.sceneId ?: "?"
        }
        val unit = sc?.units?.firstOrNull { it.id == pos.unitId }
        val unitDesc = if (unit != null) {
            val typeLabel = unit.type?.replaceFirstChar { it.uppercase() } ?: "Unit"
            val textSnippet = unit.text?.take(60)?.let { t -> if (t.isNotBlank()) " — \"$t\"" else "" } ?: ""
            "$typeLabel$textSnippet"
        } else if (pos.unitId != null) {
            pos.unitId
        } else if (pos.unitIndex > 0) {
            "${getString(R.string.navigate_unit)} ${pos.unitIndex + 1}"
        } else {
            ""
        }
        val positionText = getString(R.string.ai_position_format, chName, scName, unitDesc)
        val msg = ChatMessage(text = positionText, isUser = false)
        messages.add(msg)
    }

    private fun showSessionListDialog() {
        if (!isAdded) return
        val bookId = generateViewModel.bookId.takeIf { it.isNotBlank() }
            ?: argBookId?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            try {
                val loaded = runCatching {
                    generateViewModel.repository.listSessions(bookId)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load session list", e)
                    if (isAdded) {
                        try {
                            android.widget.Toast.makeText(requireContext(), R.string.ai_error, android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                }.getOrDefault(emptyList())
                if (!isAdded) return@launch
                sessions.clear()
                sessions.addAll(loaded)
                if (sessions.isEmpty()) {
                    if (isAdded) {
                        try {
                            android.widget.Toast.makeText(requireContext(), R.string.ai_no_sessions, android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                    return@launch
                }
                val titles = sessions.map { s ->
                    val active = if (s.sessionId == currentSessionId) "✓ " else ""
                    val dateStr = try {
                        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                        fmt.format(java.util.Date(s.updatedAt.takeIf { it > 0 } ?: s.createdAt))
                    } catch (_: Exception) { "" }
                    val modeLabel = when (s.mode) {
                        "conversation" -> getString(R.string.ai_mode_conversation)
                        "edit" -> getString(R.string.ai_mode_edit)
                        "import" -> getString(R.string.ai_mode_import)
                        "director" -> getString(R.string.ai_mode_director)
                        "extraction" -> getString(R.string.ai_mode_extraction)
                        "validation" -> getString(R.string.ai_mode_validation)
                        else -> s.mode
                    }
                    "$active$dateStr · $modeLabel"
                }.toTypedArray()
                if (!isAdded) return@launch
                try {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.ai_session_list)
                        .setItems(titles) { _, which ->
                            try {
                                val selected = sessions.getOrNull(which) ?: return@setItems
                                if (selected.sessionId != currentSessionId) {
                                    restoreSessionFromApi(selected)
                                }
                            } catch (_: Exception) {}
                        }
                        .setPositiveButton(android.R.string.cancel, null)
                        .show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show session list dialog", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "showSessionListDialog unexpected error", e)
            }
        }
    }

    private fun addWelcomeMessage() {
        val bid = generateViewModel.bookId.takeIf { it.isNotBlank() }
            ?: argBookId?.takeIf { it.isNotBlank() }
        if (bid != null) {
            addContextualWelcome(bid)
        } else {
            val msg = ChatMessage(text = getString(R.string.ai_welcome), isUser = false)
            messages.add(msg)
            adapter.submitList(messages.toList())
            scrollToBottom()
        }
    }

    private fun addContextualWelcome(bookId: String) {
        welcomeJob?.cancel()
        welcomeJob = lifecycleScope.launch {
            try {
                val rawBook = generateViewModel.repository.getBook(bookId)
                val bookData = rawBook
                val title = bookData.book?.title ?: bookData.manifest?.book_id ?: bookId
                val pos = SharedPositionManager.current.value
                val ch = bookData.chapters?.firstOrNull { it.chapter_id == pos.chapterId }
                val sc = ch?.scenes?.firstOrNull { it.scene_id == pos.sceneId }
                val scIdx = sc?.display_index ?: 0
                val uIdx = bookData.unitIndex(pos.chapterId, pos.sceneId, pos.unitIndex)
                val isSpecial = ch?.is_special == true
                val chTitle = ch?.chapter_title?.takeIf { it.isNotBlank() }
                val chapterId = if (isSpecial) {
                    chTitle ?: ch?.type?.replaceFirstChar { it.uppercase() } ?: "?"
                } else if (chTitle != null) {
                    chTitle
                } else if (ch?.display_number != null) {
                    "${getString(R.string.navigate_chapter)} ${ch.display_number}"
                } else {
                    pos.chapterId ?: "?"
                }
                val sceneId = if (scIdx > 0) "${getString(R.string.navigate_scene)} $scIdx" else pos.sceneId ?: "?"
                val iuLabel = if (uIdx > 0) "${getString(R.string.navigate_unit)} $uIdx" else ""
                val text = getString(R.string.ai_welcome_context, title, chapterId, sceneId, iuLabel)
                val msg = ChatMessage(text = text, isUser = false)
                if (!isAdded || binding == null) return@launch
                messages.add(msg)
                adapter.submitList(messages.toList())
                scrollToBottom()
            } catch (_: Exception) {
                if (!isAdded || binding == null) return@launch
                val msg = ChatMessage(text = getString(R.string.ai_welcome), isUser = false)
                messages.add(msg)
                adapter.submitList(messages.toList())
            }
        }
    }

    private fun showTypingIndicator() {
        val b = binding ?: return
        b.typingIndicator.root.visibility = View.VISIBLE
        typingAnimJob?.cancel()
        val bubble = b.typingIndicator.root.getChildAt(0) as? ViewGroup ?: return
        val dots = (0 until 3).map { bubble.getChildAt(it) }
        typingAnimJob = lifecycleScope.launch {
            var index = 0
            while (isActive) {
                dots.forEachIndexed { i, dot ->
                    dot.alpha = if (i == index % 3) 1.0f else 0.25f
                }
                index = (index + 1) % 4
                delay(300)
            }
        }
    }

    private fun hideTypingIndicator() {
        typingAnimJob?.cancel()
        binding?.typingIndicator?.root?.visibility = View.GONE
    }

    /** Marshals [block] to the UI thread when the fragment is attached. */
    private fun runOnMain(block: () -> Unit) {
        if (isAdded) requireActivity().runOnUiThread(block)
    }

    /** Resolves an i18n string-resource key name (e.g. "ai_state_timeout") to its value. */
    private fun stringByKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val res = requireContext().resources
        val resId = res.getIdentifier(key, "string", requireContext().packageName)
        return if (resId == 0) null else getString(resId)
    }

    /** Send button: send icon when idle, stop icon while streaming. */
    private fun updateSendButton() {
        val b = binding ?: return
        b.sendButton.isEnabled = true
        b.sendButton.setImageResource(if (aiSending) R.drawable.ic_stop else R.drawable.ic_send)
        b.sendButton.contentDescription = getString(if (aiSending) R.string.ai_cancel else R.string.ai_send)
    }

    /** User pressed stop — cancel the in-flight stream and mark the bubble cancelled. */
    private fun stopAiStream() {
        if (!aiSending) return
        aiSending = false
        updateSendButton()
        hideTypingIndicator()
        aiChatStream.cancel()
        runOnMain {
            val idx = assistantMsgIndex
            if (idx >= 0 && idx < messages.size) {
                val m = messages[idx]
                // Keep partial answer if anything streamed; empty bubble → ai_cancelled.
                val bubbleText = if (streamedText.isNotBlank()) streamedText else m.text
                val finalText = if (bubbleText.isBlank()) getString(R.string.ai_cancelled) else bubbleText
                messages[idx] = m.copy(streaming = false, cancelled = true, text = finalText)
                if (streamedText.isNotEmpty()) {
                    apiMessages.add(AiMessage(role = "assistant", content = streamedText))
                }
            } else {
                messages.add(ChatMessage(text = getString(R.string.ai_cancelled), isUser = false, cancelled = true))
            }
            adapter.submitList(messages.toList())
            scrollToBottom()
        }
    }

    /** Send a message, streaming the reply over SSE with fallback to one-shot POST /ai/chat. */
    private fun sendAiMessage() {
        val text = binding?.inputField?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        binding?.inputField?.setText("")

        val userMsg = ChatMessage(text = text, isUser = true)
        messages.add(userMsg)
        apiMessages.add(AiMessage(role = "user", content = text))
        adapter.submitList(messages.toList())
        scrollToBottom()

        aiSending = true
        updateSendButton()

        val sessionAtSend = currentSessionId
        assistantMsgIndex = -1
        streamedText = ""
        currentSource = null
        streamSessionId = null

        lifecycleScope.launch {
            val bid = generateViewModel.bookId.takeIf { it.isNotBlank() }
                ?: argBookId?.takeIf { it.isNotBlank() }
            if (bid.isNullOrBlank()) {
                runOnMain { aiSending = false; updateSendButton() }
                return@launch
            }
            val lang = resolveLanguage(Locale.getDefault().language)

            val request = AiChatRequest(
                apiMessages.toList(),
                bookId = bid,
                lang = lang,
                mode = currentMode.id,
                topicId = "book",
                sceneId = SharedPositionManager.current.value.sceneId,
                characterId = null,
                sessionId = sessionAtSend
            )

            // Placeholder assistant bubble that will be filled by the stream.
            // (lifecycleScope.launch on Fragment runs on the main thread.)
            showTypingIndicator()
            scrollToBottom()
            assistantMsgIndex = messages.size
            messages.add(
                ChatMessage(
                    text = "",
                    isUser = false,
                    streaming = true,
                    sourceBadge = sourceBadgeKey(currentSource)
                )
            )
            adapter.submitList(messages.toList())
            scrollToBottom()

            aiChatStream.onMeta = { meta ->
                if (sessionAtSend == null && meta.sessionId != null) {
                    currentSessionId = meta.sessionId
                    streamSessionId = meta.sessionId
                }
                currentSource = meta.aiSource
                runOnMain {
                    val idx = assistantMsgIndex
                    if (idx >= 0 && idx < messages.size) {
                        messages[idx] = messages[idx].copy(sourceBadge = sourceBadgeKey(meta.aiSource))
                        adapter.notifyItemChanged(idx)
                    }
                }
            }

            aiChatStream.onDelta = { delta ->
                streamedText += delta
                runOnMain {
                    val idx = assistantMsgIndex
                    if (idx >= 0 && idx < messages.size) {
                        messages[idx] = messages[idx].copy(text = streamedText, streaming = true)
                        adapter.notifyItemChanged(idx)
                        scrollToBottom()
                    }
                }
            }

            aiChatStream.onDone = { done ->
                runOnMain {
                    finalizeAiStream(done, bid, sessionAtSend)
                }
            }

            aiChatStream.onError = { error ->
                runOnMain {
                    handleAiStreamError(error, sessionAtSend)
                }
            }

            try {
                aiChatStream.start(request)
            } catch (e: Exception) {
                runOnMain {
                    handleAiStreamError(
                        AiChatError(code = null, message = e.message ?: "stream failed"),
                        sessionAtSend
                    )
                }
            }
        }
    }

    /** Terminal frame received — finalize the assistant bubble (web: onDone). */
    private fun finalizeAiStream(done: AiChatDone, bid: String?, sessionAtSend: String?) {
        // Discard if user switched sessions while streaming
        if (sessionAtSend != null && currentSessionId != sessionAtSend) {
            aiSending = false
            updateSendButton()
            hideTypingIndicator()
            return
        }

        aiSending = false
        updateSendButton()
        hideTypingIndicator()

        val idx = assistantMsgIndex
        // displayText priority: done.reply → streamedText → tool-results message.
        val finalText = AiChatStream.displayTextFor(
            done,
            streamedText,
            buildToolResultMessage(done.toolResults ?: emptyList(), done.patchesApplied)
        )

        // Save session_id only when backend auto-created it (no session before)
        if (sessionAtSend == null && done.sessionId != null) {
            currentSessionId = done.sessionId
            streamSessionId = done.sessionId
        }

        val sourceKey = sourceBadgeKey(currentSource)
        val aiMsg = if (done.bookId != null) {
            var bookId_ = done.bookId
            if (bookId_.contains("api/v1/book/")) {
                bookId_ = bookId_.substringAfterLast("/api/v1/book/").substringBeforeLast("/")
            }
            val downloadUrl = "${com.example.animastor.BuildConfig.BASE_URL}api/v1/book/${bookId_}/download"
            ChatMessage(text = finalText, isUser = false, downloadUrl = downloadUrl,
                source = currentSource, sourceBadge = sourceKey)
        } else {
            ChatMessage(text = finalText, isUser = false, source = currentSource,
                sourceBadge = sourceKey)
        }

        // Single write of the assistant bubble (keeps source badge).
        if (idx >= 0 && idx < messages.size) {
            messages[idx] = aiMsg
        } else {
            messages.add(aiMsg)
        }

        apiMessages.add(AiMessage(role = "assistant", content = done.reply ?: streamedText))

        // ── External invalidation: the assistant just mutated the book
        // bundle server-side (patches_applied > 0). Same contract as the
        // one-shot POST /ai/chat path (AiAssistantFragment parity).
        val patchedBookId = done.bookId
            ?.substringAfterLast("/api/v1/book/")
            ?.substringBeforeLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: bid
        if (done.patchesApplied > 0 && !patchedBookId.isNullOrBlank()) {
            ResourceInvalidations.emitExternal(ResourceInvalidations.Keys.book(patchedBookId))
            Log.i(TAG, "chat applied ${done.patchesApplied} patch(es) — emitted book invalidation for $patchedBookId")
            loadBookDataAsync()
        }

        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    /** Stream error — mark the bubble failed with the error text (no fallback to one-shot POST /ai/chat). */
    private fun handleAiStreamError(error: AiChatError, sessionAtSend: String?) {
        if (sessionAtSend != null && currentSessionId != sessionAtSend) {
            aiSending = false
            updateSendButton()
            hideTypingIndicator()
            return
        }

        val idx = assistantMsgIndex
        aiSending = false
        updateSendButton()
        hideTypingIndicator()

        val errText = stringByKey(streamErrorKey(error.code))
            ?: getString(R.string.ai_error, error.message ?: "stream failed")

        if (idx >= 0 && idx < messages.size) {
            val m = messages[idx]
            // Mid-stream failure: partial answer stays, error note appended
            // below it (web: `${m.text}\n\n${msg}`); before first delta → error text only.
            val text = if (streamedText.isNotBlank()) "$streamedText\n\n$errText" else errText
            messages[idx] = m.copy(streaming = false, failed = true, text = text)
        } else {
            messages.add(ChatMessage(text = errText, isUser = false, failed = true))
        }
        // Web parity: an assistant turn only when something streamed — no empty content.
        if (streamedText.isNotEmpty()) {
            apiMessages.add(AiMessage(role = "assistant", content = streamedText))
        }
        adapter.submitList(messages.toList())
        scrollToBottom()
    }


    private fun startVoiceInput() {
        voiceInputClickCount++
        if (isRecognizing) return
        if (isListening) {
            stopListening()
            return
        }

        val permission = android.Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(requireContext(), permission)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(permission),
                REQ_CODE_MIC_PERMISSION
            )
            return
        }

        isListening = true
        isRecognizing = true
        activity?.runOnUiThread {
            binding?.micButton?.setImageResource(R.drawable.ic_mic_off)
            binding?.inputField?.setText(R.string.ai_speaking)
            binding?.inputField?.setFocusable(false)
            binding?.inputField?.setFocusableInTouchMode(false)
        }

        speechRecognizer?.let { sr ->
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_speaking))
            }
            sr.startListening(intent)
        } ?: run {
            activity?.runOnUiThread {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Voice recognition not available",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            stopListening()
        }
    }

    private fun stopListening() {
        if (!isListening) return
        isRecognizing = false
        speechRecognizer?.cancel()
        speechRecognizer?.stopListening()
        isListening = false
        activity?.runOnUiThread {
            binding?.micButton?.setImageResource(R.drawable.ic_mic)
            binding?.inputField?.setFocusable(true)
            binding?.inputField?.setFocusableInTouchMode(true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_MIC_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Microphone permission granted")
            } else {
                messages.add(ChatMessage(text = getString(R.string.ai_permission_required), isUser = false))
                adapter.submitList(messages.toList())
                scrollToBottom()
            }
        }
    }

    private fun buildToolResultMessage(toolResults: List<Any>?, patchesApplied: Int): String {
        if (patchesApplied > 0) {
            return "✅ Changes applied: $patchesApplied patch(es) to the book."
        }
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        for (tr in toolResults ?: emptyList()) {
            when (tr) {
                is ToolCallResult -> {
                    if (tr.error != null) errors.add("⚠️ ${tr.tool}: ${tr.error}")
                    else if (tr.result != null || tr.applied != null) {
                        successes.add(if (tr.applied != null && tr.applied > 0) "✅ ${tr.tool}: ${tr.applied} change(s) applied" else "✅ ${tr.tool}: ${tr.result ?: "done"}")
                    }
                }
                is Map<*, *> -> {
                    val tool = tr["tool"]?.toString() ?: "tool"
                    val error = tr["error"]
                    val result = tr["result"]
                    val applied = tr["applied"]
                    if (error != null) errors.add("⚠️ $tool: $error")
                    else if (result != null || applied != null) {
                        successes.add(if (applied is Number && applied.toInt() > 0) "✅ $tool: ${applied} change(s) applied" else "✅ $tool: ${result ?: "done"}")
                    }
                }
            }
        }
        val parts = errors + successes
        // No patches, no tool results — the model produced nothing usable
        // (e.g. reasoning consumed the whole token budget). Honest retry hint
        // instead of the old misleading 'Tool executed.' ghost message.
        // (Web parity: AiAssistantPage.buildToolResultMessage / ai_no_result.)
        return if (parts.isEmpty()) getString(R.string.ai_no_result) else parts.joinToString("\n")
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        binding?.messageList?.post {
            if (messages.isNotEmpty()) {
                binding?.messageList?.smoothScrollToPosition(messages.size - 1)
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && argCreateMode && generateViewModel.bookId.isNotBlank()) {
            argCreateMode = false
            loadSessionsFromBackend()
        }
    }

    override fun onDestroyView() {
        stopListening()
        sessionLoadJob?.cancel()
        welcomeJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        binding = null
        super.onDestroyView()
    }
}
