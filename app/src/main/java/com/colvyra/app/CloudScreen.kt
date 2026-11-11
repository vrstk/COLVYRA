package com.colvyra.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private data class Account(val endpoint:String,val user:String,val password:String)
private data class Remote(val url:HttpUrl,val folder:Boolean,val name:String)
private class Vault(private val context:Context) {
    private val prefs=context.getSharedPreferences("cloud_credentials",Context.MODE_PRIVATE)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("colvyra_cloud",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("colvyra_cloud",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(a:Account) {
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val plain=JSONObject().put("endpoint",a.endpoint).put("user",a.user).put("password",a.password).toString()
        val bytes=c.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv",Base64.encodeToString(c.iv,Base64.NO_WRAP)).putString("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).apply()
    }
    fun load():Account? {
        val data=prefs.getString("data",null)?:return null
        val iv=prefs.getString("iv",null)?:return null
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))}
        val j=JSONObject(String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8))
        return Account(j.getString("endpoint"),j.getString("user"),j.getString("password"))
    }
    fun clear(){prefs.edit().clear().apply()}
}
private class Dav(private val account:Account) {
    val root=account.endpoint.trimEnd('/').plus("/").toHttpUrl().also{require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null){"Требуется HTTPS URL без логина, пароля и параметров"}}
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private fun allowed(u:HttpUrl)=u.scheme==root.scheme && u.host==root.host && u.port==root.port && u.encodedPath.startsWith(root.encodedPath)
    private fun request(u:HttpUrl):Request.Builder {
        require(allowed(u)){"Адрес вне подключённого каталога"}
        return Request.Builder().url(u).header("Authorization",Credentials.basic(account.user,account.password))
    }
    private fun check(r:Response){if(!r.isSuccessful) error("WebDAV HTTP ${r.code}")}
    fun list(u:HttpUrl):List<Remote> {
        val xml="""<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>"""
        client.newCall(request(u).header("Depth","1").method("PROPFIND",xml.toRequestBody("application/xml".toMediaType())).build()).execute().use{r->
            check(r)
            val parser=Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
            parser.setInput(r.body!!.byteStream(),null)
            val results=mutableListOf<Remote>()
            var href:String?=null;var folder=false
            var inResponse=false
            while(parser.eventType!=XmlPullParser.END_DOCUMENT){
                if(parser.eventType==XmlPullParser.START_TAG && parser.namespace=="DAV:"){
                    when(parser.name){
                        "response"->{inResponse=true;href=null;folder=false}
                        "href"->if(inResponse)href=parser.nextText()
                        "collection"->if(inResponse)folder=true
                    }
                }
                if(parser.eventType==XmlPullParser.END_TAG && parser.namespace=="DAV:" && parser.name=="response"){
                    val target=href?.let{u.resolve(it)}
                    if(target!=null && allowed(target) && target.encodedPath.trimEnd('/')!=u.encodedPath.trimEnd('/')){
                        val normalized=if(folder && !target.encodedPath.endsWith("/"))target.newBuilder().addPathSegment("").build() else target
                        results.add(Remote(normalized,folder,target.pathSegments.lastOrNull{it.isNotEmpty()}?:"Файл"))
                    }
                    inResponse=false
                }
                parser.next()
            }
            return results.distinctBy{it.url}.sortedWith(compareBy<Remote>{!it.folder}.thenBy{it.name.lowercase()})
        }
    }
    fun mkdir(parent:HttpUrl,name:String){
        require(name.isNotBlank() && name !in listOf(".","..") && '/' !in name)
        val u=parent.newBuilder().addPathSegment(name).addPathSegment("").build()
        client.newCall(request(u).method("MKCOL",null).build()).execute().use{check(it)}
    }
    fun download(context:Context,item:Remote,dest:Uri){
        client.newCall(request(item.url).get().build()).execute().use{r->
            check(r)
            val out=context.contentResolver.openOutputStream(dest,"w")?:error("Не удалось открыть файл назначения")
            out.use{o->r.body!!.byteStream().use{it.copyTo(o)}}
        }
    }
    fun upload(context:Context,parent:HttpUrl,uri:Uri){
        val cr=context.contentResolver
        val name=cr.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}?:error("Не удалось определить имя")
        require(name.isNotBlank() && name !in listOf(".","..") && '/' !in name)
        val target=parent.newBuilder().addPathSegment(name).build()
        val body=object:RequestBody(){
            override fun contentType()=(cr.getType(uri)?:"application/octet-stream").toMediaType()
            override fun writeTo(sink:BufferedSink){(cr.openInputStream(uri)?:error("Файл недоступен")).source().use{sink.writeAll(it)}}
        }
        client.newCall(request(target).header("If-None-Match","*").put(body).build()).execute().use{check(it)}
    }
}
@Composable internal fun CloudScreen(){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val vault=remember{Vault(context)}
    var account by remember{mutableStateOf(runCatching{vault.load()}.getOrNull())}
    var endpoint by remember{mutableStateOf(account?.endpoint?:"https://webdav.cloud.mail.ru/")}
    var user by remember{mutableStateOf(account?.user?:"")}
    var password by remember{mutableStateOf("")}
    var dav by remember{mutableStateOf(account?.let{runCatching{Dav(it)}.getOrNull()})}
    var stack by remember{mutableStateOf<List<HttpUrl>>(emptyList())}
    var rows by remember{mutableStateOf<List<Remote>>(emptyList())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var selected by remember{mutableStateOf<Remote?>(null)}
    var mkdir by remember{mutableStateOf(false)}
    var folderName by remember{mutableStateOf("")}
    var pendingDownload by remember{mutableStateOf<Remote?>(null)}
    fun work(block:suspend ()->Unit){
        if(busy)return
        busy=true;error=null
        scope.launch{
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.localizedMessage?:"Ошибка подключения"}finally{busy=false}
        }
    }
    fun load(client:Dav,path:List<HttpUrl>){
        work{val list=withContext(Dispatchers.IO){client.list(path.last())};stack=path;rows=list}
    }
    val download=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val item=pendingDownload;pendingDownload=null;val client=dav
        if(uri!=null && item!=null && client!=null)work{
            withContext(Dispatchers.IO){client.download(context,item,uri)}
            Toast.makeText(context,"Файл скачан",Toast.LENGTH_SHORT).show()
        }
    }
    val upload=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        val client=dav;val parent=stack.lastOrNull()
        if(uri!=null && client!=null && parent!=null)work{
            val updated=withContext(Dispatchers.IO){client.upload(context,parent,uri);client.list(parent)}
            rows=updated
        }
    }
    HorizontalDivider()
    Text("Mail.ru Cloud / WebDAV",style=MaterialTheme.typography.titleLarge)
    if(account==null){
        OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS адрес WebDAV")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(user,{user=it},label={Text("Логин")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("Пароль приложения")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy && user.isNotBlank() && password.isNotBlank(),onClick={
            val a=Account(endpoint.trim(),user.trim(),password)
            work{
                val client=Dav(a)
                val list=withContext(Dispatchers.IO){val result=client.list(client.root);vault.save(a);result}
                account=a;dav=client;stack=listOf(client.root);rows=list;password=""
            }
        }){Text("Подключить")}
    }else{
        Text(account?.user.orEmpty())
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(enabled=!busy,onClick={dav?.let{load(it,listOf(it.root))}}){Text("Открыть")}
            TextButton(enabled=!busy,onClick={vault.clear();account=null;dav=null;stack=emptyList();rows=emptyList();password=""}){Text("Отключить")}
        }
        if(stack.isNotEmpty()){
            Row{
                IconButton(enabled=!busy && stack.size>1,onClick={dav?.let{load(it,stack.dropLast(1))}}){Icon(Icons.Outlined.ArrowBack,"Назад")}
                IconButton(enabled=!busy,onClick={dav?.let{load(it,stack)}}){Icon(Icons.Outlined.Refresh,"Обновить")}
                IconButton(enabled=!busy,onClick={mkdir=true}){Icon(Icons.Outlined.CreateNewFolder,"Создать папку")}
                IconButton(enabled=!busy,onClick={upload.launch(arrayOf("*/*"))}){Icon(Icons.Outlined.Add,"Загрузить файл")}
            }
            Text(stack.last().encodedPath,maxLines=2)
            LazyColumn(Modifier.heightIn(max=360.dp)){
                items(rows,key={it.url.toString()}){item->
                    ListItem(headlineContent={Text(item.name)},leadingContent={Icon(if(item.folder)Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,null)},modifier=Modifier.clickable(enabled=!busy){
                        if(item.folder)dav?.let{load(it,stack+item.url)} else selected=item
                    })
                }
            }
        }
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    selected?.let{item->AlertDialog(onDismissRequest={selected=null},title={Text(item.name)},text={Text("Скачать файл в выбранное место?")},confirmButton={TextButton(onClick={selected=null;pendingDownload=item;download.launch(item.name)}){Text("Скачать")}},dismissButton={TextButton(onClick={selected=null}){Text("Отмена")}})}
    if(mkdir)AlertDialog(onDismissRequest={mkdir=false},title={Text("Новая папка")},text={OutlinedTextField(folderName,{folderName=it},singleLine=true)},confirmButton={TextButton(onClick={
        val client=dav;val parent=stack.lastOrNull();val name=folderName.trim();mkdir=false
        if(client!=null && parent!=null)work{rows=withContext(Dispatchers.IO){client.mkdir(parent,name);client.list(parent)}}
    },enabled=folderName.isNotBlank()){Text("Создать")}},dismissButton={TextButton(onClick={mkdir=false}){Text("Отмена")}})
}
