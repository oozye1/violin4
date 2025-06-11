package com.example.nativeguitartuner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.filters.HighPass
import be.tarsos.dsp.filters.LowPassFS
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.util.fft.FFT
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

enum class VisualizerMode {
    NONE, BARS, WAVEFORM, SPECTROGRAPH
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        private const val SAMPLE_RATE = 22050
        private const val AUDIO_BUFFER_SIZE = 2048
        private const val BUFFER_OVERLAP = 0
        private const val CONFIDENCE_THRESHOLD = 0.9f
        private const val PITCH_BUFFER_SIZE = 5
        private const val NO_SIGNAL_RESET_DELAY_MS = 500L
        private const val IN_TUNE_DELAY_MS = 2500L
        private const val IN_TUNE_CENTS_THRESHOLD = 3.0f
        private const val SPECTROGRAPH_HISTORY_SIZE = 100
    }

    // --- State Variables ---
    private var isRecording by mutableStateOf(false)
    private var detectedNote by mutableStateOf("--")
    private var frequencyText by mutableStateOf("0.00 Hz")
    private var statusText by mutableStateOf("Press Start to Tune")
    private var statusColor by mutableStateOf(Color.Black)
    private var rotationAngle by mutableStateOf(0f)
    private var smoothedAngle by mutableStateOf(0f)
    private var voiceModeEnabled by mutableStateOf(true)

    private var cents by mutableStateOf(0.0f)
    private val activeLedIndex by derivedStateOf { (cents / 10f).coerceIn(-5f, 5f).toInt() }

    private var isMetronomeRunning by mutableStateOf(false)
    private var tempo by mutableStateOf(120)
    private val timeSignatures = listOf("4/4", "3/4", "6/8", "2/4", "5/4")
    private var timeSignatureIndex by mutableStateOf(0)
    private var metronomeJob: Job? = null

    private var visualizerMode by mutableStateOf(VisualizerMode.NONE)
    private var magnitudes by mutableStateOf(floatArrayOf())
    private var waveformData by mutableStateOf(floatArrayOf())
    private var spectrographHistory by mutableStateOf<List<FloatArray>>(emptyList())

    // --- Skin State and Lists ---
    private var selectedPedal by mutableStateOf(R.drawable.doom)
    private var selectedVDU by mutableStateOf(R.drawable.dial)

    private val pedalImages = listOf(
        R.drawable.vintage_drive_pedal, R.drawable.blue_delay_pedal, R.drawable.wood, R.drawable.wood2,
        R.drawable.punk, R.drawable.taj, R.drawable.doom, R.drawable.dovercastle, R.drawable.gothic, R.drawable.alien
    )
    private val vduImages = listOf(R.drawable.dial2, R.drawable.dial3, R.drawable.dial4, R.drawable.dial)

    // --- Audio Processing Variables ---
    private var dispatcher: AudioDispatcher? = null
    private var audioThread: Thread? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isActiveTuner = true
    private lateinit var soundPool: SoundPool
    private var soundUp = 0; private var soundDown = 0; private var soundIntune = 0; private var soundTick = 0
    private var lastFeedbackTime = 0L
    private val pitchBuffer = mutableListOf<Float>()
    private var lastPitchTime = 0L
    private var inTuneStartTime = 0L
    private var inTuneSoundPlayed = false

    private val noteFrequencies = listOf(
        82.41f to "E2", 110.00f to "A2", 146.83f to "D3", 196.00f to "G3", 246.94f to "B3", 329.63f to "E4"
    )

    private fun getNearestNoteFrequency(pitch: Float): Pair<Float, String>? = noteFrequencies.minByOrNull { abs(pitch - it.first) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSoundPool()

        setContent {
            val window = this@MainActivity.window
            LaunchedEffect(isRecording, isMetronomeRunning) {
                val keepScreenOn = isRecording || isMetronomeRunning
                if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(painter = painterResource(id = selectedPedal), contentDescription = null, modifier = Modifier.fillMaxSize())
                        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) { MetronomeControls() }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp)) {
                            LedTuningStrip(activeLedIndex = activeLedIndex)
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(painter = painterResource(id = selectedVDU), contentDescription = null, modifier = Modifier.size(280.dp))
                        }
                        Image(painter = painterResource(id = R.drawable.needle), contentDescription = null, modifier = Modifier.size(140.dp).align(Alignment.Center).offset(y = (-20).dp).graphicsLayer {
                            rotationZ = smoothedAngle; transformOrigin = TransformOrigin(0.5f, 0.84f)
                        })
                        Icon(imageVector = if (voiceModeEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Toggle Voice Feedback", tint = if (voiceModeEnabled) Color.Green else Color.Red,
                            modifier = Modifier.padding(12.dp).size(28.dp).align(Alignment.TopStart).clickable { voiceModeEnabled = !voiceModeEnabled })

                        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Note: $detectedNote", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(text = frequencyText, fontSize = 16.sp, color = Color.LightGray)
                            Text(text = statusText, fontSize = 20.sp, color = statusColor)
                            Spacer(modifier = Modifier.height(16.dp))
                            AudioVisualizer()
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { if (isRecording) stopTuner() else requestPermissionAndStartTuner() }) { Text(if (isRecording) "Stop" else "Start") }
                                Button(onClick = { randomizeSkins() }) { Text("Skin") }
                            }
                        }
                    }
                }
            }
        }
        activityScope.launch { while (isActive) { delay(16); val smoothing = 0.1f; smoothedAngle += (rotationAngle - smoothedAngle) * smoothing } }
    }

    private fun randomizeSkins() {
        var newPedal = pedalImages.random()
        while (newPedal == selectedPedal) { newPedal = pedalImages.random() }
        selectedPedal = newPedal
        selectedVDU = vduImages.random()
    }

    private fun setupSoundPool() {
        soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        soundUp = soundPool.load(this, R.raw.up, 1); soundDown = soundPool.load(this, R.raw.down, 1); soundIntune = soundPool.load(this, R.raw.intune, 1); soundTick = soundPool.load(this, R.raw.metronome_tick, 1)
    }

    override fun onStart() { super.onStart(); isActiveTuner = true }
    override fun onStop() { super.onStop(); isActiveTuner = false; stopMetronome() }
    override fun onDestroy() { super.onDestroy(); soundPool.release(); activityScope.cancel() }

    private fun requestPermissionAndStartTuner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else { startTuner() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isActiveTuner = true; startTuner()
            } else {
                statusText = "Permission Denied."; statusColor = Color.Red
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMetronome() {
        if (isMetronomeRunning) return
        isMetronomeRunning = true
        metronomeJob = activityScope.launch(Dispatchers.Default) {
            while (isActive) {
                withContext(Dispatchers.Main) { soundPool.play(soundTick, 1f, 1f, 0, 0, 1f) }
                val delayMillis = 60_000L / tempo
                delay(delayMillis)
            }
        }
    }

    private fun stopMetronome() { metronomeJob?.cancel(); isMetronomeRunning = false }

    private fun startTuner() {
        if (isRecording) return
        try {
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, AUDIO_BUFFER_SIZE, BUFFER_OVERLAP)
            val pitchDetectionHandler = PitchDetectionHandler { r, _ -> activityScope.launch { processPitchResult(r) } }
            val pitchProcessor = PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.YIN, SAMPLE_RATE.toFloat(), AUDIO_BUFFER_SIZE, pitchDetectionHandler)

            val fftSize = 1024
            val fftProcessor = object : AudioProcessor {
                var fft = FFT(fftSize)
                private val newMagnitudes = FloatArray(fftSize / 2)
                override fun process(audioEvent: AudioEvent): Boolean {
                    val audioFloatBuffer = audioEvent.floatBuffer
                    val waveformUpdate = audioFloatBuffer.copyOf()
                    val transformBuffer = audioFloatBuffer.clone()
                    fft.forwardTransform(transformBuffer)
                    for (i in 0 until fftSize / 2) {
                        val real = transformBuffer[2 * i]; val imag = transformBuffer[2 * i + 1]
                        newMagnitudes[i] = sqrt(real * real + imag * imag)
                    }
                    val magnitudeUpdate = newMagnitudes.copyOf()
                    activityScope.launch {
                        waveformData = waveformUpdate; magnitudes = magnitudeUpdate
                        val newHistory = spectrographHistory.toMutableList()
                        newHistory.add(0, magnitudeUpdate)
                        while (newHistory.size > SPECTROGRAPH_HISTORY_SIZE) { newHistory.removeLast() }
                        spectrographHistory = newHistory
                    }
                    return true
                }
                override fun processingFinished() {}
            }

            dispatcher?.addAudioProcessor(HighPass(60f, SAMPLE_RATE.toFloat()))
            dispatcher?.addAudioProcessor(LowPassFS(1500f, SAMPLE_RATE.toFloat()))
            dispatcher?.addAudioProcessor(pitchProcessor)
            dispatcher?.addAudioProcessor(fftProcessor)

            audioThread = Thread(dispatcher, "AudioDispatcherThread").apply { isDaemon = true; start() }
            isRecording = true; statusText = "Listening..."; statusColor = Color(0xFF4CAF50); lastPitchTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tuner", e); statusText = "Error starting tuner."; statusColor = Color.Red; isRecording = false
        }
    }

    private fun processPitchResult(result: PitchDetectionResult) {
        if (result.pitch != -1f && result.isPitched && result.probability > CONFIDENCE_THRESHOLD) {
            lastPitchTime = System.currentTimeMillis()
            pitchBuffer.add(result.pitch)
            if (pitchBuffer.size > PITCH_BUFFER_SIZE) { pitchBuffer.removeAt(0) }
            if (pitchBuffer.size == PITCH_BUFFER_SIZE) { updateUiWithPitch(getMedian(pitchBuffer)) }
        } else { if (System.currentTimeMillis() - lastPitchTime > NO_SIGNAL_RESET_DELAY_MS) { resetUiToListening() } }
    }

    private fun getMedian(list: List<Float>): Float { if (list.isEmpty()) return 0f; return list.sorted()[list.size / 2] }

    private fun updateUiWithPitch(stablePitch: Float) {
        frequencyText = String.format("%.2f Hz", stablePitch)
        val nearestNote = getNearestNoteFrequency(stablePitch)
        if (nearestNote != null) {
            val (noteFreq, noteName) = nearestNote
            cents = 1200f * kotlin.math.log(stablePitch / noteFreq, 2.0f)
            rotationAngle = (cents.coerceIn(-50f, 50f) / 50f) * 90f
            val isInTune = abs(cents) <= IN_TUNE_CENTS_THRESHOLD
            if (isInTune) {
                detectedNote = noteName; statusText = "$noteName (In Tune)"; statusColor = Color.Green
                if (inTuneStartTime == 0L) { inTuneStartTime = System.currentTimeMillis() }
                if (System.currentTimeMillis() - inTuneStartTime >= IN_TUNE_DELAY_MS && !inTuneSoundPlayed) { playFeedbackSound(soundIntune); inTuneSoundPlayed = true }
            } else {
                inTuneStartTime = 0L; inTuneSoundPlayed = false
                if (cents < 0) { detectedNote = noteName; statusText = "$noteName (Tune Up)"; statusColor = Color(0xFFFFA000); playFeedbackSound(soundUp) }
                else { detectedNote = noteName; statusText = "$noteName (Tune Down)"; statusColor = Color(0xFFFFA000); playFeedbackSound(soundDown) }
            }
        }
    }

    private fun resetUiToListening() {
        if (isRecording) {
            detectedNote = "--"; frequencyText = "..."; statusText = "Listening..."; statusColor = Color.LightGray
            rotationAngle = 0f; pitchBuffer.clear(); inTuneStartTime = 0L; inTuneSoundPlayed = false; cents = 0.0f
            magnitudes = floatArrayOf(); waveformData = floatArrayOf(); spectrographHistory = emptyList()
        }
    }

    private fun playFeedbackSound(soundId: Int) {
        val now = System.currentTimeMillis()
        val cooldown = if (soundId == soundIntune) 0 else 1500
        if (voiceModeEnabled && isActiveTuner && now - lastFeedbackTime > cooldown) { if (soundId != 0) { soundPool.play(soundId, 1f, 1f, 1, 0, 1f); lastFeedbackTime = now } }
    }

    private fun stopTuner() {
        audioThread?.interrupt(); dispatcher?.stop()
        try { audioThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        dispatcher = null; audioThread = null; isRecording = false; pitchBuffer.clear()
        statusText = "Press Start to Tune"; statusColor = Color.DarkGray; detectedNote = "--"; frequencyText = "0.00 Hz"
        rotationAngle = 0f; inTuneStartTime = 0L; inTuneSoundPlayed = false; cents = 0.0f
        magnitudes = floatArrayOf(); waveformData = floatArrayOf(); spectrographHistory = emptyList()
    }

    @Composable
    fun MetronomeControls() {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.6f), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.8f))) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (tempo > 40) tempo-- }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowLeft, "Decrease Tempo", tint = Color.White) }
                Text("$tempo BPM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { if (tempo < 240) tempo++ }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowRight, "Increase Tempo", tint = Color.White) }
                Spacer(Modifier.width(8.dp)); Divider(modifier = Modifier.height(24.dp).width(1.dp), color = Color.Gray.copy(alpha = 0.8f)); Spacer(Modifier.width(8.dp))
                IconButton(onClick = { timeSignatureIndex = (timeSignatureIndex - 1 + timeSignatures.size) % timeSignatures.size }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowLeft, "Previous Time Signature", tint = Color.White) }
                Text(timeSignatures[timeSignatureIndex], color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(50.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { timeSignatureIndex = (timeSignatureIndex + 1) % timeSignatures.size }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowRight, "Next Time Signature", tint = Color.White) }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = { if (isMetronomeRunning) stopMetronome() else startMetronome() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isMetronomeRunning) Color(0xFFE53935) else Color(0xFF43A047))) { Text(if (isMetronomeRunning)"Stop" else "Start") }
            }
        }
    }

    @Composable
    fun AudioVisualizer() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.fillMaxWidth(0.8f).height(80.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                when (visualizerMode) {
                    VisualizerMode.BARS -> BarsVisualizer(Modifier.fillMaxSize(), magnitudes)
                    VisualizerMode.WAVEFORM -> WaveformVisualizer(Modifier.fillMaxSize(), waveformData)
                    VisualizerMode.SPECTROGRAPH -> SpectrographVisualizer(Modifier.fillMaxSize(), spectrographHistory)
                    VisualizerMode.NONE -> {}
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val allModes = VisualizerMode.entries; val currentIndex = allModes.indexOf(visualizerMode)
                val nextIndex = (currentIndex + 1) % allModes.size; visualizerMode = allModes[nextIndex]
            }) { Text("Visualizer: ${visualizerMode.name.lowercase().replaceFirstChar { it.titlecase() }}") }
        }
    }

    @Composable
    fun BarsVisualizer(modifier: Modifier = Modifier, magnitudes: FloatArray) {
        Canvas(modifier = modifier) {
            if (magnitudes.isNotEmpty()) {
                val barCount = magnitudes.size / 2
                val barWidth = size.width / barCount
                val maxMagnitude = (magnitudes.maxOrNull() ?: 1f).coerceAtLeast(0.5f)
                magnitudes.take(barCount).forEachIndexed { index, mag ->
                    val normalizedHeight = (mag / maxMagnitude).coerceIn(0f, 1f)
                    val barHeight = normalizedHeight * size.height
                    val color = lerp(Color.Green, Color.Red, normalizedHeight)
                    drawRect(color = color, topLeft = Offset(x = index * barWidth, y = size.height - barHeight), size = Size(width = barWidth * 0.8f, height = barHeight))
                }
            }
        }
    }

    @Composable
    fun WaveformVisualizer(modifier: Modifier = Modifier, data: FloatArray) {
        Canvas(modifier = modifier) {
            if (data.isNotEmpty()) {
                val path = Path(); val stepX = size.width / data.size
                path.moveTo(0f, size.height / 2)
                data.forEachIndexed { index, value -> path.lineTo(index * stepX, (size.height / 2) * (1 - value)) }
                drawPath(path = path, color = Color.Green, style = Stroke(width = 2f))
            }
        }
    }

    @Composable
    fun SpectrographVisualizer(modifier: Modifier = Modifier, history: List<FloatArray>) {
        Canvas(modifier = modifier) {
            if (history.isNotEmpty()) {
                val historySize = history.size; val fftSize = history.first().size
                val cellHeight = size.height / historySize; val cellWidth = size.width / fftSize
                history.forEachIndexed { yIndex, magnitudes ->
                    val maxMag = (magnitudes.maxOrNull() ?: 1f).coerceAtLeast(0.1f)
                    magnitudes.forEachIndexed { xIndex, mag ->
                        val normalizedMag = (mag / maxMag).coerceIn(0f, 1f)
                        val color = lerp(Color.Blue, Color.Yellow, normalizedMag)
                        drawRect(color = color, topLeft = Offset(x = xIndex * cellWidth, y = yIndex * cellHeight), size = Size(cellWidth, cellHeight))
                    }
                }
            }
        }
    }

    @Composable
    fun LedTuningStrip(activeLedIndex: Int) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            (-5..5).forEach { index ->
                val isActive = when { activeLedIndex < 0 -> index >= activeLedIndex && index < 0; activeLedIndex > 0 -> index <= activeLedIndex && index > 0; else -> index == 0 }
                val color = when { index == 0 -> Color(0xFF00C853); abs(index) in 1..2 -> Color(0xFFFFFF00); else -> Color(0xFFD50000) }
                LedIndicator(isActive = isActive, activeColor = color)
                if (index < 5) { Spacer(modifier = Modifier.width(2.dp)) }
            }
        }
    }

    @Composable
    fun LedIndicator(isActive: Boolean, activeColor: Color) {
        val color = if (isActive) activeColor else Color.DarkGray.copy(alpha = 0.5f)
        Box(modifier = Modifier.size(width = 20.dp, height = 24.dp).background(color, shape = RoundedCornerShape(4.dp)).border(width = 1.dp, color = Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp)))
    }
}
