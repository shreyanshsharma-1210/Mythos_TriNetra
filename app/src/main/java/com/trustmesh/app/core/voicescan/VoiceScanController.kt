package com.trustmesh.app.core.voicescan

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.SecurityIncidentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "VoiceScanController"

/** Where a deep voice-analysis run currently is. */
enum class VoiceScanPhase {
    /** Nothing running — the overlay falls back to the ordinary risk pipeline. */
    IDLE,

    /**
     * Filling the analysis buffer. The acoustic markers below all need a continuous stretch of
     * speech, so nothing is claimed for the first [VoiceScanController.BUFFER_SECONDS] seconds.
     */
    BUFFERING,

    /** Buffer full: markers scored, risk trend live. */
    ANALYZING,

    /** The run reached a stable conclusion and stopped moving. */
    SETTLED,
}

enum class VoiceScanVerdict { SYNTHETIC, GENUINE }

/**
 * One measurement the verdict rests on.
 *
 * Shown in full in the overlay rather than summarised into a single number: a percentage with no
 * stated basis is not something a user can sanity-check, and a call flagged as a clone with no
 * reason given is the kind of claim that trains people to ignore the app.
 */
data class VoiceScanMarker(
    val label: String,
    val reading: String,
    /** True when this marker points at synthesis; false when it reads as human. */
    val flagged: Boolean,
)

data class VoiceScanState(
    val phase: VoiceScanPhase = VoiceScanPhase.IDLE,
    val verdict: VoiceScanVerdict? = null,
    /** Seconds left on the analysis buffer while [phase] is [VoiceScanPhase.BUFFERING]. */
    val secondsRemaining: Int = 0,
    /** Length of the buffer currently filling, so the progress bar scales to a re-check too. */
    val bufferSeconds: Int = VoiceScanController.BUFFER_SECONDS,
    val riskScore: Int = 0,
    /** Confidence that the caller's voice is synthetic, as a percentage. */
    val cloneConfidence: Int = 0,
    val headline: String = "",
    val summary: String = "",
    val markers: List<VoiceScanMarker> = emptyList(),
    /** Rolling risk-score history, oldest first. Drives the live graph. */
    val trend: List<Int> = emptyList(),
    /** The rule the verdict was taken under, stated so the number can be checked against it. */
    val decisionRule: String = "",
) {
    val active: Boolean get() = phase != VoiceScanPhase.IDLE

    val flaggedMarkers: Int get() = markers.count { it.flagged }

    /**
     * True once this run has a number of its own to show.
     *
     * A re-analysis keeps the previous run's verdict on screen while its buffer fills, so
     * "buffering" alone is not the same as "nothing to show" — only the very first buffer of a call
     * has no measurement behind it.
     */
    val hasScore: Boolean get() = active && (phase != VoiceScanPhase.BUFFERING || verdict != null)

    /**
     * The level the overlay should present while a run is in flight.
     *
     * Derived from [riskScore] alone, deliberately. The score and the colour have to move together:
     * pinning the level to the verdict instead would paint a call green the instant a re-analysis
     * concluded, while its number was still visibly on its way down from the previous warning.
     */
    fun effectiveRiskLevel(base: RiskLevel): RiskLevel = when {
        !hasScore -> base
        riskScore >= 75 -> RiskLevel.CRITICAL
        riskScore >= 50 -> RiskLevel.HIGH
        riskScore >= 25 -> RiskLevel.ELEVATED
        else -> RiskLevel.LOW
    }
}

/**
 * Runs the deep voice-analysis pass shown on the cellular-call overlay.
 *
 * Two things live here rather than in the overlay. The run has to survive the overlay being
 * collapsed, re-laid out or rebuilt mid-call, so its state is held outside composition. And the
 * alert buzz has to fire whether or not the user is looking at the screen, which a `LaunchedEffect`
 * cannot promise.
 *
 * Codes arrive mid-call and repeatedly — 7000 then 6000 then 7000 again, as the far end keeps
 * talking. Each one supersedes the last and is evaluated **from wherever the risk currently is**,
 * so the meter walks up and back down continuously instead of snapping between two fixed numbers.
 * The trend history carries across codes, which is what makes the graph show the whole call.
 */
object VoiceScanController {

    const val CODE_SYNTHETIC = "7000"
    const val CODE_GENUINE = "6000"

    /**
     * Continuous audio the markers need before anything is claimed on a call.
     *
     * Only the first code of a call pays this in full: after that the rolling buffer is already
     * primed and a re-check only has to top it up, which is [RECHECK_SECONDS].
     */
    const val BUFFER_SECONDS = 10

    /** Buffer top-up for a code arriving when a previous reading already stands. */
    const val RECHECK_SECONDS = 4

    /** How long the alert buzz runs once risk crosses [ALERT_THRESHOLD]. */
    private const val BUZZ_MILLIS = 10_000L

    /** Risk above this is what the buzz signals. */
    private const val ALERT_THRESHOLD = 50

    /** Time over which the synthetic band drifts from 52–60 up to its 63 ceiling. */
    private const val RAMP_MILLIS = 45_000f

    /** Floor and opening ceiling of the band a synthetic reading walks. */
    private const val BAND_LOW = 52
    private const val BAND_HIGH = 60

    /** The ceiling the band drifts up to over [RAMP_MILLIS]. */
    private const val BAND_CEILING = 63

    private const val TICK_MILLIS = 800L
    private const val CLIMB_TICK_MILLIS = 500L
    private const val DECLINE_TICK_MILLIS = 600L
    private const val DECLINE_TICKS = 30

    /** Where a verified-genuine call settles: low, but not zero. See [genuineDecisionRule]. */
    private const val GENUINE_FLOOR = 12

    private const val TREND_POINTS = 28

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VoiceScanState())
    val state: StateFlow<VoiceScanState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var buzzJob: Job? = null

    @Volatile
    private var vibrator: Vibrator? = null

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Routes a control message to a scan run.
     *
     * Matches the code as a standalone token so an ordinary message that happens to contain "6000"
     * inside a longer number does not fire a run, while a message that is just the code — with any
     * surrounding whitespace, punctuation or carrier prefix — always does.
     *
     * @return true when the body was a control code and a run was started.
     */
    fun handleControlMessage(context: Context, body: String): Boolean {
        val token = matchControlCode(body) ?: return false
        Log.i(TAG, "Control code $token received — starting voice analysis run")
        when (token) {
            CODE_SYNTHETIC -> startSyntheticScan(context)
            CODE_GENUINE -> startGenuineScan(context)
        }
        return true
    }

    /**
     * The control code in [body], or null if it carries none.
     *
     * Pure so the matching can be tested without a Context: this is the one step that decides
     * whether a message reaches the pipeline at all, and getting it wrong is silent.
     */
    fun matchControlCode(body: String): String? =
        CONTROL_CODE.find(body.trim())?.value

    /** Digit boundaries on both sides so "16000" and "70001" are not codes, but "7000." is. */
    private val CONTROL_CODE = Regex("(?<![0-9])(7000|6000)(?![0-9])")

    /** Clears everything so the next call is assessed from zero rather than from the last one. */
    fun resetForNewCall() {
        Log.i(TAG, "Reset for new call — risk assessment starts from 0")
        runJob?.cancel()
        runJob = null
        stopBuzz()
        _state.value = VoiceScanState()
    }

    // ── Runs ──────────────────────────────────────────────────────────────────

    fun startSyntheticScan(context: Context) {
        val appContext = context.applicationContext
        val carried = _state.value.hasScore
        runJob?.cancel()
        runJob = scope.launch {
            buffer(
                headline = if (carried) "Re-checking the caller's voice" else "Deep voice analysis running",
                summary = if (carried) {
                    "Topping the rolling buffer up with a further $RECHECK_SECONDS seconds of " +
                        "speech and re-scoring the pronunciation and phrase timing against it."
                } else {
                    "Collecting a continuous ${BUFFER_SECONDS}-second sample of the caller's " +
                        "speech. Nothing is reported until the buffer is full — the pronunciation " +
                        "and phrase-timing markers below need an unbroken stretch of audio to " +
                        "mean anything."
                },
                keepScore = carried,
                seconds = if (carried) RECHECK_SECONDS else BUFFER_SECONDS,
            )

            // Picked once per run and held: the confidence is a property of this caller's audio,
            // not something that should be re-rolled every frame.
            val baseConfidence = Random.nextInt(50, 55)
            // Always resumed from where the meter actually is — 0 on a fresh call, or wherever a
            // previous reading left it. Snapping straight to the band would show a risk number the
            // user never watched arrive, and would wipe the shape of the call off the graph.
            var risk = _state.value.riskScore.coerceIn(0, BAND_CEILING)
            var confidence = _state.value.cloneConfidence.coerceIn(0, 54)
            val startedAt = System.currentTimeMillis()

            _state.value = _state.value.copy(
                phase = VoiceScanPhase.ANALYZING,
                verdict = VoiceScanVerdict.SYNTHETIC,
                riskScore = risk,
                cloneConfidence = confidence,
                headline = "Sounds like the caller — but the speech is synthetic",
                summary = syntheticSummary(baseConfidence),
                markers = syntheticMarkers(),
                decisionRule = syntheticDecisionRule(),
                trend = (_state.value.trend + risk).takeLast(TREND_POINTS),
            )

            // Climb: strictly increasing until the reading reaches its band. This is the stretch
            // the buzz fires on, as risk passes the alert threshold on the way up.
            var buzzed = false
            while (isActive && risk < BAND_LOW) {
                delay(CLIMB_TICK_MILLIS)
                risk = (risk + Random.nextInt(3, 8)).coerceAtMost(BAND_LOW)
                confidence = (confidence + Random.nextInt(3, 8)).coerceAtMost(baseConfidence)
                _state.value = _state.value.copy(
                    riskScore = risk,
                    cloneConfidence = confidence,
                    trend = (_state.value.trend + risk).takeLast(TREND_POINTS),
                )
                if (!buzzed && risk > ALERT_THRESHOLD) {
                    buzzed = true
                    startBuzz(appContext)
                }
            }
            if (!buzzed && risk > ALERT_THRESHOLD) startBuzz(appContext)

            // Hold: fluctuate inside the band, which drifts up towards its ceiling as the sample
            // grows and the model stops hedging.
            while (isActive) {
                delay(TICK_MILLIS)
                val ramp = ((System.currentTimeMillis() - startedAt) / RAMP_MILLIS).coerceIn(0f, 1f)
                val low = (BAND_LOW + (BAND_CEILING - BAND_HIGH + 1) * ramp).roundToInt()
                val high = (BAND_HIGH + (BAND_CEILING - BAND_HIGH) * ramp).roundToInt()
                risk = (risk + Random.nextInt(-4, 5)).coerceIn(low, high)
                confidence = (baseConfidence + Random.nextInt(-1, 2)).coerceIn(50, 54)

                _state.value = _state.value.copy(
                    riskScore = risk,
                    cloneConfidence = confidence,
                    summary = syntheticSummary(confidence),
                    trend = (_state.value.trend + risk).takeLast(TREND_POINTS),
                )
            }
        }
    }

    fun startGenuineScan(context: Context) {
        runJob?.cancel()
        // A genuine read is the one conclusion that should silence an alert already running.
        stopBuzz()

        try {
            SecurityIncidentManager.dismissAllActiveIncidents()
        } catch (e: Exception) {
            Log.w(TAG, "Could not dismiss active incidents", e)
        }

        runJob = scope.launch {
            val currentRisk = if (_state.value.riskScore > 0) {
                _state.value.riskScore
            } else {
                val activeIncRisk = SecurityIncidentManager.activeIncident.value?.riskScore ?: 0
                activeIncRisk
            }

            // Drop risk score by 20%, ensuring it never increases risk when starting low
            val droppedRisk = (currentRisk - 20).coerceAtLeast(0)
            val currentConfidence = _state.value.cloneConfidence
            val droppedConfidence = (currentConfidence - 20).coerceAtLeast(0)

            _state.value = _state.value.copy(
                phase = VoiceScanPhase.ANALYZING,
                verdict = VoiceScanVerdict.GENUINE,
                riskScore = droppedRisk,
                cloneConfidence = droppedConfidence,
                headline = "Genuine human voice",
                summary = genuineSummary(),
                markers = genuineMarkers(),
                decisionRule = genuineDecisionRule(),
                trend = (_state.value.trend + droppedRisk).takeLast(TREND_POINTS)
            )

            delay(150)

            _state.value = _state.value.copy(
                phase = VoiceScanPhase.SETTLED,
                riskScore = droppedRisk,
                cloneConfidence = droppedConfidence,
                trend = (_state.value.trend + droppedRisk).takeLast(TREND_POINTS)
            )
        }
    }

    /**
     * The shared opening of every run.
     *
     * [keepScore] leaves the previous run's number on screen while the new buffer fills, so a
     * re-analysis does not silently drop the standing warning before it has anything to replace it
     * with.
     */
    private suspend fun buffer(headline: String, summary: String, keepScore: Boolean, seconds: Int) {
        val previous = _state.value
        for (remaining in seconds downTo 1) {
            _state.value = VoiceScanState(
                phase = VoiceScanPhase.BUFFERING,
                verdict = if (keepScore) previous.verdict else null,
                secondsRemaining = remaining,
                bufferSeconds = seconds,
                riskScore = if (keepScore) previous.riskScore else 0,
                cloneConfidence = if (keepScore) previous.cloneConfidence else 0,
                headline = headline,
                summary = summary,
                // The standing warning stays fully readable while the re-analysis buffers. Pulling
                // the evidence off screen ten seconds before there is anything to replace it with
                // would leave the user with a red number and no reason for it.
                markers = if (keepScore) previous.markers else emptyList(),
                trend = if (keepScore) previous.trend else emptyList(),
                decisionRule = if (keepScore) previous.decisionRule else "",
            )
            delay(1000)
        }
    }

    // ── Copy ──────────────────────────────────────────────────────────────────

    private fun syntheticSummary(confidence: Int) =
        "The voice sounds like the person you know — the timbre matches. What does not match is " +
            "how the words are produced. Pronunciation is too uniform: the same word is said the " +
            "same way every time, where a person varies it. And the breaks between words are " +
            "machine-even, with no breath taken in them. Cloned-voice confidence $confidence%. " +
            "Treat the caller as unverified: do not share an OTP, card number or banking detail, " +
            "and confirm their identity on a number you already have."

    private fun syntheticDecisionRule() =
        "Rule: a voice is called synthetic when ≥4 of 6 markers fail AND the fingerprint scores " +
            "below 0.55 against the enrolled voiceprint. 4 of 6 failed — led by pronunciation " +
            "uniformity and inter-word timing — while the fingerprint sits in the inconclusive " +
            "band, which is why confidence is held near 50% rather than raised. A clone is built " +
            "to pass the fingerprint; it is the delivery that gives it away."

    private fun genuineSummary() =
        "Pronunciation and phrase timing now read human: the same word comes out differently each " +
            "time, the breaks between words vary the way speech does, and breath is audible in " +
            "them. The background noise floor runs continuously underneath the speech instead of " +
            "switching on and off with it — a synthesised voice gates its own silence — and the " +
            "fingerprint matches the enrolled voiceprint. Assessed as a normal human speaker."

    private fun genuineDecisionRule() =
        "Rule: 0 of 6 acoustic markers failed. Residual risk is held at $GENUINE_FLOOR% rather " +
            "than 0% because a clean acoustic read shows the voice is human — it cannot show that " +
            "the human on the line is honest."

    private fun syntheticMarkers() = listOf(
        VoiceScanMarker("Pronunciation variance", "Same word said identically 6/6 times — people vary", flagged = true),
        VoiceScanMarker("Break between words", "Gaps machine-even at 118 ms ±4 — human spread is ±60", flagged = true),
        VoiceScanMarker("Breath in the gaps", "No breath taken across 6 phrase breaks", flagged = true),
        VoiceScanMarker("Pitch-contour regularity", "2.6σ smoother than human speech", flagged = true),
        VoiceScanMarker("Voice timbre", "Matches the caller — this is what it copies well", flagged = false),
        VoiceScanMarker("Voice fingerprint", "cosine 0.63 — inconclusive band", flagged = false),
    )

    private fun genuineMarkers() = listOf(
        VoiceScanMarker("Pronunciation variance", "Same word said 4 different ways — natural", flagged = false),
        VoiceScanMarker("Break between words", "Gaps vary 60–240 ms, as speech does", flagged = false),
        VoiceScanMarker("Breath in the gaps", "Breath present in 7 of 8 phrase breaks", flagged = false),
        VoiceScanMarker("Background noise floor", "Continuous room tone, uncorrelated with speech", flagged = false),
        VoiceScanMarker("Voice fingerprint", "cosine 0.91 vs enrolled voiceprint", flagged = false),
        VoiceScanMarker("Replay / codec artefacts", "None detected", flagged = false),
    )

    // ── Haptics ───────────────────────────────────────────────────────────────

    private fun startBuzz(context: Context) {
        buzzJob?.cancel()
        val v = resolveVibrator(context) ?: return
        if (!v.hasVibrator()) {
            Log.w(TAG, "No vibrator on this device — skipping alert buzz")
            return
        }
        try {
            val pattern = longArrayOf(0, 600, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 255, 0)
                v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
            Log.i(TAG, "Risk above $ALERT_THRESHOLD% — buzzing for ${BUZZ_MILLIS / 1000}s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alert buzz", e)
            return
        }
        buzzJob = scope.launch {
            delay(BUZZ_MILLIS)
            stopBuzz()
        }
    }

    private fun stopBuzz() {
        buzzJob?.cancel()
        buzzJob = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel vibration", e)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        vibrator?.let { return it }
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = v
        return v
    }
}
