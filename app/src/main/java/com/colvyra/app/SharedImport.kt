package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
internal fun sharedFileUris(intent:Intent?):List<Uri>{
    if(intent==null)return emptyList()
    val list=when(intent.action){
        Intent.ACTION_SEND->listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE->intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else->emptyList()
    }
    return list.filter{it.scheme=="content" && it.authority!="com.colvyra.app.imports"}.distinct().take(100)
}
private data class Imported(val ref:LibraryReference,val directory:File)
private suspend fun copyShared(context:Context,uri:Uri):Imported=withContext(Dispatchers.IO){
    val cr=context.contentResolver
    val original=cr.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null} ?: "file"
    val safe=original.replace(Regex("[/\\\\\\p{Cntrl}]"),"_").take(180).trim().takeUnless{it.isBlank()||it=="."||it==".."} ?: "file"
    val dir=File(context.filesDir,"imports/"+UUID.randomUUID())
    check(dir.mkdirs()){"Не удалось создать папку"}
    val file=File(dir,safe)
    try{
        val mime=cr.getType(uri) ?: "application/octet-stream"
        (cr.openInputStream(uri)?:error("Нет доступа к файлу")).use{input->
            file.outputStream().use{out->
                val buf=ByteArray(64*1024)
                while(true){coroutineContext.ensureActive();val n=input.read(buf);if(n<0)break;out.write(buf,0,n)}
            }
        }
        val saved=FileProvider.getUriForFile(context,"com.colvyra.app.imports",file)
        Imported(LibraryReference(saved.toString(),safe,mime),dir)
    }catch(e:Exception){dir.deleteRecursively();throw e}
}

@Composable internal fun SharedImportDialog(dao:CollectionDao,uris:List<Uri>,dismiss:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val collections by dao.observeCollections().collectAsState(initial=emptyList())
    var selected by remember(uris){mutableStateOf<String?>(null)}
    var newName by remember(uris){mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var progress by remember{mutableIntStateOf(0)}
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Импорт файлов")},
        text={
            Column{
                Text("Получено файлов: ${uris.size}. COLVYRA создаст собственные копии в памяти приложения. Оригиналы не изменятся.")
                if(busy){LinearProgressIndicator(progress={progress.toFloat()/uris.size.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth());Text("$progress из ${uris.size}")}
                else{
                    LazyColumn(Modifier.heightIn(max=220.dp)){
                        items(collections,key={it.id}){c->
                            Row{
                                RadioButton(selected=selected==c.id,onClick={selected=c.id})
                                TextButton(onClick={selected=c.id}){Text(c.name)}
                            }
                        }
                    }
                    OutlinedTextField(newName,{newName=it;if(it.isNotBlank())selected=null},label={Text("Или новая коллекция")},singleLine=true)
                }
            }
        },
        confirmButton={TextButton(enabled=!busy&&(selected!=null||newName.isNotBlank()),onClick={
            busy=true
            scope.launch{
                var count=0
                try{
                    val id=selected ?: CollectionRecord(name=newName.trim()).let{dao.insert(it);it.id}
                    for(uri in uris){
                        var copied:Imported?=null
                        try{
                            copied=copyShared(context,uri)
                            dao.addReference(id,copied.ref)
                            count++
                        }catch(e:CancellationException){throw e}
                        catch(e:Exception){copied?.directory?.deleteRecursively()}
                        finally{progress++}
                    }
                    Toast.makeText(context,"Импортировано: $count из ${uris.size}",Toast.LENGTH_LONG).show()
                    dismiss()
                }catch(e:CancellationException){throw e}
                catch(e:Exception){Toast.makeText(context,"Не удалось создать коллекцию",Toast.LENGTH_LONG).show()}
                finally{busy=false}
            }
        }){Text("Импортировать")}},
        dismissButton={TextButton(onClick=dismiss,enabled=!busy){Text("Отмена")}})
}
