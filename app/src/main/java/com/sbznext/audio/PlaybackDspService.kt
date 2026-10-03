package com.sbznext.audio

import android.app.*
import android.app.Activity
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.sbznext.MainActivity
import com.sbznext.R
import com.sbznext.dsp.DspEngine

class PlaybackDspService:Service(){
    companion object{
        const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="data"; const val ACTION_STOP="com.sbznext.STOP"
        private const val CHANNEL="sbznext_audio"; private const val NOTIFICATION_ID=1001
    }
    private var projection:MediaProjection?=null; private var record:AudioRecord?=null; private var track:AudioTrack?=null; private var worker:Thread?=null
    @Volatile private var running=false
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        if(running)return START_STICKY
        val code=intent?.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)?:return START_NOT_STICKY
        val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(EXTRA_DATA,Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if(code!=Activity.RESULT_OK||data==null)return START_NOT_STICKY
        createChannel(); startForegroundCompat(); startCapture(code,data); return START_STICKY
    }
    private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"sBz Next DSP",NotificationManager.IMPORTANCE_LOW))}
    private fun notification():Notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_dsp).setContentTitle("sBz Next").setContentText("Procesamiento DSP PCM activo").setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).addAction(android.R.drawable.ic_media_pause,"Detener",PendingIntent.getService(this,2,Intent(this,PlaybackDspService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE)).build()
    private fun startForegroundCompat(){if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID,notification())}
    private fun startCapture(code:Int,data:Intent){
        val pm=getSystemService(MediaProjectionManager::class.java); val p=pm.getMediaProjection(code,data)?:run{stopSelf();return}; projection=p
        p.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},Handler(mainLooper))
        val sr=48000; val min=AudioRecord.getMinBufferSize(sr,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_FLOAT).let{if(it>0)it else 16384}; val bytes=(min*2).coerceAtLeast(16384)
        val cfg=AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        record=AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bytes).setAudioPlaybackCaptureConfig(cfg).build()
        val outFmt=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        track=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(outFmt).setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build()
        val dsp=DspEngine(sr); DspRuntime.attach(dsp); running=true
        record!!.startRecording(); track!!.play()
        worker=Thread({
            val buf=FloatArray(2048)
            try{while(running){val n=record?.read(buf,0,buf.size,AudioRecord.READ_BLOCKING)?:-1;if(n>0){dsp.process(buf);track?.write(buf,0,n,AudioTrack.WRITE_BLOCKING)}else if(n<0)break}}
            catch(_:InterruptedException){}
            catch(_:Throwable){stopSelf()}
        },"sBzNext-AudioThread").also{it.priority=Thread.MAX_PRIORITY;it.start()}
    }
    override fun onDestroy(){running=false;worker?.interrupt();runCatching{record?.stop()};runCatching{track?.stop()};runCatching{record?.release()};runCatching{track?.release()};record=null;track=null;DspRuntime.detach();runCatching{projection?.stop()};projection=null;super.onDestroy()}
}
