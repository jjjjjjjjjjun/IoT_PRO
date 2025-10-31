package com.example.iotcontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.appcompat.app.AppCompatActivity
import com.example.iotcontrol.databinding.ActivityMainBinding
import com.google.firebase.database.FirebaseDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseDatabase.getInstance().reference
    private val VOICE_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ LCD 전송 버튼
        binding.btnLcdSubmit.setOnClickListener {
            val text = binding.etLcdText.text.toString()
            db.child("device/lcd/text").setValue(text)
        }

        // ✅ RGB 미리보기 (색 적용)
        val updateColor: () -> Unit = {
            val r = binding.seekBarRed.progress
            val g = binding.seekBarGreen.progress
            val b = binding.seekBarBlue.progress

            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            binding.viewColorPreview.setBackgroundColor(Color.rgb(r, g, b))
        }

        binding.seekBarRed.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarGreen.setOnSeekBarChangeListener(SeekBarListener(updateColor))
        binding.seekBarBlue.setOnSeekBarChangeListener(SeekBarListener(updateColor))

        // ✅ LED Switch
        binding.switchLed.setOnCheckedChangeListener { _, isChecked ->
            db.child("device/led/enabled").setValue(isChecked)
            if (isChecked) updateColor()
        }

        // ✅ LCD Switch
        binding.switchLcd.setOnCheckedChangeListener { _, isChecked ->
            db.child("device/lcd/enabled").setValue(isChecked)
        }

        // ✅ 음성인식 → LCD 반영
        binding.switchLcd.setOnLongClickListener {
            startVoiceRecognition()
            true
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        startActivityForResult(intent, VOICE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VOICE_CODE && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: return
            binding.etLcdText.setText(text)
            db.child("device/lcd/text").setValue(text)
        }
    }

    // SeekBar 리스너 공통 처리
    inner class SeekBarListener(private val update: () -> Unit) : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = update()
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }
}
