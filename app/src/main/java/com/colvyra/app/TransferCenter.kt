package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.work.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

private const val DOWNLOAD_TAG="colvyra-download"
private const val NAME_TAG="name:"
private const val SOURCE_TAG="source:"
internal fun enqueueDownload(context:Context,ref:LibraryReference,dest:Uri,source:String,agent:String=""){
    require(dest.scheme=="content"){"Требуется системный выбор файла"}
    try { context.contentResolver.takePersistableUriPermission(dest,Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    catch(e:SecurityException){ error("Выбранное хранилище не разрешает фоновую запись. Выберите другую папку.") }
    val data=workDataOf("uri" to ref.uri,"name" to ref.name,"mime" to ref.mime,"dest" to dest.toString(),"agent" to agent)
    val request=OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(data).addTag(DOWNLOAD_TAG).addTag(NAME_TAG+ref.name.take(250)).addTag(SOURCE_TAG+source)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueue(request)
}
class DownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        val source=inputData.getString("uri") ?: return@withContext Result.failure()
        val name=inputData.getString("name") ?: "Файл"
        val mime=inputData.getString("mime") ?: "application/octet-stream"
        val dest=inputData.getString("dest")?.let(Uri::parse) ?: return@withContext Result.failure()
        val tmp=File(applicationContext.cacheDir,"transfer-"+id)
        var writing=false
        try {
            val call=if(isCloudReference(source)){
                cloudDownloadCall(applicationContext,LibraryReference(source,name,mime))
            }else{
                val url=safeWebUrl(source) ?: error("Требуется HTTPS")
                val req=Request.Builder().url(url).header("User-Agent",inputData.getString("agent").orEmpty().ifBlank{"COLVYRA"})
                CookieManager.getInstance().getCookie(url)?.let{req.header("Cookie",it)}
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build().newCall(req.build())
            }
            call.execute().use{ response ->
                if(!response.isSuccessful){
                    if(response.code in 500..599 && runAttemptCount<2)return@withContext Result.retry()
                    return@withContext Result.failure(workDataOf("error" to "HTTP "+response.code))
                }
                val body=response.body ?: error("Пустой ответ")
                val total=body.contentLength()
                var done=0L
                var last=0L
                tmp.outputStream().use{out->
                    body.byteStream().use{input->
                        val buffer=ByteArray(64*1024)
                        while(true){
                            coroutineContext.ensureActive()
                            val n=input.read(buffer);if(n<0)break
                            out.write(buffer,0,n);done+=n
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-last>=500){setProgress(workDataOf("done" to done,"total" to total));last=now}
                        }
                    }
                }
                if(total>=0 && done!=total)throw IOException("Неполный ответ")
                setProgress(workDataOf("done" to done,"total" to done))
            }
            coroutineContext.ensureActive()
            writing=true
            val output=applicationContext.contentResolver.openOutputStream(dest,"wt") ?: error("Нет доступа к файлу назначения")
            output.use{out->tmp.inputStream().use{input->
                val buffer=ByteArray(64*1024)
                while(true){coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}
            }}
            Result.success(workDataOf("bytes" to tmp.length()))
        }catch(e:CancellationException){throw e}
        catch(e:IOException){
            if(!writing && runAttemptCount<2)Result.retry()
            else Result.failure(workDataOf("error" to if(writing)"Ошибка записи. Проверьте файл назначения." else "Сетевая ошибка или недостаточно места"))
        }catch(e:Exception){Result.failure(workDataOf("error" to "Загрузка не выполнена. Проверьте источник и доступ к файлу."))}
        finally{tmp.delete()}
    }
}

@Composable internal fun TransferCenter(dismiss:()->Unit){
    val context=LocalContext.current
    val manager=remember{WorkManager.getInstance(context)}
    val flow=remember{manager.getWorkInfosByTagFlow(DOWNLOAD_TAG)}
    val jobs by flow.collectAsState(initial=emptyList())
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().padding(16.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text("Загрузки",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=dismiss){Text("Закрыть")}
                }
                Text("Задания продолжаются при переключении вкладок. Android может отложить выполнение.",style=MaterialTheme.typography.bodySmall)
                if(jobs.isEmpty())Text("Нет загрузок",Modifier.padding(top=20.dp))
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp)){
                    items(jobs.sortedBy{if(it.state.isFinished)1 else 0},key={it.id.toString()}){job->
                        val name=job.tags.firstOrNull{it.startsWith(NAME_TAG)}?.removePrefix(NAME_TAG) ?: "Файл"
                        val source=job.tags.firstOrNull{it.startsWith(SOURCE_TAG)}?.removePrefix(SOURCE_TAG).orEmpty()
                        val status=when(job.state){
                            WorkInfo.State.ENQUEUED->"В очереди"
                            WorkInfo.State.RUNNING->"Выполняется"
                            WorkInfo.State.SUCCEEDED->"Скачано"
                            WorkInfo.State.FAILED->"Ошибка"
                            WorkInfo.State.CANCELLED->"Отменено"
                            else->"Ожидание"
                        }
                        val done=job.progress.getLong("done",0)
                        val total=job.progress.getLong("total",-1)
                        Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){
                            Column(Modifier.padding(12.dp)){
                                Text(name,style=MaterialTheme.typography.titleSmall)
                                Text(source+" · "+status,style=MaterialTheme.typography.bodySmall)
                                if(job.state==WorkInfo.State.RUNNING){
                                    if(total>0)LinearProgressIndicator(progress={(done.toDouble()/total).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp))
                                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=6.dp))
                                    Text("${done/1024} КБ",style=MaterialTheme.typography.labelSmall)
                                }
                                if(job.state==WorkInfo.State.FAILED)Text(job.outputData.getString("error") ?: "Не удалось скачать",color=MaterialTheme.colorScheme.error)
                                if(!job.state.isFinished)TextButton(onClick={manager.cancelWorkById(job.id)}){Text("Отменить")}
                            }
                        }
                    }
                }
            }
        }
    }
}
