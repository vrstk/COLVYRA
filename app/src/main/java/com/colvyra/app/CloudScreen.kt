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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import java.util.UUID
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
import org.json.JSONArray
import coil.disk.DiskCache
import org.xmlpull.v1.XmlPullParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private data class Account(val endpoint:String,val user:String,val password:String) {
    val id: String get() = UUID.nameUUIDFromBytes((endpoint.trimEnd('/').plus("/").toHttpUrl().toString()+"\n"+user).toByteArray(Charsets.UTF_8)).toString()
}
private data class Remote(val url:HttpUrl,val folder:Boolean,val name:String,val mime:String = if(folder) "vnd.android.document/directory" else guessMime(name)){
    val media get()=mime.startsWith("image/") || mime.startsWith("video/")
}
private fun guessMime(name:String):String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
private const val CLOUD_SCHEME="colvyra-dav"
internal fun isCloudReference(uri:String)=Uri.parse(uri).scheme==CLOUD_SCHEME
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
    private fun decode(data:String,iv:String):String {
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))}
        return String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8)
    }
    private fun account(j:JSONObject)=Account(j.getString("endpoint"),j.getString("user"),j.getString("password"))
    fun loadAll():List<Account> {
        val data=prefs.getString("accounts_data",null)
        val iv=prefs.getString("accounts_iv",null)
        if(data!=null && iv!=null) {
            val arr=JSONArray(decode(data,iv))
            return (0 until arr.length()).map{account(arr.getJSONObject(it))}
        }
        val old=prefs.getString("data",null)?:return emptyList()
        val oldIv=prefs.getString("iv",null)?:return emptyList()
        return listOf(account(JSONObject(decode(old,oldIv))))
    }
    fun saveAll(accounts:List<Account>) {
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val arr=JSONArray()
        accounts.forEach{a->arr.put(JSONObject().put("endpoint",a.endpoint).put("user",a.user).put("password",a.password))}
        val bytes=c.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("accounts_iv",Base64.encodeToString(c.iv,Base64.NO_WRAP))
            .putString("accounts_data",Base64.encodeToString(bytes,Base64.NO_WRAP)).remove("data").remove("iv").commit()){"Не удалось сохранить аккаунты"}
    }
}
private class Dav(private val account:Account) {
    val root=account.endpoint.trimEnd('/').plus("/").toHttpUrl().also{require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null){"Требуется HTTPS URL без логина, пароля и параметров"}}
    val id = account.id
    val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
        val req=chain.request()
        if(!allowed(req.url)) throw java.io.IOException("Адрес вне подключённого каталога")
        chain.proceed(req.newBuilder().header("Authorization",Credentials.basic(account.user,account.password)).build())
    }.build()
    fun reference(item:Remote)=LibraryReference(Uri.Builder().scheme(CLOUD_SCHEME).authority(id).appendQueryParameter("url",item.url.toString()).build().toString(),item.name,item.mime)
    fun resolve(ref:LibraryReference):Remote {
        val uri=Uri.parse(ref.uri)
        require(uri.scheme==CLOUD_SCHEME && uri.authority==id){"Подключите исходный облачный аккаунт"}
        val url=uri.getQueryParameter("url")?.toHttpUrl()?:error("Некорректная ссылка")
        require(allowed(url)){"Адрес вне подключённого каталога"}
        return Remote(url,ref.mime=="vnd.android.document/directory",ref.name,ref.mime)
    }
    fun imageLoader(context:Context,cacheEnabled:Boolean)=ImageLoader.Builder(context.applicationContext).okHttpClient(client)
        .diskCache { if(cacheEnabled) DiskCache.Builder().directory(java.io.File(context.cacheDir,"dav_images_"+id)).maxSizeBytes(80L*1024*1024).build() else null }
        .components {
        if(Build.VERSION.SDK_INT>=28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
    fun rename(item:Remote,name:String) {
        require(name.isNotBlank() && name !in listOf(".","..") && '/' !in name && '\\' !in name)
        val b=item.url.newBuilder()
        if(item.url.encodedPath.endsWith("/")) b.removePathSegment(item.url.pathSegments.lastIndex)
        b.setPathSegment(b.build().pathSegments.lastIndex,name)
        if(item.folder)b.addPathSegment("")
        val dest=b.build()
        require(allowed(dest))
        client.newCall(request(item.url).header("Destination",dest.toString()).header("Overwrite","F").method("MOVE",null).build()).execute().use{check(it)}
    }
    fun delete(item:Remote) {
        client.newCall(request(item.url).delete().build()).execute().use{check(it)}
    }
    fun allowed(u:HttpUrl)=u.scheme==root.scheme && u.host==root.host && u.port==root.port && u.encodedPath.startsWith(root.encodedPath)
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
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun CloudViewer(item:Remote,dav:Dav,loader:ImageLoader,dismiss:()->Unit){
    val context=LocalContext.current
    var failed by remember(item.url){mutableStateOf<String?>(null)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().padding(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(item.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"Закрыть")}
                }
                if(item.mime.startsWith("video/")){
                    val player=remember(item.url,dav){
                        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(dav.client))).build().apply{
                            setMediaItem(MediaItem.fromUri(item.url.toString()))
                            playWhenReady=true
                            prepare()
                        }
                    }
                    DisposableEffect(player){
                        val listener=object:Player.Listener{override fun onPlayerError(error:PlaybackException){failed="Не удалось воспроизвести видео: "+error.errorCodeName}}
                        player.addListener(listener)
                        onDispose{player.removeListener(listener);player.release()}
                    }
                    AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true}},modifier=Modifier.weight(1f).fillMaxWidth(),update={it.player=player})
                }else{
                    AsyncImage(model=ImageRequest.Builder(context).data(item.url.toString()).diskCachePolicy(CachePolicy.DISABLED).memoryCacheKey(dav.id+":"+item.url).build(),imageLoader=loader,contentDescription=item.name,modifier=Modifier.weight(1f).fillMaxWidth(),contentScale=ContentScale.Fit,onError={failed="Не удалось загрузить изображение"})
                }
                failed?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            }
        }
    }
}
@Composable private fun CloudPreview(item:Remote,dav:Dav,loader:ImageLoader,modifier:Modifier,cacheEnabled:Boolean){
    val context=LocalContext.current
    Box(modifier,contentAlignment=Alignment.Center){
        Icon(when{item.folder->Icons.Outlined.Folder;item.mime.startsWith("video/")->Icons.Outlined.Movie;item.media->Icons.Outlined.Image;else->Icons.Outlined.InsertDriveFile},null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(36.dp))
        if(item.mime.startsWith("image/")) AsyncImage(model=ImageRequest.Builder(context).data(item.url.toString()).diskCachePolicy(if(cacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED).memoryCacheKey(dav.id+":"+item.url).build(),imageLoader=loader,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
    }
}
@Composable internal fun ColumnScope.CloudScreen(mediaOnly:Boolean=false,manage:Boolean=false,initialReference:LibraryReference?=null,addToCollection:(LibraryReference)->Unit={},onReferenceConsumed:()->Unit={}){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("colvyra",Context.MODE_PRIVATE)}
    val scope=rememberCoroutineScope()
    val vault=remember{Vault(context)}
    val loaded = remember { runCatching { vault.loadAll() } }
    var accounts by remember { mutableStateOf(loaded.getOrDefault(emptyList())) }
    var account by remember {
        mutableStateOf(accounts.find { it.id == prefs.getString("active_cloud",null) } ?: accounts.firstOrNull())
    }
    var adding by remember { mutableStateOf(account==null) }
    var accountMenu by remember { mutableStateOf(false) }
    var cacheEnabled by remember { mutableStateOf(prefs.getBoolean("cloud_disk_cache",false)) }
    var endpoint by remember{mutableStateOf(account?.endpoint?:"https://webdav.cloud.mail.ru/")}
    var user by remember{mutableStateOf(account?.user?:"")}
    var password by remember{mutableStateOf("")}
    var dav by remember{mutableStateOf(account?.let{runCatching{Dav(it)}.getOrNull()})}
    var stack by remember{mutableStateOf<List<HttpUrl>>(emptyList())}
    var rows by remember{mutableStateOf<List<Remote>>(emptyList())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(loaded.exceptionOrNull()?.let{"Не удалось прочитать сохранённые аккаунты"})}
    var selected by remember{mutableStateOf<Remote?>(null)}
    var viewer by remember{mutableStateOf<Remote?>(null)}
    var mkdir by remember{mutableStateOf(false)}
    var folderName by remember{mutableStateOf("")}
    var rename by remember{mutableStateOf<Remote?>(null)}
    var remove by remember{mutableStateOf<Remote?>(null)}
    var pendingDownload by remember{mutableStateOf<Remote?>(null)}
    var query by remember{mutableStateOf("")}
    var grid by remember(mediaOnly){mutableStateOf(prefs.getBoolean("cloud_grid_$mediaOnly",mediaOnly))}
    val loader=remember(dav,cacheEnabled){dav?.imageLoader(context,cacheEnabled)}
    DisposableEffect(loader){onDispose{loader?.shutdown()}}
    fun work(block:suspend ()->Unit){
        if(busy)return
        busy=true;error=null
        scope.launch{
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.localizedMessage?:"Ошибка подключения"}finally{busy=false}
        }
    }
    fun rememberedPath(client:Dav):List<HttpUrl>{
        val saved=prefs.getString("cloud_path_"+client.id,null) ?: return listOf(client.root)
        return runCatching {
            val arr=JSONArray(saved)
            (0 until arr.length()).map{arr.getString(it).toHttpUrl()}.takeIf{it.isNotEmpty() && it.first()==client.root && it.all{url->client.allowed(url)}} ?: listOf(client.root)
        }.getOrDefault(listOf(client.root))
    }
    fun savePath(client:Dav,path:List<HttpUrl>){
        prefs.edit().putString("cloud_path_"+client.id,JSONArray(path.map{it.toString()}).toString()).apply()
    }
    fun load(client:Dav,path:List<HttpUrl>){
        work{val list=withContext(Dispatchers.IO){client.list(path.last())};stack=path;rows=list;query="";savePath(client,path)}
    }
    fun activate(a:Account?){
        account=a; dav=a?.let{Dav(it)}; stack=emptyList();rows=emptyList();query="";selected=null;viewer=null;rename=null;remove=null
        prefs.edit().putString("active_cloud",a?.id).apply()
        adding=a==null;error=null
    }
    LaunchedEffect(dav,initialReference,adding){
        if(adding)return@LaunchedEffect
        val ref=initialReference
        if(ref!=null){
            val target=accounts.find{it.id==Uri.parse(ref.uri).authority}
            if(target==null){error="Подключите исходный облачный аккаунт";onReferenceConsumed();return@LaunchedEffect}
            if(dav?.id!=target.id){activate(target);return@LaunchedEffect}
        }
        val client=dav ?: return@LaunchedEffect
        if(ref!=null){
            try{
                val item=client.resolve(ref)
                if(item.folder){
                    val path=mutableListOf(client.root)
                    val parts=item.url.pathSegments.filter{it.isNotEmpty()}.drop(client.root.pathSegments.count{it.isNotEmpty()})
                    for(part in parts)path.add(path.last().newBuilder().addPathSegment(part).addPathSegment("").build())
                    load(client,path)
                }else{if(item.media)viewer=item else selected=item;if(stack.isEmpty())load(client,rememberedPath(client))}
            }catch(e:Exception){error=e.localizedMessage}
            onReferenceConsumed()
        }else if(stack.isEmpty()&&!busy)load(client,rememberedPath(client))
    }
    BackHandler(enabled=stack.size>1&&!busy){dav?.let{load(it,stack.dropLast(1))}}
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
            rows=withContext(Dispatchers.IO){client.upload(context,parent,uri);client.list(parent)}
        }
    }
    fun open(item:Remote){if(item.folder)dav?.let{load(it,stack+item.url)} else if(item.media)viewer=item else selected=item}
    if(manage)HorizontalDivider()
    Text(if(mediaOnly)"Облачная галерея" else "Mail.ru Cloud / WebDAV",style=MaterialTheme.typography.titleMedium)
    if(adding){
        if(accounts.isNotEmpty()) TextButton(onClick={adding=false;password=""},enabled=!busy){Text("Отмена")}
        OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS адрес WebDAV")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(user,{user=it},label={Text("Логин")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("Пароль приложения")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy && user.isNotBlank() && password.isNotBlank(),onClick={
            val a=Account(endpoint.trim(),user.trim(),password)
            work{
                val client=Dav(a)
                val updated=accounts.filterNot{it.id==a.id}+a
                val list=withContext(Dispatchers.IO){val result=client.list(client.root);vault.saveAll(updated);result}
                accounts=updated;activate(a);stack=listOf(client.root);rows=list;savePath(client,stack);password=""
            }
        }){Text("Подключить")}
    }else{
        Row(verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){
                TextButton(onClick={accountMenu=true},enabled=!busy){Text(account?.user.orEmpty(),maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.Outlined.ArrowDropDown,"Выбрать аккаунт")}
                DropdownMenu(expanded=accountMenu,onDismissRequest={accountMenu=false}){
                    accounts.forEach{a->DropdownMenuItem(text={Text(a.user+" · "+a.endpoint)},onClick={accountMenu=false;if(a.id!=account?.id)activate(a)})}
                    DropdownMenuItem(text={Text("Добавить аккаунт")},onClick={accountMenu=false;endpoint="https://webdav.cloud.mail.ru/";user="";password="";adding=true})
                }
            }
            if(manage) TextButton(enabled=!busy,onClick={dav?.let{load(it,listOf(it.root))}}){Text("Корень")}
        }
        if(manage){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Кэш изображений на диске",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                Switch(checked=cacheEnabled,onCheckedChange={cacheEnabled=it;prefs.edit().putBoolean("cloud_disk_cache",it).apply()})
            }
            Row{
                TextButton(enabled=!busy,onClick={
                    val a=account
                    if(a!=null) work{
                        val updated=accounts.filterNot{it.id==a.id}
                        withContext(Dispatchers.IO){vault.saveAll(updated)}
                        accounts=updated;activate(updated.firstOrNull())
                    }
                }){Text("Отключить аккаунт")}
                TextButton(enabled=!busy,onClick={
                    val current=loader
                    val id=dav?.id
                    if(id!=null) work{withContext(Dispatchers.IO){current?.memoryCache?.clear();if(cacheEnabled)current?.diskCache?.clear() else java.io.File(context.cacheDir,"dav_images_"+id).deleteRecursively()}}
                }){Text("Очистить кэш")}
            }
        }
        if(stack.isNotEmpty()){
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton(enabled=!busy && stack.size>1,onClick={dav?.let{load(it,stack.dropLast(1))}}){Icon(Icons.Outlined.ArrowBack,"Назад")}
                IconButton(enabled=!busy,onClick={dav?.let{load(it,stack)}}){Icon(Icons.Outlined.Refresh,"Обновить")}
                IconButton(onClick={grid=!grid;prefs.edit().putBoolean("cloud_grid_$mediaOnly",grid).apply()}){Icon(if(grid)Icons.Outlined.ViewList else Icons.Outlined.GridView,"Список или плитка")}
                IconButton(enabled=!busy,onClick={folderName="";mkdir=true}){Icon(Icons.Outlined.CreateNewFolder,"Создать папку")}
                IconButton(enabled=!busy,onClick={upload.launch(arrayOf("*/*"))}){Icon(Icons.Outlined.Add,"Загрузить файл")}
            }
            Text("/"+stack.last().pathSegments.filter{it.isNotEmpty()}.joinToString("/"),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Поиск в папке")})
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            val visible=remember(rows,query,mediaOnly){rows.filter{(!mediaOnly||it.folder||it.media)&&it.name.contains(query,true)}}
            Text("Элементов: ${visible.size}",style=MaterialTheme.typography.labelMedium)
            val client=dav;val images=loader
            if(client!=null&&images!=null){
                if(grid)LazyVerticalGrid(columns=GridCells.Adaptive(132.dp),modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=8.dp)){
                    gridItems(visible,key={it.url.toString()}){item->
                        Card{Column(Modifier.clickable(enabled=!busy){open(item)}.padding(8.dp)){
                            CloudPreview(item,client,images,Modifier.fillMaxWidth().aspectRatio(1f),cacheEnabled)
                            Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text(if(item.folder)"Папка" else if(item.media)"Медиа" else "Файл",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                                IconButton(onClick={selected=item},enabled=!busy){Icon(Icons.Outlined.MoreVert,"Действия")}
                            }
                        }}
                    }
                }else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp)){
                    items(visible,key={it.url.toString()}){item->
                        ListItem(headlineContent={Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis)},leadingContent={CloudPreview(item,client,images,Modifier.size(52.dp),cacheEnabled)},trailingContent={IconButton(onClick={selected=item},enabled=!busy){Icon(Icons.Outlined.MoreVert,"Действия")}},modifier=Modifier.clickable(enabled=!busy){open(item)})
                        HorizontalDivider()
                    }
                }
            }
        }else{
            Button(onClick={dav?.let{load(it,listOf(it.root))}},enabled=!busy){Text("Открыть корневую папку")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(adding){if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
    selected?.let{item->AlertDialog(onDismissRequest={selected=null},title={Text(item.name)},text={
        Column{
            if(item.folder||item.media)TextButton(onClick={selected=null;open(item)}){Text("Открыть")}
            if(!item.folder)TextButton(onClick={selected=null;pendingDownload=item;download.launch(item.name)}){Text("Скачать")}
            TextButton(onClick={selected=null;dav?.let{addToCollection(it.reference(item))}}){Text("Добавить в коллекцию")}
            TextButton(onClick={selected=null;folderName=item.name;rename=item}){Text("Переименовать")}
            TextButton(onClick={selected=null;remove=item}){Text("Удалить оригинал",color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={selected=null}){Text("Закрыть")}})}
    if(mkdir||rename!=null)AlertDialog(onDismissRequest={mkdir=false;rename=null},title={Text(if(mkdir)"Новая папка" else "Переименовать")},text={OutlinedTextField(folderName,{folderName=it},singleLine=true)},confirmButton={TextButton(onClick={
        val client=dav;val parent=stack.lastOrNull();val name=folderName.trim();val item=rename;mkdir=false;rename=null
        if(client!=null && parent!=null)work{rows=withContext(Dispatchers.IO){if(item==null)client.mkdir(parent,name)else client.rename(item,name);client.list(parent)}}
    },enabled=folderName.isNotBlank()&&'/' !in folderName&&'\\' !in folderName&&folderName.trim() !in listOf(".","..")){Text("Сохранить")}},dismissButton={TextButton(onClick={mkdir=false;rename=null}){Text("Отмена")}})
    remove?.let{item->AlertDialog(onDismissRequest={remove=null},title={Text("Удалить оригинал?")},text={Text(if(item.folder)"Папка «${item.name}» и её содержимое могут быть удалены из облака." else "Файл «${item.name}» будет удалён из облака.")},confirmButton={TextButton(onClick={
        remove=null;val client=dav;val parent=stack.lastOrNull()
        if(client!=null&&parent!=null)work{rows=withContext(Dispatchers.IO){client.delete(item);client.list(parent)}}
    }){Text("Удалить",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={remove=null}){Text("Отмена")}})}
    viewer?.let{item->val client=dav;val images=loader;if(client!=null&&images!=null)CloudViewer(item,client,images){viewer=null}}
}
