package com.example.nativeguitartuner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import kotlinx.coroutines.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    private val pedalImages = listOf(
        R.drawable.vintage_drive_pedal,
        R.drawable.blue_delay_pedal,
        R.drawable.wood,
        R.drawable.wood2,
        R.drawable.punk,
        R.drawable.taj,
        R.drawable.doom,
        R.drawable.dovercastle,
        R.drawable.gothic,
        R.drawable.alien
    )

    private val vduImages = listOf(
        R.drawable.dial2,
        R.drawable.dial3,
        R.drawable.dial4,
        R.drawable.dial
    )

    private var isRecording by mutableStateOf(false)
    private var detectedNote by mutableStateOf("--")
    private var frequencyText by mutableStateOf("0.00 Hz")
    private var statusText by mutableStateOf("Press Start to Tune")
    private var statusColor by mutableStateOf(Color.Black)
    private var rotationAngle by mutableStateOf(0f)
    private var smoothedAngle by mutableStateOf(0f)
    private var voiceModeEnabled by mutableStateOf(true)

    private var dispatcher: AudioDispatcher? = null
    private var audioThread: Thread? = null
    private val sampleRate = 22050
    private val audioBufferSize = 1024
    private val bufferOverlap = 0

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastFeedbackTime = 0L

    private lateinit var soundPool: SoundPool
    private var soundUp = 0
    private var soundDown = 0
    private var soundIntune = 0

    private var isActiveTuner = true

    private val noteFrequencies = listOf(
        82.41f to "E2",
        110.00f to "A2",
        146.83f to "D3",
        196.00f to "G3",
        246.94f to "B3",
        329.63f to "E4"
    )

    private fun getNearestNoteFrequency(pitch: Float): Pair<Float, String>? {
        // This function is fine as is, finding the closest standard note.
        return noteFrequencies.minByOrNull { abs(pitch - it.first) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        soundPool = SoundPool.Builder()
            .setMaxStreams(3) // Increased streams to handle overlapping sounds if needed
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        soundUp = soundPool.load(this, R.raw.up, 1)
        soundDown = soundPool.load(this, R.raw.down, 1)
        soundIntune = soundPool.load(this, R.raw.intune, 1)

        val selectedPedal = pedalImages.random()
        val selectedVDU = vduImages.random()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            painter = painterResource(id = selectedPedal),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )

                        Image(
                            painter = painterResource(id = selectedVDU),
                            contentDescription = null,
                            modifier = Modifier
                                .size(280.dp)
                                .align(Alignment.Center)
                        )

                        Image(
                            painter = painterResource(id = R.drawable.needle),
                            contentDescription = null,
                            modifier = Modifier
                                .size(140.dp)
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    rotationZ = smoothedAngle
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.84f)
                                }
                        )

                        Icon(
                            imageVector = if (voiceModeEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Toggle Voice Feedback",
                            tint = if (voiceModeEnabled) Color.Green else Color.Red,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(28.dp)
                                .align(Alignment.TopStart)
                                .clickable { voiceModeEnabled = !voiceModeEnabled }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Note: $detectedNote", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            // ADDED: Display for the detected frequency
                            Text(text = frequencyText, fontSize = 16.sp, color = Color.LightGray)
                            Text(text = statusText, fontSize = 20.sp, color = statusColor)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                if (isRecording) stopTuner() else requestPermissionAndStartTuner()
                            }) {
                                Text(if (isRecording) "Stop" else "Start")
                            }
                        }
                    }
                }
            }
        }

        activityScope.launch {
            while (isActive) {
                delay(16) // Smoother animation with ~60fps delay
                val smoothing = 0.2f // Adjusted smoothing for a less jittery needle
                smoothedAngle += (rotationAngle - smoothedAngle) * smoothing
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActiveTuner = true
    }

    override fun onStop() {
        super.onStop()
        isActiveTuner = false
        if (isRecording) stopTuner()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
        activityScope.cancel()
    }

    private fun requestPermissionAndStartTuner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            startTuner()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startTuner()
            } else {
                statusText = "Permission Denied. Cannot tune."
                statusColor = Color.Red
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTuner() {
        if (isRecording) return
        try {
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, audioBufferSize, bufferOverlap)
            val pitchDetectionHandler = PitchDetectionHandler { result, _ ->
                activityScope.launch { handlePitchResult(result) }
            }
            val pitchProcessor = PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.YIN, sampleRate.toFloat(), audioBufferSize, pitchDetectionHandler)
            dispatcher?.addAudioProcessor(pitchProcessor)
            audioThread = Thread(dispatcher, "AudioDispatcherThread").apply {
                isDaemon = true
                start()
            }
            isRecording = true
            statusText = "Listening..."
            statusColor = Color(0xFF4CAF50)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tuner", e)
            statusText = "Error starting tuner. Please restart."
            statusColor = Color.Red
            isRecording = false
        }
    }

    // ADDED: A helper function to play sounds with all the necessary checks.
    private fun playFeedbackSound(soundId: Int) {
        val now = System.currentTimeMillis()
        // Cooldown prevents sound from spamming. 1.5 seconds is a good value.
        if (voiceModeEnabled && isActiveTuner && now - lastFeedbackTime > 1500) {
            if (soundId != 0) { // Check if the sound loaded correctly
                soundPool.play(soundId, 1f, 1f, 0, 0, 1f)
                lastFeedbackTime = now
            }
        }
    }

    // CHANGED: The entire pitch handling logic is updated for correctness.
    private fun handlePitchResult(result: PitchDetectionResult) {
        val pitch = result.pitch
        // Check for a reliable pitch detection
        if (pitch != -1f && result.isPitched && result.probability > 0.85) {
            frequencyText = String.format("%.2f Hz", pitch)
            val nearestNote = getNearestNoteFrequency(pitch)

            if (nearestNote != null) {
                val (noteFreq, noteName) = nearestNote
                val diff = pitch - noteFreq

                // The needle angle is now calculated based on the deviation from the target note.
                rotationAngle = calculateNeedleAngle(pitch, noteFreq)

                when {
                    // In Tune: If the pitch is very close to the target frequency
                    abs(diff) <= 1.5f -> {
                        detectedNote = noteName
                        statusText = "$noteName (In Tune)"
                        statusColor = Color.Green
                        playFeedbackSound(soundIntune) // Play "in tune" sound
                    }
                    // Flat: If the pitch is lower than the target
                    diff < -1.5f -> {
                        detectedNote = noteName
                        statusText = "$noteName (Flat)"
                        statusColor = Color(0xFFFFA000) // Orange color for warning
                        playFeedbackSound(soundDown) // Play "flat" sound
                    }
                    // Sharp: If the pitch is higher than the target
                    diff > 1.5f -> {
                        detectedNote = noteName
                        statusText = "$noteName (Sharp)"
                        statusColor = Color(0xFFFFA000) // Orange color for warning
                        playFeedbackSound(soundUp) // Play "sharp" sound
                    }
                }
            }
        } else {
            // No reliable pitch detected
            if (isRecording) { // Only reset if we are actively listening
                detectedNote = "--"
                frequencyText = "0.00 Hz"
                statusText = "Listen Closely..."
                statusColor = Color.LightGray
                rotationAngle = 0f
            }
        }
    }

    // CHANGED: This function now correctly calculates the needle's angle based on deviation.
    private fun calculateNeedleAngle(currentPitch: Float, targetPitch: Float): Float {
        // The maximum deviation in Hz to show on the meter.
        // A pitch difference of 5Hz (sharp or flat) will move the needle to the edge.
        val maxDeviation = 5.0f
        val diff = currentPitch - targetPitch

        // Normalize the difference to a range of -1.0 to 1.0
        val normalizedDiff = (diff / maxDeviation).coerceIn(-1f, 1f)

        // Map the normalized value to the angle range [-90, 90]
        return normalizedDiff * 90f
    }

    private fun stopTuner() {
        audioThread?.interrupt()
        dispatcher?.stop()
        try {
            audioThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        dispatcher = null
        audioThread = null
        isRecording = false
        statusText = "Stopped. Press Start to Tune."
        statusColor = Color.DarkGray
        detectedNote = "--"
        frequencyText = "0.00 Hz"
        rotationAngle = 0f
    }
}
