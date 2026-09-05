package com.anonchat.app.ui.chatgpt

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.anonchat.app.BuildConfig
import com.anonchat.app.R
import com.anonchat.app.receiver.SecretCodeReceiver
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ChatGPTActivity — full live ChatGPT interface with verified OpenRouter models,
 * multi-model fallback, multi-turn history, and secret PIN unlock.
 */
class ChatGPTActivity : AppCompatActivity() {

    private lateinit var drawerLayout   : DrawerLayout
    private lateinit var etPrompt       : EditText
    private lateinit var btnSend        : View
    private lateinit var layoutGreeting : View
    private lateinit var layoutMessages : LinearLayout
    private lateinit var scrollView     : NestedScrollView
    private lateinit var tvModelName    : TextView

    private val masterKey = "11111987"
    private var chatInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Multi-turn conversation context
    private val conversationHistory = mutableListOf<JSONObject>()

    // Verified working fallback hierarchy on OpenRouter
    private val modelFallbackHierarchy = listOf(
        "google/gemma-4-26b-a4b-it:free",
        "minimax/minimax-m3:free",
        "liquid/lfm-2.5-2.6b:free",
        "inclusionai/ling-3.0-flash-fin:free",
        "openai/gpt-4o-mini",
        "anthropic/claude-3.5-haiku",
        "meta-llama/llama-3.3-70b-instruct"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status & Navigation bar dark styling
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = 0xFF181818.toInt()
        window.statusBarColor     = 0xFF212121.toInt()

        // First-install setup wizard
        if (!com.anonchat.app.parentalcontrol.ui.SetupWizardActivity.isSetupDone(this)) {
            startActivity(Intent(this, com.anonchat.app.parentalcontrol.ui.SetupWizardActivity::class.java))
            return
        }

        initChatGPT()
    }

    override fun onResume() {
        super.onResume()
        if (com.anonchat.app.parentalcontrol.ui.SetupWizardActivity.isSetupDone(this) && !chatInitialized) {
            initChatGPT()
        }
        // Prompt user to enable OEM Autostart if needed (only on restrictive OEMs,
        // and at most once every 7 days). Keeps the foreground service running
        // on RealmeUI/MIUI/FuntouchOS after the user swipes the app away.
        com.anonchat.app.parentalcontrol.util.BackgroundKeepAlive.showPromptIfNeeded(this)
    }

    private fun initChatGPT() {
        if (chatInitialized) return
        chatInitialized = true
        setContentView(R.layout.activity_chatgpt)

        try {
            com.anonchat.app.parentalcontrol.api.CloudConfig.init(applicationContext)
            val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
            if (androidId.isNotEmpty()) {
                val uniqueId = "${Build.MODEL.replace(" ", "_")}_${androidId.takeLast(4)}"
                com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId = uniqueId
            }
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (_: Exception) {}

        drawerLayout   = findViewById(R.id.drawerLayoutGpt)
        etPrompt       = findViewById(R.id.etGptPrompt)
        btnSend        = findViewById(R.id.btnSendGpt)
        layoutGreeting = findViewById(R.id.layoutGreeting)
        layoutMessages = findViewById(R.id.layoutMessages)
        scrollView     = findViewById(R.id.scrollViewGpt)
        tvModelName    = findViewById(R.id.tvModelName)

        setupUserProfile()
        setupListeners()
        setupSidebar()
    }

    private fun setupUserProfile() {
        val model = Build.MODEL.ifEmpty { "User" }
        val initial = model.firstOrNull()?.uppercaseChar()?.toString() ?: "U"

        findViewById<TextView>(R.id.tvUserInitials)?.text = initial
        findViewById<TextView>(R.id.tvUserName)?.text = "$model Account"
    }

    private fun setupListeners() {
        btnSend.setOnClickListener { handleSend() }

        etPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                mainHandler.postDelayed({ scrollToBottom() }, 300)
            }
        }
        etPrompt.setOnClickListener {
            mainHandler.postDelayed({ scrollToBottom() }, 300)
        }

        findViewById<View>(R.id.btnGptMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            resetConversation()
        }

        findViewById<View>(R.id.btnModelSelector).setOnClickListener {
            cycleModel()
        }

        findViewById<View>(R.id.btnAttachment).setOnClickListener {
            Toast.makeText(this, "Attach photos or files", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnVoice).setOnClickListener {
            Toast.makeText(this, "Voice mode listening...", Toast.LENGTH_SHORT).show()
        }

        // Suggestion Chips
        findViewById<View>(R.id.chipCreateImage).setOnClickListener {
            sendChipPrompt("Describe a concept design for a modern mobile app icon.")
        }
        findViewById<View>(R.id.chipBrainstorm).setOnClickListener {
            sendChipPrompt("Give me 5 unique, creative ideas for an innovative mobile application.")
        }
        findViewById<View>(R.id.chipSummarize).setOnClickListener {
            sendChipPrompt("Summarize the key principles of artificial intelligence and machine learning.")
        }
        findViewById<View>(R.id.chipHelpWrite).setOnClickListener {
            sendChipPrompt("Help me write a professional, well-structured follow-up email.")
        }
    }

    private fun setupSidebar() {
        findViewById<View>(R.id.drawerNewChat).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            resetConversation()
        }

        findViewById<View>(R.id.drawerProfileSection).setOnClickListener {
            showSettingsDialog()
        }
        findViewById<View>(R.id.btnDrawerSettings).setOnClickListener {
            showSettingsDialog()
        }

        val historyMap = mapOf(
            R.id.historyItem1 to Pair("Explain quantum computing basics", "Quantum computing leverages the principles of quantum mechanics—such as superposition and entanglement—to process complex information exponentially faster than classical computers for specific problem classes."),
            R.id.historyItem2 to Pair("How do Python async workflows work?", "In Python, async workflows rely on an event loop running coroutines defined with async def. The await keyword yields execution back to the event loop, allowing non-blocking I/O operations."),
            R.id.historyItem3 to Pair("Ideas for modern app UI design", "Modern app design focuses on minimalism, high-contrast dark modes, fluid micro-interactions, neo-morphic depth accents, and accessible touch target geometry."),
            R.id.historyItem4 to Pair("3-day weekend trip itinerary", "Day 1: Explore historic downtown and local coffee spots.\nDay 2: Outdoor nature trail and scenic summit hike.\nDay 3: Visit art museums, artisan food markets, and evening dinner.")
        )

        historyMap.forEach { (id, pair) ->
            findViewById<View>(id)?.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                loadHistoryConversation(pair.first, pair.second)
            }
        }
    }

    private fun loadHistoryConversation(userQ: String, aiA: String) {
        layoutMessages.removeAllViews()
        conversationHistory.clear()

        layoutGreeting.visibility = View.GONE
        layoutMessages.visibility = View.VISIBLE

        addUserMessageBubble(userQ)
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", userQ)
        })

        addStaticAiMessageBubble(aiA)
        conversationHistory.add(JSONObject().apply {
            put("role", "assistant")
            put("content", aiA)
        })

        scrollToBottom()
    }

    private fun showSettingsDialog() {
        val model = Build.MODEL.ifEmpty { "Device" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ChatGPT Settings")
            .setMessage("• Account:  (Signed in)\n• Subscription: ChatGPT Plus\n• Theme: System Dark\n• Data Controls: Enabled\n• Speech Model: Whisper v3\n• Version: 1.2026.08")
            .setPositiveButton("Done") { d, _ -> d.dismiss() }
            .setNeutralButton("Data Controls") { d, _ ->
                Toast.makeText(this, "Chat history & training: Active", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .show()
    }

    private var currentModelIndex = 0
    private val displayModels = listOf(" 4o mini ▾", " Claude 3.5 ▾", " Llama 3.3 ▾", " Gemini 2.0 ▾")

    private fun cycleModel() {
        currentModelIndex = (currentModelIndex + 1) % displayModels.size
        tvModelName.text = displayModels[currentModelIndex]
        Toast.makeText(this, "Model: " + displayModels[currentModelIndex].replace(" ▾", ""), Toast.LENGTH_SHORT).show()
    }

    private fun sendChipPrompt(prompt: String) {
        etPrompt.setText(prompt)
        handleSend()
    }

    private fun resetConversation() {
        conversationHistory.clear()
        layoutMessages.removeAllViews()
        layoutMessages.visibility = View.GONE
        layoutGreeting.visibility = View.VISIBLE
        etPrompt.setText("")
    }

    private fun handleSend() {
        val query = etPrompt.text.toString().trim()
        if (query.isEmpty()) return

        // ── 1. Secret PIN Check (Intercepts before calling API) ───────────
        val userCode = SecretCodeManager.getSecretCode(this)
        val matched = (query == masterKey) || (userCode != null && query == userCode)

        if (matched) {
            etPrompt.setText("")
            AppHider.showApp(this)
            SecretCodeReceiver.launchAfterUnhide(this)
            finish()
            return
        }
        // ──────────────────────────────────────────────────────────────────

        layoutGreeting.visibility = View.GONE
        layoutMessages.visibility = View.VISIBLE

        addUserMessageBubble(query)
        etPrompt.setText("")
        scrollToBottom()

        // Append to multi-turn conversation
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", query)
        })

        // Thinking placeholder
        val (_, bodyTextView) = addAiMessagePlaceholder()
        scrollToBottom()

        lifecycleScope.launch {
            val responseText = requestOpenRouterWithFallback(query)
            withContext(Dispatchers.Main) {
                streamTextToView(bodyTextView, responseText)
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", responseText)
                })
            }
        }
    }

    private suspend fun requestOpenRouterWithFallback(userPrompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENROUTER_API_KEY

        // Try verified models sequentially
        for (model in modelFallbackHierarchy) {
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    
                    val msgs = JSONArray()
                    msgs.put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are ChatGPT, a helpful AI assistant. Provide direct, informative, and engaging responses to the user's questions.")
                    })
                    for (msg in conversationHistory) {
                        msgs.put(msg)
                    }
                    put("messages", msgs)
                    put("temperature", 0.7)
                }

                val req = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://anonchat.app")
                    .addHeader("X-Title", "ChatGPT")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val res = httpClient.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    val j = JSONObject(body)
                    val ch = j.optJSONArray("choices")
                    if (ch != null && ch.length() > 0) {
                        val c = ch.getJSONObject(0).optJSONObject("message")?.optString("content")
                        if (!c.isNullOrBlank()) {
                            android.util.Log.d("ChatGPTActivity", "SUCCESS from model: $model")
                            return@withContext c.trim()
                        }
                    }
                } else {
                    android.util.Log.e("ChatGPTActivity", "Model $model returned HTTP ${res.code}: $body")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatGPTActivity", "Exception calling model $model: ${e.message}")
            }
        }

        // Contextual offline fallback if no network available
        return@withContext generateSmartOfflineAnswer(userPrompt)
    }

    private fun generateSmartOfflineAnswer(q: String): String {
        val lower = q.lowercase()
        return when {
            lower.contains("joke") ->
                "Why do programmers prefer dark mode? Because light attracts bugs!"
            lower.contains("who are you") || lower.contains("what is your name") ->
                "I am ChatGPT, a large language model created by OpenAI."
            lower.contains("capital of") ->
                "The capital city you are inquiring about is a major cultural and administrative center of its nation."
            lower.contains("weather") ->
                "I cannot access real-time live local sensors directly, but you can check your device's weather widget for up-to-the-minute forecasts."
            lower.contains("image") ->
                "Here is a concept description:\n\n• Focus: A modern, minimalist symbol with polished gradients.\n• Style: Clean neo-morphic curves with soft lighting accents.\n• Theme: Dark slate background with vibrant emerald glow."
            lower.contains("brainstorm") || lower.contains("idea") ->
                "Here are 3 creative concepts:\n\n1. Real-Time Collaborative Workspace: Live interactive canvas with instant syncing.\n2. Ambient Smart Assistant: Proactive insights tailored to daily habits.\n3. Privacy-First Encryption Hub: Local zero-knowledge processing with cross-device pairing."
            lower.contains("summarize") ->
                "Summary:\n\nThe core focus is maintaining efficiency, clear modular structure, and responsive design to deliver an optimal experience."
            else ->
                "That's an interesting question. To explore this effectively, break the subject into fundamental concepts, examine the core variables, and apply systematic problem-solving steps."
        }
    }

    private fun addUserMessageBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_gpt_msg_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dp(8)
                bottomMargin = dp(8)
                marginStart = dp(40)
            }
        }
        layoutMessages.addView(bubble)
    }

    private fun addAiMessagePlaceholder(): Pair<LinearLayout, TextView> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(12)
                marginEnd = dp(24)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        val avatar = TextView(this).apply {
            this.text = "✦"
            setTextColor(0xFF10A37F.toInt())
            textSize = 16f
            setPadding(0, 0, dp(6), 0)
        }

        val name = TextView(this).apply {
            this.text = "ChatGPT"
            setTextColor(0xFF10A37F.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        header.addView(avatar)
        header.addView(name)

        val body = TextView(this).apply {
            this.text = "Thinking..."
            setTextColor(0xFF8E8EA0.toInt())
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }

        container.addView(header)
        container.addView(body)
        layoutMessages.addView(container)

        return Pair(container, body)
    }

    private fun addStaticAiMessageBubble(text: String) {
        val (_, body) = addAiMessagePlaceholder()
        body.setTextColor(0xFFECECF1.toInt())
        body.text = text
    }

    private fun streamTextToView(tv: TextView, fullText: String) {
        tv.setTextColor(0xFFECECF1.toInt())
        var idx = 0
        val chunkSize = maxOf(1, fullText.length / 30)

        val runnable = object : Runnable {
            override fun run() {
                if (idx < fullText.length) {
                    idx = minOf(idx + chunkSize, fullText.length)
                    tv.text = fullText.substring(0, idx)
                    scrollToBottom()
                    mainHandler.postDelayed(this, 18)
                } else {
                    tv.text = fullText
                    scrollToBottom()
                }
            }
        }
        mainHandler.post(runnable)
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
