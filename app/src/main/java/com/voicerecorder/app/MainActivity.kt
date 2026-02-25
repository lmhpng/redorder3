package com.voicerecorder.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var fabRecord: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var waveformView: WaveformView

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var currentFile: File? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerSeconds = 0
    private val recordingsList = mutableListOf<Recording>()
    private lateinit var adapter: RecordingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
        loadRecordings()
    }

    private fun initViews() {
        fabRecord = findViewById(R.id.fabRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        rvRecordings = findViewById(R.id.rvRecordings)
        tvEmpty = findViewById(R.id.tvEmpty)
        waveformView = findViewById(R.id.waveformView)

        adapter = RecordingAdapter(
            recordingsList,
            onPlay = { playRecording(it) },
            onDelete = { deleteRecording(it) },
            onTranscribe = { transcribeRecording(it) },
            onSummarize = { showSummary(it) }
        )
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter
        fabRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun getPrefs() = getSharedPreferences("recordings_meta", MODE_PRIVATE)
    private fun getRecordingsDir() = File(filesDir, "recordings").also { if (!it.exists()) it.mkdirs() }

    private fun loadRecordings() {
        recordingsList.clear()
        val prefs = getPrefs()
        getRecordingsDir().listFiles()
            ?.filter { it.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                recordingsList.add(Recording(
                    id = file.name,
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    duration = getDuration(file),
                    date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                    transcript = prefs.getString("transcript_${file.name}", null),
                    summary = prefs.getString("summary_${file.name}", null)
                ))
            }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (recordingsList.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvRecordingCount)?.text = "${recordingsList.size} 条"
    }

    private fun getDuration(file: File): String {
        return try {
            val mp = MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
            val dur = mp.duration / 1000
            mp.release()
            "%02d:%02d".format(dur / 60, dur % 60)
        } catch (e: Exception) { "00:00" }
    }

    private fun startRecording() {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentFile = File(getRecordingsDir(), "录音_$dateStr.m4a")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(currentFile!!.absolutePath)
            prepare(); start()
        }
        isRecording = true
        fabRecord.setImageResource(R.drawable.ic_stop)
        tvStatus.text = "● 正在录音..."
        tvStatus.setTextColor(0xFFE53935.toInt())
        waveformView.startAnimation()
        timerSeconds = 0
        startTimer()
    }

    private fun stopRecording() {
        try { mediaRecorder?.apply { stop(); release() } } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        timerHandler.removeCallbacksAndMessages(null)
        waveformView.stopAnimation()
        fabRecord.setImageResource(R.drawable.ic_mic)
        tvStatus.text = "点击开始录音"
        tvStatus.setTextColor(0xFF888888.toInt())
        tvTimer.text = "00:00"
        currentFile?.takeIf { it.exists() && it.length() > 0 }?.let {
            Toast.makeText(this, "✅ 录音已保存", Toast.LENGTH_SHORT).show()
            loadRecordings()
        }
    }

    private fun startTimer() {
        timerHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRecording) return
                timerSeconds++
                tvTimer.text = "%02d:%02d".format(timerSeconds / 60, timerSeconds % 60)
                try { mediaRecorder?.let { waveformView.updateAmplitude(it.maxAmplitude) } } catch (e: Exception) {}
                timerHandler.postDelayed(this, 100)
            }
        }, 100)
    }

    private fun playRecording(recording: Recording) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(recording.filePath)
            prepare(); start()
            setOnCompletionListener { adapter.setPlaying(null) }
        }
        adapter.setPlaying(recording.id)
    }

    private fun deleteRecording(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("删除录音")
            .setMessage("确定要删除「${recording.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
                mediaPlayer = null
                File(recording.filePath).delete()
                getPrefs().edit()
                    .remove("transcript_${recording.id}")
                    .remove("summary_${recording.id}").apply()
                loadRecordings()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun saveTranscript(recordingId: String, text: String) {
        getPrefs().edit()
            .putString("transcript_$recordingId", text)
            .remove("summary_$recordingId") // 文字变了清除旧总结
            .apply()
    }

    // 语音转文字（硅基流动 SenseVoiceSmall）
    private fun transcribeRecording(recording: Recording) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "录音文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("🎙️ 语音转文字")
            .setMessage("正在识别中，请稍候...\n预计需要5~15秒 ⏳")
            .setCancelable(false).create()
        loadingDialog.show()

        lifecycleScope.launch {
            try {
                val transcript = IFlytekService.transcribeAudio(file)
                loadingDialog.dismiss()
                saveTranscript(recording.id, transcript)
                loadRecordings()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✅ 转文字完成")
                    .setMessage(transcript)
                    .setPositiveButton("立即AI总结 🤖") { _, _ ->
                        val updated = recordingsList.find { it.id == recording.id }
                        if (updated != null) generateAiSummary(updated)
                    }
                    .setNeutralButton("复制") { _, _ -> copyToClipboard(transcript) }
                    .setNegativeButton("关闭", null).show()
            } catch (e: Exception) {
                loadingDialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("❌ 识别失败")
                    .setMessage("原因：${e.message}\n\n可手动输入文字后生成AI总结。")
                    .setPositiveButton("手动输入") { _, _ -> showManualInputDialog(recording) }
                    .setNegativeButton("关闭", null).show()
            }
        }
    }

    private fun showManualInputDialog(recording: Recording) {
        val editText = android.widget.EditText(this).apply {
            hint = "在此输入文字内容..."
            minLines = 4; maxLines = 10
            setPadding(40, 20, 40, 20)
            setText(recording.transcript ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ 手动输入文字")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    saveTranscript(recording.id, text)
                    loadRecordings()
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // AI总结（硅基流动 DeepSeek-V3 真正AI总结）
    private fun showSummary(recording: Recording) {
        val current = recordingsList.find { it.id == recording.id } ?: recording
        if (current.transcript.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("💡 提示")
                .setMessage("请先点击「📝 转文字」将语音转为文字，再生成AI总结。")
                .setPositiveButton("去转文字") { _, _ -> transcribeRecording(recording) }
                .setNegativeButton("取消", null).show()
            return
        }
        generateAiSummary(current)
    }

    private fun generateAiSummary(recording: Recording) {
        val transcript = recording.transcript ?: return
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("🤖 AI总结生成中...")
            .setMessage("DeepSeek 正在分析内容，请稍候...")
            .setCancelable(false).create()
        loadingDialog.show()

        lifecycleScope.launch {
            try {
                val summary = IFlytekService.generateSummary(transcript)
                getPrefs().edit().putString("summary_${recording.id}", summary).apply()
                loadingDialog.dismiss()
                loadRecordings()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🤖 AI总结 - ${recording.name}")
                    .setMessage(summary)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("复制") { _, _ -> copyToClipboard(summary) }.show()
            } catch (e: Exception) {
                loadingDialog.dismiss()
                Toast.makeText(this@MainActivity, "总结失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        if (isRecording) stopRecording()
    }
}
