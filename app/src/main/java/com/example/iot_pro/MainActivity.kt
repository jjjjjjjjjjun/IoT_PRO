package com.example.iotcontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.iotcontrol.databinding.ActivityMainBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.firebase.database.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: DatabaseReference
    private val gemini = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = "AIzaSyBcb7ZxFOKYkg272ahUHfQpe5hwb60cjoQ"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var luxRunnable: Runnable? = null
    private val LUX_INTERVAL = 500L // 0.5초

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            text?.let { analyzeWithGemini(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseDatabase.getInstance("https://iotbackend-827aa-default-rtdb.firebaseio.com/")
            .getReference("smart_home/device_01")

        setupFirebaseListeners()
        setupControls()
        setupVoice()
        setupLuxUpdater()
    }

    private fun setupFirebaseListeners() {
        db.child("sensors").child("lux").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lux = snapshot.getValue(Long::class.java) ?: 0L
                lastLuxValue = lux
                if (!binding.switchLightSensor.isChecked) {
                    binding.tvLuminance.text = "-- lux"
                    binding.progressLux.progress = 0
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private var lastLuxValue: Long = 0

    private fun setupLuxUpdater() {
        binding.switchLightSensor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startLuxUpdates()
                updateLuxDisplay()
            } else {
                stopLuxUpdates()
                binding.tvLuminance.text = "-- lux"
                binding.progressLux?.progress = 0
            }
        }
    }

    private fun startLuxUpdates() {
        stopLuxUpdates()
        luxRunnable = object : Runnable {
            override fun run() {
                updateLuxDisplay()
                handler.postDelayed(this, LUX_INTERVAL)
            }
        }
        handler.post(luxRunnable!!)
    }

    private fun stopLuxUpdates() {
        luxRunnable?.let { handler.removeCallbacks(it) }
        luxRunnable = null
    }

    private fun updateLuxDisplay() {
        binding.tvLuminance.text = "$lastLuxValue lux"
        binding.progressLux?.progress = lastLuxValue.coerceIn(0, 1000).toInt()
    }

    private fun setupControls() {
        // LCD 전송 + 토스트
        binding.btnLcdSubmit.setOnClickListener {
            val text = binding.etLcdText.text.toString().trim()
            if (text.isNotEmpty()) {
                sendLcd(text, binding.switchLcd.isChecked)
                Toast.makeText(this, "전송되었습니다", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchLcd.setOnCheckedChangeListener { _, isChecked ->
            sendLcd(binding.etLcdText.text.toString(), isChecked)
        }

        // RGB 슬라이더
        val updateColor = {
            val r = binding.seekBarRed.progress
            val g = binding.seekBarGreen.progress
            val b = binding.seekBarBlue.progress
            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            binding.viewColorPreview.setBackgroundColor(Color.rgb(r, g, b))
            sendRgb(r, g, b)
            if (r + g + b > 0) binding.switchLed.isChecked = true
        }

        listOf(binding.seekBarRed, binding.seekBarGreen, binding.seekBarBlue).forEach {
            it.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateColor()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("controls/led/enabled").setValue(isChecked)
            if (!isChecked) setRgb(0, 0, 0)
        }
    }

    private fun setupVoice() {
        binding.btnVoiceControl.setOnClickListener { startVoice() }
    }

    private fun startVoice() {
        voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "불 켜줘, 화면 꺼줘, 센서 켜줘")
        })
    }

    private fun analyzeWithGemini(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                "$text" → 다음 중 하나만 JSON:
                - 불 켜줘, LED 켜줘 → {"action":"led_on"}
                - 불 꺼줘 → {"action":"led_off"}
                - 화면 켜줘 → {"action":"lcd_on"}
                - 화면 꺼줘 → {"action":"lcd_off"}
                - 센서 켜줘 → {"action":"sensor_on"}
                - 센서 꺼줘 → {"action":"sensor_off"}
                - LCD에 (.*) → {"action":"lcd_text","text":"$1"}
                """.trimIndent()

                val response = gemini.generateContent(prompt)
                val json = response.text?.trim()?.removeSurrounding("```json", "```")?.trim() ?: "{}"
                Log.d("Gemini", json)

                val map = Gson().fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()

                withContext(Dispatchers.Main) {
                    when (map["action"] as? String) {
                        "led_on" -> {
                            binding.switchLed.isChecked = true
                            Toast.makeText(this@MainActivity, "LED 켜짐", Toast.LENGTH_SHORT).show()
                        }
                        "led_off" -> {
                            binding.switchLed.isChecked = false
                            Toast.makeText(this@MainActivity, "LED 꺼짐", Toast.LENGTH_SHORT).show()
                        }
                        "lcd_on" -> {
                            binding.switchLcd.isChecked = true
                            Toast.makeText(this@MainActivity, "화면 켜짐", Toast.LENGTH_SHORT).show()
                        }
                        "lcd_off" -> {
                            binding.switchLcd.isChecked = false
                            Toast.makeText(this@MainActivity, "화면 꺼짐", Toast.LENGTH_SHORT).show()
                        }
                        "sensor_on" -> {
                            binding.switchLightSensor.isChecked = true
                            Toast.makeText(this@MainActivity, "조도 센서 켜짐", Toast.LENGTH_SHORT).show()
                        }
                        "sensor_off" -> {
                            binding.switchLightSensor.isChecked = false
                            Toast.makeText(this@MainActivity, "조도 센서 꺼짐", Toast.LENGTH_SHORT).show()
                        }
                        "lcd_text" -> {
                            val txt = (map["text"] as? String)?.take(32) ?: "Hello"
                            binding.etLcdText.setText(txt)
                            sendLcd(txt, true)
                            Toast.makeText(this@MainActivity, "LCD에 '$txt' 표시", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Gemini", "오류", e)
            }
        }
    }

    private fun sendRgb(r: Int, g: Int, b: Int) {
        db.child("controls/rgb").setValue(mapOf("r" to r, "g" to g, "b" to b))
    }

    private fun sendLcd(text: String, enabled: Boolean) {
        val clean = text.trim().take(32)
        db.child("display").setValue(mapOf("text" to clean, "enabled" to enabled))
        db.child("controls/lcd/enabled").setValue(enabled)
    }

    private fun setRgb(r: Int, g: Int, b: Int) {
        binding.seekBarRed.progress = r
        binding.seekBarGreen.progress = g
        binding.seekBarBlue.progress = b
        sendRgb(r, g, b)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLuxUpdates()
    }
}