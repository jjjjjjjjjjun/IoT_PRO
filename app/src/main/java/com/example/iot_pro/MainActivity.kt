package com.example.iotcontrol

import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
    private val LUX_INTERVAL = 500L
    private var lastLuxValue: Long = 0

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
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
        setupDarkModeSwitch()
    }

    /** ---------------------- Firebase Lux ---------------------- **/
    private fun setupFirebaseListeners() {
        db.child("sensors").child("lux").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lux = snapshot.getValue(Long::class.java) ?: 0L
                lastLuxValue = lux
                if (!binding.switchLightSensor.isChecked) {
                    updateLuxDisplay()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("DBError", "Failed to read lux value.", error.toException())
            }
        })
    }

    private fun setupLuxUpdater() {
        binding.switchLightSensor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startLuxUpdates() else stopLuxUpdates()
            updateLuxDisplay()
        }
        updateLuxDisplay()
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
        if (binding.switchLightSensor.isChecked) {
            binding.tvLuminance.text = "$lastLuxValue lux"
            val progress = lastLuxValue.coerceIn(0, 1000).toInt()
            binding.progressLux.progress = progress
            binding.tvLuxStatus.text = when {
                lastLuxValue < 100 -> "어두움"
                lastLuxValue < 500 -> "보통"
                else -> "밝음"
            }
        } else {
            binding.tvLuminance.text = "--- lux"
            binding.progressLux.progress = 0
            binding.tvLuxStatus.text = "측정 중지"
        }
    }

    /** ---------------------- LED + HSV ---------------------- **/
    private fun setupControls() {
        val hsvSeekBars = listOf(binding.seekBarHue, binding.seekBarSaturation, binding.seekBarValue)

        hsvSeekBars.forEach { seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateColorFromSliders()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("controls/led/enabled").setValue(isChecked)
            if (!isChecked) {
                setRgb(0, 0, 0)
            } else {
                if (binding.seekBarValue.progress == 0) {
                    binding.seekBarSaturation.progress = 0
                    binding.seekBarValue.progress = 100
                }
                updateColorFromSliders()
            }
        }
    }

    private fun updateColorFromSliders() {
        val h = binding.seekBarHue.progress.toFloat()
        val s = binding.seekBarSaturation.progress.toFloat() / 100f
        val v = binding.seekBarValue.progress.toFloat() / 100f

        val rgb = Color.HSVToColor(floatArrayOf(h, s, v))
        binding.viewColorPreview.setBackgroundColor(rgb)

        val r = Color.red(rgb)
        val g = Color.green(rgb)
        val b = Color.blue(rgb)

        sendRgb(r, g, b)

        // LED 스위치 자동 연동
        binding.switchLed.setOnCheckedChangeListener(null)
        binding.switchLed.isChecked = v > 0
        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("controls/led/enabled").setValue(isChecked)
            if (!isChecked) setRgb(0, 0, 0) else updateColorFromSliders()
        }
    }

    /** ---------------------- Gemini Voice ---------------------- **/
    private fun setupVoice() {
        binding.btnVoiceControl.setOnClickListener { startVoice() }

        binding.tvGeminiButtonText.post {
            val paint = binding.tvGeminiButtonText.paint
            val text = binding.tvGeminiButtonText.text.toString()
            val width = paint.measureText(text)
            val textShader = LinearGradient(
                0f, 0f, width, binding.tvGeminiButtonText.textSize,
                intArrayOf(Color.parseColor("#FFD700"), Color.parseColor("#FF4500"), Color.parseColor("#8A2BE2")),
                null, Shader.TileMode.CLAMP
            )
            binding.tvGeminiButtonText.paint.shader = textShader
            binding.tvGeminiButtonText.invalidate()
        }
    }

    private fun startVoice() {
        voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "불 켜줘, 불 꺼줘, 센서 켜줘, 빨간색으로 바꿔줘")
        })
    }

    private fun analyzeWithGemini(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                    "$text" → 다음 중 하나만 JSON:
                    - 불 켜줘, LED 켜줘 → {"action":"led_on"}
                    - 불 꺼줘 → {"action":"led_off"}
                    - 센서 켜줘 → {"action":"sensor_on"}
                    - 센서 꺼줘 → {"action":"sensor_off"}
                    - (.*) 색으로 바꿔줘, (.*) 색으로 설정해줘 → {"action":"led_color","color":"$1"}
                """.trimIndent()

                val response = gemini.generateContent(prompt)
                val json =
                    response.text?.trim()?.removeSurrounding("```json", "```")?.trim() ?: "{}"
                Log.d("Gemini", json)

                val map = Gson().fromJson<Map<String, Any>>(json,
                    object : TypeToken<Map<String, Any>>() {}.type
                ) ?: emptyMap()

                withContext(Dispatchers.Main) {
                    when (map["action"] as? String) {
                        "led_on" -> {
                            binding.switchLed.isChecked = true
                            if (binding.seekBarValue.progress == 0) setRgb(255, 255, 255)
                            Toast.makeText(this@MainActivity, "LED 켜짐", Toast.LENGTH_SHORT).show()
                        }
                        "led_off" -> {
                            binding.switchLed.isChecked = false
                            Toast.makeText(this@MainActivity, "LED 꺼짐", Toast.LENGTH_SHORT).show()
                        }
                        "sensor_on" -> {
                            binding.switchLightSensor.isChecked = true
                            Toast.makeText(this@MainActivity, "조도 센서 켜짐", Toast.LENGTH_SHORT).show()
                        }
                        "sensor_off" -> {
                            binding.switchLightSensor.isChecked = false
                            Toast.makeText(this@MainActivity, "조도 센서 꺼짐", Toast.LENGTH_SHORT).show()
                        }
                        "led_color" -> {
                            val colorName = map["color"] as? String ?: "흰색"
                            val hsvValues = colorNameToHsv(colorName)
                            val h = hsvValues[0].toFloat()
                            val s = hsvValues[1].toFloat() / 100f
                            val v = hsvValues[2].toFloat() / 100f
                            val rgb = Color.HSVToColor(floatArrayOf(h, s, v))
                            setRgb(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                            binding.switchLed.isChecked = true
                            Toast.makeText(this@MainActivity, "$colorName LED로 변경", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Gemini", "오류", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "명령 분석 중 오류", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** ---------------------- Utility ---------------------- **/
    private fun colorNameToHsv(colorName: String): IntArray {
        return when (colorName.replace(" ", "")) {
            "빨간색","레드" -> intArrayOf(0,100,100)
            "주황색","오렌지" -> intArrayOf(30,100,100)
            "노란색","옐로우" -> intArrayOf(60,100,100)
            "초록색","그린" -> intArrayOf(120,100,100)
            "하늘색","스카이블루" -> intArrayOf(180,100,100)
            "파란색","블루" -> intArrayOf(240,100,100)
            "보라색","퍼플","자주색" -> intArrayOf(270,100,100)
            "분홍색","핑크" -> intArrayOf(300,100,100)
            "흰색","화이트" -> intArrayOf(0,0,100)
            else -> intArrayOf(0,0,100)
        }
    }

    private fun sendRgb(r: Int, g: Int, b: Int) {
        db.child("controls/rgb").setValue(mapOf("r" to r, "g" to g, "b" to b))
    }

    private fun setRgb(r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)

        binding.seekBarHue.progress = hsv[0].toInt()
        binding.seekBarSaturation.progress = (hsv[1]*100).toInt()
        binding.seekBarValue.progress = (hsv[2]*100).toInt()

        binding.viewColorPreview.setBackgroundColor(Color.rgb(r, g, b))
        sendRgb(r, g, b)
    }

    private fun setupDarkModeSwitch() {
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 다크 모드
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                binding.imgSun.alpha = 0.3f      // 태양 아이콘은 희미하게
                binding.imgMoon.alpha = 1f       // 달 아이콘 강조
                binding.btnVoiceControl.setBackgroundColor(Color.parseColor("#1976D2"))
                binding.tvGeminiButtonText.setTextColor(Color.WHITE)
            } else {
                // 라이트 모드
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                binding.imgSun.alpha = 1f
                binding.imgMoon.alpha = 0.3f
                binding.btnVoiceControl.setBackgroundColor(Color.parseColor("#1976D2"))
                binding.tvGeminiButtonText.setTextColor(Color.WHITE)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopLuxUpdates()
    }
}

