import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import java.util.concurrent.ConcurrentLinkedQueue


const val sampleRate = 8000
const val channelConfig = AudioFormat.CHANNEL_IN_MONO
const val sampleFormat = AudioFormat.ENCODING_PCM_16BIT
val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, sampleFormat)

class Echoback {
    private var recorder: AudioRecord? = null
    private val recorderHandlerThread = HandlerThread("recorder")
    private var recorderHandler : Handler
    private lateinit var recorderCallback : Runnable
    private val tracker: AudioTrack
    private val trackerHandlerThread = HandlerThread("tracker")
    private var trackerHandler : Handler
    private lateinit var trackerCallback : Runnable
    private val echoBuffer: ConcurrentLinkedQueue<Pair<Long, ByteArray>> = ConcurrentLinkedQueue()
    private val activity: Activity
    private val mediaSession : MediaSession

    constructor(activity: Activity) {
        this.activity = activity
        recorderHandlerThread.start()
        trackerHandlerThread.start()
        recorderHandler = Handler(recorderHandlerThread.looper)
        trackerHandler = Handler(trackerHandlerThread.looper)

        if (ActivityCompat.checkSelfPermission(
                activity.applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        } else {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(sampleFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }

        tracker = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(sampleFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        val samplesPerFrame = sampleRate*20/1000
        val bitsPerSample = 16
        val bufSize = samplesPerFrame * bitsPerSample / 8
        val echoDelay = 5000 // ms
        recorderCallback = Runnable {
            Process.setThreadPriority(-19)
            while(true){
                if(recorder == null || recorder!!.recordingState == AudioRecord.RECORDSTATE_STOPPED){
                    recorderHandler.postDelayed(recorderCallback, 100)
                    break
                }
                val buf = ByteArray(bufSize)
                val readSize = recorder!!.read(buf,0,buf.size)
                if(readSize <= 0 || readSize != buf.size){
                    continue
                }
                val current = System.currentTimeMillis()
                val millis = current+echoDelay
                echoBuffer.add(Pair(millis, buf))
            }
        }
        recorderHandler.post(recorderCallback)

        trackerCallback = Runnable {
            while(true){
                if(echoBuffer.size <= 0 || echoBuffer.peek().first > System.currentTimeMillis() ){
                    trackerHandler.postDelayed(trackerCallback,100)
                    break
                }
                val data = echoBuffer.poll().second
                tracker.write(data,0,data.size)
            }
        }
        trackerHandler.post(trackerCallback)

        mediaSession = MediaSession(activity.applicationContext, activity.applicationContext.packageName)
        mediaSession.setCallback(object: MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if(event != null && event.action == KeyEvent.ACTION_DOWN){
                    if(isRecording()){
                        stop()
                    }else{
                        start()
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        mediaSession.isActive = true
    }

    fun start() {
        if(recorder == null){
            if (ActivityCompat.checkSelfPermission(
                    activity.applicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(sampleFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
        setupHeadset()
        recorder!!.startRecording()
        tracker.play()
    }

    fun stop() {
        recorder?.stop()
//        recorder?.release()
        tracker.stop()
//        tracker.release()
        echoBuffer.clear()
    }

    fun isRecording(): Boolean{
        return recorder != null && recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    private fun setupHeadset(){
        val am = activity.applicationContext.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isMicrophoneMute = false
        for (device in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                val result = am.setCommunicationDevice(device)
                if(result) break
                Log.d("Echoback", "setCommunicationDevice failed:" + arrayOf(device.productName,device.id, device.type, device.address,  if(device.isSink) "Sink" else if(device.isSource) "Source" else "Unknown" ).joinToString(" "))
            }
        }
        for(device in am.getDevices(AudioManager.GET_DEVICES_INPUTS)){
            if(device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO){
                recorder?.preferredDevice = device
                Log.d("Echoback", "setPreferredDevice:" +  arrayOf(device.productName,device.id, device.type, device.address,  if(device.isSink) "Sink" else if(device.isSource) "Source" else "Unknown" ).joinToString(" "))
            }
        }
    }
}
