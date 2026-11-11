package com.colvyra.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val LIMIT=16*1024*1024
private data class Backup(val records:List<CollectionRecord>,val entries:List<CollectionEntry>,val theme:String?)
private suspend fun exportLibrary(context:Context,dao:CollectionDao,uri:Uri,theme:String){
    val (records,entries)=dao.snapshot()
    withContext(Dispatchers.IO){
        val a=JSONArray()
        records.forEach{c->a.put(JSONObject().put("id",c.id).put("name",c.name).put("description",c.description)
            .put("tags",c.tags).put("favorite",c.favorite).put("coverUri",c.coverUri ?: JSONObject.NULL))}
        val b=JSONArray()
        entries.forEach{e->b.put(JSONObject().put("collectionId",e.collectionId).put("uri",e.uri).put("name",e.name)
            .put("mime",e.mime).put("tags",e.tags).put("favorite",e.favorite))}
        val bytes=JSONObject().put("format","colvyra-library").put("version",1).put("theme",theme)
            .put("collections",a).put("entries",b).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=LIMIT){"Слишком большой каталог"}
        val stream=context.contentResolver.openOutputStream(uri,"wt")?:error("Нет доступа к файлу")
        stream.use{it.write(bytes)}
    }
}
private suspend fun readBackup(context:Context,uri:Uri):Backup=withContext(Dispatchers.IO){
    val input=context.contentResolver.openInputStream(uri)?:error("Нет доступа к файлу")
    val bytes=input.use{
        val out=java.io.ByteArrayOutputStream()
        val buf=ByteArray(8192)
        while(true){val n=it.read(buf);if(n<0)break;require(out.size()+n<=LIMIT){"Файл слишком большой"};out.write(buf,0,n)}
        out.toByteArray()
    }
    val j=JSONObject(String(bytes,Charsets.UTF_8))
    require(j.getString("format")=="colvyra-library"&&j.getInt("version")==1){"Неизвестный формат"}
    val a=j.getJSONArray("collections");val b=j.getJSONArray("entries")
    require(a.length()<=10000&&b.length()<=100000){"Слишком много записей"}
    val records=(0 until a.length()).map{i->
        val c=a.getJSONObject(i)
        CollectionRecord(c.getString("id"),c.getString("name"),c.optString("description"),c.optString("tags"),c.optBoolean("favorite"),if(c.isNull("coverUri"))null else c.optString("coverUri"))
    }
    BackupIntegrity.validateCollections(records.map { it.id }, records.map { it.name })
    val ids=records.map{it.id}.toSet()
    val entries=(0 until b.length()).map{i->
        val e=b.getJSONObject(i)
        val ref=e.getString("uri")
        require(e.getString("collectionId") in ids && (safeWebUrl(ref)!=null || isCloudReference(ref) || Uri.parse(ref).scheme=="content")){"Некорректная ссылка"}
        CollectionEntry(e.getString("collectionId"),ref,e.getString("name"),e.getString("mime"),e.optString("tags"),e.optBoolean("favorite"))
    }
    BackupIntegrity.validateReferences(ids, entries.map { it.collectionId to it.uri })
    Backup(records,entries,j.optString("theme").takeIf{it in listOf("light","dark","system")})
}
@Composable internal fun LibrarySettings(dao:CollectionDao,theme:String,setTheme:(String)->Unit,dismiss:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var backup by remember{mutableStateOf<Backup?>(null)}
    fun work(action:suspend ()->Unit){
        if(busy)return
        busy=true;status=""
        scope.launch{
            try{action()}catch(e:CancellationException){throw e}catch(e:Exception){status=e.localizedMessage?:"Ошибка"}finally{busy=false}
        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)work{exportLibrary(context,dao,uri,theme);status="Каталог экспортирован"}
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)work{backup=readBackup(context,uri)}
    }
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text("Настройки",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=dismiss,enabled=!busy){Text("Закрыть")}
                }
                Text("Оформление",style=MaterialTheme.typography.titleMedium)
                listOf("system" to "Системная","light" to "Светлая","dark" to "Тёмная").forEach{(value,label)->
                    Row{RadioButton(selected=theme==value,onClick={setTheme(value)});Text(label)}
                }
                HorizontalDivider()
                Text("Резервная копия каталога",style=MaterialTheme.typography.titleMedium)
                Text("Копируются описания, теги и ссылки, но не сами файлы, пароли или разрешения Android. JSON не шифруется.")
                Button(onClick={export.launch("COLVYRA-library.json")},enabled=!busy){Text("Экспортировать каталог")}
                OutlinedButton(onClick={import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy){Text("Импортировать каталог")}
                Text("При восстановлении коллекции объединяются по ID. Ссылки могут потребовать повторного доступа.")
                HorizontalDivider()
                AppDiagnosticsPanel(dao)
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(status.isNotBlank())Text(status)
            }
        }
    }
    backup?.let{b->AlertDialog(onDismissRequest={backup=null},title={Text("Восстановить?")},
        text={Text("Коллекций: ${b.records.size}; ссылок: ${b.entries.size}. Существующие записи сохранятся.")},
        confirmButton={TextButton(onClick={backup=null;work{dao.restore(b.records,b.entries);b.theme?.let(setTheme);status="Каталог объединён"}}){Text("Восстановить")}},
        dismissButton={TextButton(onClick={backup=null}){Text("Отмена")}})}
}
