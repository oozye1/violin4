package com.example.nativeguitartuner

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.util.fft.FFT
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.log2

enum class VisualizerMode {
    NONE, BARS, WAVEFORM
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
        private const val IN_TUNE_DELAY_MS = 2500L
        private const val IN_TUNE_CENTS_THRESHOLD = 3.0f

        // --- NEW: Constants for SharedPreferences ---
        private const val PREFS_NAME = "TunerPrefs"
        private const val PREF_PEDAL_SKIN = "pedal_skin"
        private const val PREF_VDU_SKIN = "vdu_skin"
    }

    // State Variables
    private var isRecording by mutableStateOf(false)
    private var detectedNote by mutableStateOf("--")
    private var frequencyText by mutableStateOf("0.00 Hz")
    private var statusText by mutableStateOf("Press Start to Tune")
    private var statusColor by mutableStateOf(Color.White)
    private var rotationAngle by mutableStateOf(0f)
    private var smoothedAngle by mutableStateOf(0f)
    private var voiceModeEnabled by mutableStateOf(false)
    private var cents by mutableStateOf(0.0f)
    private val activeLedIndex by derivedStateOf { (cents / 10f).coerceIn(-5f, 5f).toInt() }
    private var isMetronomeRunning by mutableStateOf(false)
    private var tempo by mutableStateOf(120)
    private var timeSignatureIndex by mutableStateOf(0)
    private var metronomeJob: Job? = null
    private var visualizerMode by mutableStateOf(VisualizerMode.BARS)
    private var magnitudes by mutableStateOf(floatArrayOf())
    private var waveformData by mutableStateOf(floatArrayOf())
    private var soundsLoaded by mutableStateOf(false)

    // Audio Processing and System
    private var dispatcher: AudioDispatcher? = null
    private var audioThread: Thread? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isActiveTuner by mutableStateOf(true)
    private lateinit var soundPool: SoundPool
    private var soundUp = 0; private var soundDown = 0; private var soundIntune = 0; private var soundTick = 0

    // Timing and Buffers
    private var lastFeedbackTime = 0L
    private val pitchBuffer = mutableListOf<Float>()
    private var inTuneStartTime = 0L
    private var inTuneSoundPlayed = false

    // App Resources
    private lateinit var selectedPedal: MutableState<Int>
    private lateinit var selectedVDU: MutableState<Int>
    private lateinit var pedalImages: List<Int>
    private lateinit var vduImages: List<Int>
    private lateinit var timeSignatures: List<String>
    private lateinit var noteFrequencies: List<Pair<Float, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- NEW: Load saved skins from SharedPreferences ---
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPedalId = prefs.getInt(PREF_PEDAL_SKIN, R.drawable.red)
        val savedVduId = prefs.getInt(PREF_VDU_SKIN, R.drawable.dial)

        // Initialize resources, using the loaded values
        selectedPedal = mutableStateOf(savedPedalId)
        selectedVDU = mutableStateOf(savedVduId)
        pedalImages = listOf(
            R.drawable.vintage_drive_pedal, R.drawable.blue_delay_pedal, R.drawable.wood, R.drawable.wood2, R.drawable.punk, R.drawable.taj, R.drawable.doom,
            R.drawable.dovercastle, R.drawable.gothic, R.drawable.alien, R.drawable.cyber, R.drawable.graffiti, R.drawable.hendrix, R.drawable.steampunk,
            R.drawable.usa, R.drawable.spacerock, R.drawable.acrylic, R.drawable.horse, R.drawable.stoner, R.drawable.surf,
            R.drawable.red, R.drawable.yellow, R.drawable.black, R.drawable.green, R.drawable.cats, R.drawable.wolf, R.drawable.sunflowers
        )
        vduImages = listOf(R.drawable.dial2, R.drawable.dial3, R.drawable.dial4, R.drawable.dial)
        timeSignatures = listOf("4/4", "3/4", "6/8", "2/4", "5/4")
        noteFrequencies = listOf(82.41f to "E2", 110.00f to "A2", 146.83f to "D3", 196.00f to "G3", 246.94f to "B3", 329.63f to "E4")

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
                        Image(painter = painterResource(id = selectedPedal.value), contentDescription = null, modifier = Modifier.fillMaxSize())
                        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
                            MetronomeControls(enabled = soundsLoaded)
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).offset(y = (-15).dp)
                        ) {
                            LedTuningStrip(activeLedIndex = activeLedIndex)
                            Image(
                                painter = painterResource(id = selectedVDU.value),
                                contentDescription = null,
                                modifier = Modifier.size(280.dp)
                            )
                        }
                        Image(painter = painterResource(id = R.drawable.needle), contentDescription = null, modifier = Modifier.size(140.dp).align(Alignment.Center).offset(y = (-15).dp).graphicsLayer {
                            rotationZ = smoothedAngle; transformOrigin = TransformOrigin(0.5f, 0.84f)
                        })
                        Icon(imageVector = if (voiceModeEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Toggle Voice Feedback", tint = if (voiceModeEnabled) Color.Green else Color.Red,
                            modifier = Modifier.padding(12.dp).size(28.dp).align(Alignment.TopStart).clickable {
                                if (soundsLoaded) voiceModeEnabled = !voiceModeEnabled
                            })
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            BottomControls()
                        }
                    }
                }
            }
        }
        activityScope.launch { while (isActiveTuner) { delay(16); val smoothing = 0.1f; smoothedAngle += (rotationAngle - smoothedAngle) * smoothing } }
    }

    private fun setupSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()

        var loadedCount = 0
        val totalSounds = 4
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) { // Success
                loadedCount++
                if (loadedCount == totalSounds) {
                    activityScope.launch {
                        soundsLoaded = true
                        Log.d(TAG, "All sounds loaded successfully.")
                    }
                }
            } else {
                Log.e(TAG, "Error loading sound, status: $status")
            }
        }

        Log.d(TAG, "Starting to load sounds asynchronously...")
        soundUp = soundPool.load(this, R.raw.up, 1)
        soundDown = soundPool.load(this, R.raw.down, 1)
        soundIntune = soundPool.load(this, R.raw.intune, 1)
        soundTick = soundPool.load(this, R.raw.metronome_tick, 1)
    }

    override fun onStart() { super.onStart(); isActiveTuner = true }
    override fun onStop() { super.onStop(); isActiveTuner = false; stopMetronome() }
    override fun onDestroy() { super.onDestroy(); soundPool.release(); activityScope.cancel() }

    private fun requestPermissionAndStartTuner() {
        Log.d(TAG, "requestPermissionAndStartTuner called.")
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permission already granted. Starting tuner.")
                activityScope.launch { startTuner() }
            }
            else -> {
                Log.d(TAG, "Permission not granted. Requesting permission now.")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission granted by user.")
                activityScope.launch { startTuner() }
            } else {
                Log.w(TAG, "Permission denied by user.")
                statusText = "Permission Denied"
                statusColor = Color.Red
                Toast.makeText(this, "Microphone permission is required for the tuner to work.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun startTuner() {
        if (isRecording) return
        try {
            dispatcher = withContext(Dispatchers.IO) {
                AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, AUDIO_BUFFER_SIZE, BUFFER_OVERLAP)
            }

            val pitchDetectionHandler = PitchDetectionHandler { result, _ ->
                if(result.isPitched && result.probability > CONFIDENCE_THRESHOLD) {
                    activityScope.launch { updatePitch(result.pitch) }
                }
            }
            val pitchProcessor = PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.YIN, SAMPLE_RATE.toFloat(), AUDIO_BUFFER_SIZE, pitchDetectionHandler)
            val fftSize = 1024
            val fftProcessor = object: AudioProcessor {
                var fft = FFT(fftSize)
                private val amplitudes = FloatArray(fftSize)
                override fun process(audioEvent: AudioEvent): Boolean {
                    val audioBuffer = audioEvent.floatBuffer; val waveformUpdate = audioBuffer.clone(); val transformBuffer = audioBuffer.clone(); fft.forwardTransform(transformBuffer); fft.modulus(transformBuffer, amplitudes)
                    activityScope.launch { waveformData = waveformUpdate; val newMagnitudes = amplitudes.copyOf(fftSize / 2); magnitudes = newMagnitudes }
                    return true
                }
                override fun processingFinished() {}
            }
            dispatcher?.addAudioProcessor(HighPass(60f, SAMPLE_RATE.toFloat()))
            dispatcher?.addAudioProcessor(LowPassFS(1500f, SAMPLE_RATE.toFloat()))
            dispatcher?.addAudioProcessor(pitchProcessor)
            dispatcher?.addAudioProcessor(fftProcessor)

            audioThread = Thread(dispatcher, "AudioDispatcherThread").apply { isDaemon = true; start() }
            isRecording = true
            statusText = "Listening..."
            statusColor = Color.White
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tuner", e)
            isRecording = false
            statusText = "Tuner Error"
            statusColor = Color.Red
            if (e is IllegalStateException) {
                Toast.makeText(this, "Microphone might be in use by another app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopTuner() {
        dispatcher?.stop()
        audioThread?.interrupt()
        isRecording = false
        cents = 0f
        rotationAngle = 0f
        pitchBuffer.clear()
        detectedNote = "--"
        frequencyText = "0.00 Hz"
        statusText = "Press Start to Tune"
        statusColor = Color.White
        waveformData = floatArrayOf()
        magnitudes = floatArrayOf()
    }

    private fun updatePitch(pitch: Float) {
        pitchBuffer.add(pitch)
        if (pitchBuffer.size < PITCH_BUFFER_SIZE) return
        val stablePitch = pitchBuffer.sorted()[PITCH_BUFFER_SIZE / 2]
        pitchBuffer.removeAt(0)
        val nearestNote = getNearestNoteFrequency(stablePitch)
        if (nearestNote != null) {
            val (noteFreq, noteName) = nearestNote
            cents = 1200f * log2(stablePitch / noteFreq)
            rotationAngle = (cents.coerceIn(-50f, 50f) / 50f) * 90f
            detectedNote = noteName
            frequencyText = String.format("%.2f Hz", stablePitch)
            val isInTune = abs(cents) <= IN_TUNE_CENTS_THRESHOLD
            if(isInTune) {
                statusText = "$noteName (In Tune)"
                statusColor = Color.Green
                if(inTuneStartTime == 0L) inTuneStartTime = System.currentTimeMillis()
                if(System.currentTimeMillis() - inTuneStartTime >= IN_TUNE_DELAY_MS && !inTuneSoundPlayed) {
                    playFeedbackSound(soundIntune)
                    inTuneSoundPlayed = true
                }
            } else {
                inTuneStartTime = 0L
                inTuneSoundPlayed = false
                if(cents < 0) {
                    statusText = "$noteName (Tune Up)"
                    statusColor = Color(0xFFFFA000)
                    playFeedbackSound(soundUp)
                } else {
                    statusText = "$noteName (Tune Down)"
                    statusColor = Color(0xFFFFA000)
                    playFeedbackSound(soundDown)
                }
            }
        }
    }

    private fun playFeedbackSound(soundId: Int) {
        if (!soundsLoaded) return
        val now = System.currentTimeMillis()
        val cooldown = if(soundId == soundIntune) 0 else 1500
        if (voiceModeEnabled && isActiveTuner && now - lastFeedbackTime > cooldown) {
            if (soundId != 0) {
                soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                lastFeedbackTime = now
            }
        }
    }

    private fun startMetronome() {
        if(isMetronomeRunning || !soundsLoaded) return
        isMetronomeRunning = true
        metronomeJob = activityScope.launch(Dispatchers.Default) {
            while(isActive) { // Use isActive from the coroutine scope
                withContext(Dispatchers.Main) {
                    soundPool.play(soundTick, 1f, 1f, 0, 0, 1f)
                }
                val delayMillis = 60_000L / tempo
                delay(delayMillis)
            }
        }
    }

    private fun stopMetronome() {
        metronomeJob?.cancel()
        isMetronomeRunning = false
    }

    private fun getNearestNoteFrequency(pitch: Float): Pair<Float, String>? = noteFrequencies.minByOrNull { abs(pitch - it.first) }

    // --- MODIFIED: Save the new skins to SharedPreferences ---
    private fun randomizeSkins() {
        val newPedal = pedalImages.random()
        val newVdu = vduImages.random()
        selectedPedal.value = newPedal
        selectedVDU.value = newVdu

        // Save the new IDs to SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PREF_PEDAL_SKIN, newPedal)
            .putInt(PREF_VDU_SKIN, newVdu)
            .apply()
    }

    @Composable
    fun BottomControls() {
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Note: $detectedNote", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, blurRadius = 8f)))
                Text(text = frequencyText, fontSize = 16.sp, color = Color.LightGray, style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, blurRadius = 6f)))
                Text(text = statusText, fontSize = 20.sp, color = statusColor, style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, blurRadius = 8f)))
                Spacer(modifier = Modifier.height(16.dp))
                AudioVisualizer()
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (isRecording) stopTuner() else requestPermissionAndStartTuner() }) {
                        Text(if (isRecording) "Stop" else "Start")
                    }
                    Button(onClick = { randomizeSkins() }) { Text("Skin") }
                }
            }
        }
    }

    @Composable
    fun MetronomeControls(enabled: Boolean) {
        Surface(shape=RoundedCornerShape(12.dp),color=Color.Black.copy(alpha=0.7f),border=BorderStroke(1.dp,Color.Gray.copy(alpha=0.5f))){
            Row(modifier=Modifier.height(48.dp).padding(horizontal=8.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={if(tempo>40)tempo--}, enabled=enabled){Icon(Icons.Default.KeyboardArrowLeft,"",tint=Color.White)}
                Text("$tempo BPM",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=14.sp,modifier=Modifier.width(80.dp),textAlign=TextAlign.Center)
                IconButton(onClick={if(tempo<240)tempo++}, enabled=enabled){Icon(Icons.Default.KeyboardArrowRight,"",tint=Color.White)}
                Spacer(Modifier.width(4.dp))
                Divider(modifier=Modifier.height(24.dp).width(1.dp),color=Color.Gray)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick={timeSignatureIndex=(timeSignatureIndex-1+timeSignatures.size)%timeSignatures.size}, enabled=enabled){Icon(Icons.Default.KeyboardArrowLeft,"",tint=Color.White)}
                Text(timeSignatures[timeSignatureIndex],color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=14.sp,modifier=Modifier.width(40.dp),textAlign=TextAlign.Center)
                IconButton(onClick={timeSignatureIndex=(timeSignatureIndex+1)%timeSignatures.size}, enabled=enabled){Icon(Icons.Default.KeyboardArrowRight,"",tint=Color.White)}
                Spacer(modifier=Modifier.width(8.dp))
                Button(onClick={if(isMetronomeRunning)stopMetronome()else startMetronome()}, enabled=enabled, modifier=Modifier.fillMaxHeight(0.75f),contentPadding=PaddingValues(horizontal=10.dp),colors=ButtonDefaults.buttonColors(containerColor=if(isMetronomeRunning)Color(0xFFE53935)else Color(0xFF43A047))){
                    Text(if(isMetronomeRunning)"Stop" else "Start",fontSize=12.sp)
                }
            }
        }
    }

    @Composable fun AudioVisualizer(){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(modifier=Modifier.fillMaxWidth(0.9f).height(80.dp).background(Color.Black.copy(alpha=0.6f),RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).padding(8.dp),contentAlignment=Alignment.Center){
        when(visualizerMode){
            VisualizerMode.BARS->BarsVisualizer(Modifier.fillMaxSize(),magnitudes)
            VisualizerMode.WAVEFORM->WaveformVisualizer(Modifier.fillMaxSize(),waveformData)
            VisualizerMode.NONE->{Text("No Visualizer",color=Color.Gray,fontSize=12.sp)}
        }
    };Spacer(Modifier.height(8.dp));Button(onClick={val allModes=VisualizerMode.entries;val currentIndex=allModes.indexOf(visualizerMode);val nextIndex=(currentIndex+1)%allModes.size;visualizerMode=allModes[nextIndex]}){val vizName=visualizerMode.name.replace('_',' ').lowercase().replaceFirstChar{if(it.isLowerCase())it.titlecase()else it.toString()};Text("Visualizer: $vizName")}}}
    @Composable fun BarsVisualizer(modifier:Modifier=Modifier,magnitudes:FloatArray){Canvas(modifier=modifier){if(magnitudes.isNotEmpty()){val barCount=magnitudes.size/2;val barWidth=size.width/barCount;val maxMagnitude=(magnitudes.maxOrNull()?:1f).coerceAtLeast(0.5f);magnitudes.take(barCount).forEachIndexed{index,mag->val normalizedHeight=(mag/maxMagnitude).coerceIn(0f,1f);val barHeight=normalizedHeight*size.height;val color=lerp(Color.Green,Color.Red,normalizedHeight);drawRect(color=color,topLeft=Offset(x=index*barWidth,y=size.height-barHeight),size=Size(width=barWidth*0.8f,height=barHeight))}}}}
    @Composable fun WaveformVisualizer(modifier:Modifier=Modifier,data:FloatArray){Canvas(modifier=modifier){if(data.isNotEmpty()){val path=Path();val stepX=size.width/data.size;path.moveTo(0f,size.height/2);data.forEachIndexed{index,value->path.lineTo(index*stepX,(size.height/2)*(1-value))};drawPath(path=path,color=Color.Green,style=Stroke(width=2f))}}}
    @Composable fun LedTuningStrip(activeLedIndex:Int){Row(modifier=Modifier.shadow(elevation=8.dp,shape=RoundedCornerShape(6.dp),spotColor=Color.Green),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){(-5..5).forEach{index->val isActive=when{activeLedIndex<0->index>=activeLedIndex&&index<0;activeLedIndex>0->index<=activeLedIndex&&index>0;else->index==0};val color=when{index==0->Color(0xFF00C853);abs(index)in 1..2->Color(0xFFFFFF00);else->Color(0xFFD50000)};LedIndicator(isActive=isActive,activeColor=color);if(index<5){Spacer(modifier=Modifier.width(2.dp))}}}}
    @Composable fun LedIndicator(isActive:Boolean,activeColor:Color){val color=if(isActive)activeColor else Color.DarkGray.copy(alpha=0.5f);Box(modifier=Modifier.size(width=20.dp,height=24.dp).background(color,shape=RoundedCornerShape(4.dp)).border(width=1.dp,color=Color.Black.copy(alpha=0.3f),shape=RoundedCornerShape(4.dp)))}

}
