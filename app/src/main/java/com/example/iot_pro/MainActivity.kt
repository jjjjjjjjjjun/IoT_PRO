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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: DatabaseReference
    private val VOICE_CODE = 1001

    // Gemini 설정
    private val gemini = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = "YOUR_GEMINI_API_KEY"  // 여기에 발급받은 키 넣기
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase URL 정확히 설정
        db = FirebaseDatabase.getInstance("https://iotbackend-827aa-default-rtdb.firebaseio.com/")
            .reference

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // === LCD 텍스트 전송 ===
        binding.btnLcdSubmit.setOnClickListener {
            val text = binding.etLcdText.text.toString()
            sendToFirebase("lcd", mapOf("text" to text, "enabled" to true))
        }

        // === RGB 실시간 전송 + 미리보기 ===
        val updateColor: () -> Unit = {
            val r = binding.seekBarRed.progress
            val g = binding.seekBarGreen.progress
            val b = binding.seekBarBlue.progress

            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            binding.viewColorPreview.setBackgroundColor(Color.rgb(r, g, b))

            sendRgbToFirebase(r, g, b)
        }

        binding.seekBarRed.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarGreen.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarBlue.setOnSeekBarChangeListener(SeekBarListener(updateColor))

        // === LED 스위치 ===
        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("smart_home")
                .child("device_01")
                .child("controls")
                .child("led")
                .child("enabled")
                .setValue(isChecked)
                .addOnSuccessListener { Log.d("FB", "LED 전송 성공: $isChecked") }
                .addOnFailureListener { Log.e("FB", "전송 실패", it) }
        }

        // === LCD 스위치 ===
        binding.switchLcd.setOnCheckedChangeListener { _, isChecked ->
            sendToFirebase("lcd", mapOf("enabled" to isChecked))
        }

        // === 음성 인식 전용 버튼 (LED 아래) ===
        binding.btnVoiceControl.setOnClickListener {
            startVoiceRecognition()
        }

        // === 기존 LCD 스위치 길게 누르기 (옵션) ===
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

    // === 음성 결과 처리 ===
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_CODE && resultCode == RESULT_OK) {
            val spokenText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: return
            binding.etLcdText.setText(spokenText)
            analyzeWithGemini(spokenText)
        }
    }

    // === Gemini로 음성 분석 ===
    private fun analyzeWithGemini(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                    사용자가 말한 명령: "$text"
                    가능한 명령:
                    - "불 켜", "LED 켜", "전구 켜", "불 켜줘" → {"action": "white_light_on"}
                    - "불 꺼", "LED 꺼" → {"action": "led_off"}
                    - "LCD에 [텍스트]" → {"action": "lcd_text", "text": "입력된 텍스트"}
                    - "RGB 빨강", "빨간색" → {"action": "rgb", "r":255, "g":0, "b":0}
                    - "RGB 초록", "초록색" → {"action": "rgb", "r":0, "g":255, "b":0}
                    - "RGB 파랑", "파란색" → {"action": "rgb", "r":0, "g":0, "b":255}
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
                            // 백색등 ON
                            binding.seekBarRed.progress = 255
                            binding.seekBarGreen.progress = 255
                            binding.seekBarBlue.progress = 255
                            sendRgbToFirebase(255, 255, 255)
                            Log.d("Gemini", "백색등 켜짐!")
                        }
                        "led_off" -> {
                            db.child("smart_home").child("device_01").child("controls").child("led").child("enabled")
                                .setValue(false)
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
                        }
                        else -> {  // ← 이 줄 추가! 모든 나머지 케이스 처리
                            Log.d("Gemini", "알 수 없는 명령: ${map["action"]}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Gemini", "오류: ${e.message}")
            }
        }
    }

    // === JSON 파싱 (Gemini 응답용) ===
    private fun parseGeminiJson(text: String): Map<String, Any> {
        return try {
            val cleaned = text.removePrefix("```json").removeSuffix("```").trim()
            com.google.gson.Gson().fromJson(cleaned, Map::class.java) as? Map<String, Any> ?: mapOf()
        } catch (e: Exception) {
            mapOf("action" to "unknown")
        }
    }

    // === Firebase 전송 공통 함수 ===
    private fun sendToFirebase(path: String, value: Any) {
        db.child("smart_home").child("device_01").child("controls").child(path)
            .setValue(value)
            .addOnSuccessListener { Log.d("Firebase", "$path 전송 성공") }
            .addOnFailureListener { Log.e("Firebase", "전송 실패: $it") }
    }

    // === RGB 전용 전송 함수 ===
    private fun sendRgbToFirebase(r: Int, g: Int, b: Int) {
        db.child("smart_home")
            .child("device_01")
            .child("controls")
            .child("rgb")
            .setValue(mapOf("r" to r, "g" to g, "b" to b, "enabled" to true))
            .addOnSuccessListener { Log.d("Firebase", "RGB 전송 성공") }
    }

    // === SeekBar 리스너 ===
    inner class SeekBarListener(private val update: () -> Unit) :
        android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = update()
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }
}