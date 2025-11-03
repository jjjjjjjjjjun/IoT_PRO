package com.example.iotcontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.iotcontrol.databinding.ActivityMainBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: DatabaseReference
    private val VOICE_CODE = 1001

    // === Gemini 설정 ===
    private val gemini = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = "AIzaSyBcb7ZxFOKYkg272ahUHfQpe5hwb60cjoQ"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase 초기화
        db = FirebaseDatabase.getInstance("https://iotbackend-827aa-default-rtdb.firebaseio.com/")
            .reference

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // === LCD 텍스트 전송 ===
        binding.btnLcdSubmit.setOnClickListener {
            val text = binding.etLcdText.text.toString()
            sendToFirebase("lcd", mapOf("text" to text, "enabled" to true))
        }

        // === RGB 실시간 전송 + 자동 스위치 ON ===
        val updateColor: () -> Unit = {
            val r = binding.seekBarRed.progress
            val g = binding.seekBarGreen.progress
            val b = binding.seekBarBlue.progress

            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            binding.viewColorPreview.setBackgroundColor(Color.rgb(r, g, b))

            sendRgbToFirebase(r, g, b)

            // 슬라이더 움직이면 무조건 스위치 ON!
            if (!binding.switchLed.isChecked && (r > 0 || g > 0 || b > 0)) {
                binding.switchLed.isChecked = true
                db.child("smart_home")
                    .child("device_01")
                    .child("controls")
                    .child("led")
                    .child("enabled")
                    .setValue(true)
                Log.d("FB", "슬라이더로 LED 자동 ON")
            }
        }

        binding.seekBarRed.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarGreen.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarBlue.setOnSeekBarChangeListener(SeekBarListener(updateColor))

        // === LED 스위치 (OFF 시 슬라이더 0으로!) ===
        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("smart_home")
                .child("device_01")
                .child("controls")
                .child("led")
                .child("enabled")
                .setValue(isChecked)
                .addOnSuccessListener {
                    Log.d("FB", "LED 전송 성공: $isChecked")
                    if (!isChecked) {
                        // OFF면 슬라이더도 0으로
                        binding.seekBarRed.progress = 0
                        binding.seekBarGreen.progress = 0
                        binding.seekBarBlue.progress = 0
                        sendRgbToFirebase(0, 0, 0)
                    }
                }
                .addOnFailureListener { Log.e("FB", "전송 실패", it) }
        }

        // === LCD 스위치 ===
        binding.switchLcd.setOnCheckedChangeListener { _, isChecked ->
            sendToFirebase("lcd", mapOf("enabled" to isChecked))
        }

        // === 음성 인식 ===
        binding.btnVoiceControl.setOnClickListener { startVoiceRecognition() }
        binding.switchLcd.setOnLongClickListener {
            startVoiceRecognition()
            true
        }
    }

    // === 음성 인식 시작 ===
    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "명령을 말하세요")
        }
        startActivityForResult(intent, VOICE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_CODE && resultCode == RESULT_OK) {
            val spokenText =
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: return
            analyzeWithGemini(spokenText)
        }
    }

    // === Gemini 분석 ===
    private fun analyzeWithGemini(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                사용자가 말한 명령: "$text"
                가능한 명령:
                - "불 켜", "불 켜줘", "전구 켜", "LED 켜" → {"action": "white_light_on"}
                - "불 꺼", "불 꺼줘", "LED 꺼", "전구 꺼" → {"action": "led_off"}
                - "LCD에 [텍스트]" → {"action": "lcd_text", "text": "입력된 텍스트"}
                - "빨간색", "빨강" → {"action": "rgb", "r":255, "g":0, "b":0}
                - "초록색", "초록" → {"action": "rgb", "r":0, "g":255, "b":0}
                - "파란색", "파랑" → {"action": "rgb", "r":0, "g":0, "b":255}
                - 알 수 없으면 {"action": "unknown"}
                JSON 형식으로만 응답.
            """.trimIndent()

                val response = gemini.generateContent(prompt)
                val jsonResult = response.text?.trim() ?: "{}"
                Log.d("Gemini", "분석 결과: $jsonResult")

                val map = parseGeminiJson(jsonResult)

                withContext(Dispatchers.Main) {
                    when (map["action"]) {
                        "white_light_on" -> {
                            binding.seekBarRed.progress = 255
                            binding.seekBarGreen.progress = 255
                            binding.seekBarBlue.progress = 255
                            sendRgbToFirebase(255, 255, 255)
                            binding.switchLed.isChecked = true
                            db.child("smart_home").child("device_01").child("controls")
                                .child("led").child("enabled").setValue(true)
                            Log.d("Gemini", "백색등 + 스위치 ON!")
                        }

                        "led_off" -> {
                            binding.seekBarRed.progress = 0
                            binding.seekBarGreen.progress = 0
                            binding.seekBarBlue.progress = 0
                            sendRgbToFirebase(0, 0, 0)
                            binding.switchLed.isChecked = false
                            db.child("smart_home").child("device_01").child("controls")
                                .child("led").child("enabled").setValue(false)
                            Log.d("Gemini", "RGB OFF + 스위치 OFF!")
                        }

                        "lcd_text" -> {
                            val lcdText = map["text"] as? String ?: ""
                            binding.etLcdText.setText(lcdText)
                            sendToFirebase("lcd", mapOf("text" to lcdText, "enabled" to true))
                        }

                        "rgb" -> {
                            val r = (map["r"] as? Number)?.toInt() ?: 0
                            val g = (map["g"] as? Number)?.toInt() ?: 0
                            val b = (map["b"] as? Number)?.toInt() ?: 0

                            binding.seekBarRed.progress = r.coerceIn(0, 255)
                            binding.seekBarGreen.progress = g.coerceIn(0, 255)
                            binding.seekBarBlue.progress = b.coerceIn(0, 255)

                            sendRgbToFirebase(r, g, b)

                            // ✅ Kotlin이 if를 식으로 착각하지 않게 run { }로 감쌈
                            run {
                                if (r > 0 || g > 0 || b > 0) {
                                    binding.switchLed.isChecked = true
                                    db.child("smart_home")
                                        .child("device_01")
                                        .child("controls")
                                        .child("led")
                                        .child("enabled")
                                        .setValue(true)
                                }
                            }
                        }


                        else -> {
                            Log.d("Gemini", "알 수 없는 명령: ${map["action"]}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Gemini", "오류: ${e.message}")
            }
        }
    }


    // === JSON 파싱 ===
    private fun parseGeminiJson(text: String): Map<String, Any> {
        return try {
            val cleaned = text.removePrefix("```json").removeSuffix("```").trim()
            Gson().fromJson(cleaned, Map::class.java) as? Map<String, Any> ?: mapOf()
        } catch (e: Exception) {
            mapOf("action" to "unknown")
        }
    }

    // === Firebase 전송 ===
    private fun sendToFirebase(path: String, value: Any) {
        db.child("smart_home").child("device_01").child("controls").child(path)
            .setValue(value)
            .addOnSuccessListener { Log.d("Firebase", "$path 전송 성공") }
            .addOnFailureListener { Log.e("Firebase", "전송 실패", it) }
    }

    private fun sendRgbToFirebase(r: Int, g: Int, b: Int) {
        db.child("smart_home")
            .child("device_01")
            .child("controls")
            .child("rgb")
            .setValue(mapOf("r" to r, "g" to g, "b" to b))
            .addOnSuccessListener { Log.d("Firebase", "RGB 전송 성공") }
    }

    // === SeekBar 리스너 ===
    inner class SeekBarListener(private val update: () -> Unit) :
        android.widget.SeekBar.OnSeekBarChangeListener {

        override fun onProgressChanged(
            seekBar: android.widget.SeekBar?,
            progress: Int,
            fromUser: Boolean
        ) {
            update()
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }
}
