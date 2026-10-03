package com.sbznext
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbznext.audio.DspRuntime
import com.sbznext.audio.PlaybackDspService
import com.sbznext.data.DspPresets
import com.sbznext.dsp.DspConfig

class MainActivity:ComponentActivity(){
    private var serviceRunning by mutableStateOf(false)
    private val mic=registerForActivityResult(ActivityResultContracts.RequestPermission()){ requestNotifications() }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
        if(r.resultCode==Activity.RESULT_OK && r.data!=null){
            val i=Intent(this,PlaybackDspService::class.java)
                .putExtra(PlaybackDspService.EXTRA_RESULT_CODE,r.resultCode)
                .putExtra(PlaybackDspService.EXTRA_DATA,r.data)
            startForegroundService(i)
            serviceRunning=true
        } else {
            serviceRunning=false
        }
    }
    override fun onStop(){
        DspRuntime.save(this)
        super.onStop()
    }

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        DspRuntime.configure(this)
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            mic.launch(Manifest.permission.RECORD_AUDIO)
        else requestNotifications()
        setContent{SbzApp()}
    }
    private fun requestNotifications(){
        if(android.os.Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Composable private fun SbzApp(){
        var c by remember{mutableStateOf(DspRuntime.config())}
        val running=serviceRunning
        fun update(n:DspConfig){c=n;DspRuntime.update(n)}

        MaterialTheme(colorScheme=darkColorScheme()){
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){
                Text("sBz Next",style=MaterialTheme.typography.headlineMedium)
                Text("Motor DSP PCM · 32 bandas Constant-Q",style=MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment=Alignment.CenterVertically){
                    Button(onClick={
                        projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    }){Text("Iniciar DSP")}
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick={
                        stopService(Intent(this@MainActivity,PlaybackDspService::class.java))
                        serviceRunning=false
                    }){Text("Detener")}
                    Spacer(Modifier.width(12.dp))
                    Text(if(running)"ACTIVO" else "DETENIDO")
                }
                Spacer(Modifier.height(20.dp));Text("Ganancia y salida",style=MaterialTheme.typography.titleLarge)
                Control("Pre-Gain",c.preGainDb,-12f,12f){update(c.copy(preGainDb=it))}
                Control("Bass Boost",c.bassBoostDb,-12f,12f){update(c.copy(bassBoostDb=it))}
                Control("Master",c.masterGainDb,-12f,12f){update(c.copy(masterGainDb=it))}
                Control("Balance",c.balance,-1f,1f){update(c.copy(balance=it))}
                Row(verticalAlignment=Alignment.CenterVertically){
                    Switch(c.spatialEnabled,{update(c.copy(spatialEnabled=it))})
                    Text("Spatial / Stereo Width")
                }
                if(c.spatialEnabled){
                    Control("Width",c.spatialWidth,0f,1f){update(c.copy(spatialWidth=it))}
                }
                Spacer(Modifier.height(12.dp));Text("Tone",style=MaterialTheme.typography.titleLarge)
                Control("Bass",c.toneBassDb,-12f,12f){update(c.copy(toneBassDb=it))}
                Control("Mid",c.toneMidDb,-12f,12f){update(c.copy(toneMidDb=it))}
                Control("Treble",c.toneTrebleDb,-12f,12f){update(c.copy(toneTrebleDb=it))}
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment=Alignment.CenterVertically){
                    Switch(c.eqEnabled,{update(c.copy(eqEnabled=it))});Text("EQ32 Constant-Q")
                }
                Column(Modifier.horizontalScroll(rememberScrollState()).height(270.dp)){
                    Row{
                        DspConfig.FREQUENCIES.indices.forEach{idx->
                            Column(Modifier.width(42.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                Text(label(DspConfig.FREQUENCIES[idx]),style=MaterialTheme.typography.labelSmall)
                                Slider(value=c.eqGainsDb[idx],onValueChange={v->
                                    val a=c.eqGainsDb.copyOf();a[idx]=v;update(c.copy(eqGainsDb=a))
                                },valueRange=-12f..12f,modifier=Modifier.height(220.dp),steps=23)
                                Text("${c.eqGainsDb[idx].toInt()}",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment=Alignment.CenterVertically){
                    Switch(c.mdrcEnabled,{update(c.copy(mdrcEnabled=it))});Text("MDRC 4 bandas")
                }
                Text("Cortes MDRC",style=MaterialTheme.typography.titleMedium)
                c.mdrcCutoffsHz.forEachIndexed{idx,v->
                    val lo=if(idx==0)20f else c.mdrcCutoffsHz[idx-1]+20f
                    val hi=if(idx==3)22000f else c.mdrcCutoffsHz[idx+1]-20f
                    Control("Crossover ${idx+1}",v,lo.coerceAtMost(hi),hi.coerceAtLeast(lo)){nv->
                        val a=c.mdrcCutoffsHz.copyOf();a[idx]=nv.coerceIn(lo,hi);update(c.copy(mdrcCutoffsHz=a))
                    }
                }
                c.mdrcBands.forEachIndexed{idx,b->
                    Text("Banda ${idx+1}",style=MaterialTheme.typography.titleMedium)
                    Control("Threshold",b.thresholdDb,-60f,0f){v->
                        val a=c.mdrcBands.copyOf();a[idx]=b.copy(thresholdDb=v);update(c.copy(mdrcBands=a))
                    }
                    Control("Ratio",b.ratio,1f,20f){v->
                        val a=c.mdrcBands.copyOf();a[idx]=b.copy(ratio=v);update(c.copy(mdrcBands=a))
                    }
                    Control("Attack",b.attackMs,0.5f,100f){v->
                        val a=c.mdrcBands.copyOf();a[idx]=b.copy(attackMs=v);update(c.copy(mdrcBands=a))
                    }
                    Control("Release",b.releaseMs,1f,500f){v->
                        val a=c.mdrcBands.copyOf();a[idx]=b.copy(releaseMs=v);update(c.copy(mdrcBands=a))
                    }
                    Control("Makeup",b.makeupDb,-12f,12f){v->
                        val a=c.mdrcBands.copyOf();a[idx]=b.copy(makeupDb=v);update(c.copy(mdrcBands=a))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment=Alignment.CenterVertically){
                    Switch(c.autoGainEnabled,{update(c.copy(autoGainEnabled=it))});Text("AutoGain / Headroom")
                }
                if(c.autoGainEnabled){
                    Control("Headroom",c.autoGainHeadroomDb,0f,6f){update(c.copy(autoGainHeadroomDb=it))}
                }
                Row(verticalAlignment=Alignment.CenterVertically){
                    Switch(c.limiterEnabled,{update(c.copy(limiterEnabled=it))});Text("Limiter anti-clipping")
                }
                if(c.limiterEnabled){
                    Control("Ceiling",c.limiterCeilingDb,-6f,-0.1f){update(c.copy(limiterCeilingDb=it))}
                }
                Spacer(Modifier.height(16.dp));Text("Presets",style=MaterialTheme.typography.titleLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())){
                    Button(onClick={val n=DspPresets.flat();update(n)}){Text("Flat")}
                    Spacer(Modifier.width(8.dp))
                    Button(onClick={val n=DspPresets.clarity();update(n)}){Text("Clarity")}
                    Spacer(Modifier.width(8.dp))
                    Button(onClick={val n=DspPresets.smooth();update(n)}){Text("Smooth")}
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick={DspRuntime.save(this@MainActivity)}){Text("Guardar")}
                }
                Spacer(Modifier.height(24.dp))
                Text("Importante: AudioPlaybackCapture procesa una copia autorizada del audio de otras apps; Android no expone una API pública para sustituir silenciosamente su salida original.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    @Composable private fun Control(name:String,value:Float,min:Float,max:Float,onChange:(Float)->Unit){
        Column{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name);Text(String.format("%.1f",value))}
            Slider(value=value,onValueChange=onChange,valueRange=min..max)
        }
    }
    private fun label(f:Float)=when{
        f>=1000f&&f%1000f==0f->"${(f/1000).toInt()}k"
        f>=1000f->String.format("%.1fk",f/1000)
        else->f.toInt().toString()
    }
}
