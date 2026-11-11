package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

// Never pass WebDAV credentials, file URIs or arbitrary intent schemes to WebView.
internal fun safeWebUrl(value:String):String? {
    val url=value.trim().toHttpUrlOrNull() ?: return null
    return url.takeIf{it.isHttps && it.username.isEmpty() && it.password.isEmpty()}?.toString()
}
internal fun extractWebLink(text:String):String? =
    Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(text)
        .mapNotNull{safeWebUrl(it.value.trimEnd('.',',',')',']','"','\''))}.firstOrNull()

private data class Site(val url:String,val title:String,val category:String="")
private data class WebDownload(val url:String,val name:String,val mime:String,val agent:String)
private fun loadSites(context:Context,key:String):List<Site> = try {
    val arr=JSONArray(context.getSharedPreferences("web_catalog",Context.MODE_PRIVATE).getString(key,"[]"))
    (0 until arr.length()).mapNotNull{i->
        val j=arr.getJSONObject(i)
        safeWebUrl(j.getString("url"))?.let{Site(it,j.optString("title",it),j.optString("category",""))}
    }
} catch(_:Exception){emptyList()}
private fun saveSites(context:Context,key:String,sites:List<Site>){
    val arr=JSONArray()
    sites.forEach{arr.put(JSONObject().put("url",it.url).put("title",it.title).put("category",it.category))}
    context.getSharedPreferences("web_catalog",Context.MODE_PRIVATE).edit().putString(key,arr.toString()).apply()
}
private fun shareLink(context:Context,url:String){
    try{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,url)},"Поделиться ссылкой"))}
    catch(_:Exception){Toast.makeText(context,"Не удалось открыть меню обмена",Toast.LENGTH_SHORT).show()}
}
private fun downloadWeb(context:Context,job:WebDownload,dest:Uri,progress:(Long,Long)->Unit){
    val url=safeWebUrl(job.url) ?: error("Поддерживаются только HTTPS-ссылки")
    val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    val request=Request.Builder().url(url).header("User-Agent",job.agent.ifBlank{"COLVYRA"})
    // Cookies are scoped to the exact requested URL and never forwarded to redirects.
    CookieManager.getInstance().getCookie(url)?.let{request.header("Cookie",it)}
    client.newCall(request.build()).execute().use{response->
        if(response.isRedirect)error("Сайт перенаправил загрузку. Откройте конечную ссылку или используйте внешний браузер.")
        if(!response.isSuccessful)error("HTTP "+response.code)
        val body=response.body ?: error("Пустой ответ")
        val expected=body.contentLength()
        val out=context.contentResolver.openOutputStream(dest,"w") ?: error("Нет доступа к файлу назначения")
        out.use{output->
            body.byteStream().use{input->
                val buffer=ByteArray(64*1024);var done=0L
                while(true){val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n);done+=n;progress(done,expected)}
                if(expected>=0 && done!=expected)error("Загрузка не завершена")
            }
        }
    }
}

@Composable internal fun ColumnScope.WebScreen(initialUrl:String?,consumeUrl:()->Unit,addToCollection:(LibraryReference)->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var address by rememberSaveable{mutableStateOf("")}
    var current by rememberSaveable{mutableStateOf("")}
    var title by rememberSaveable{mutableStateOf("")}
    var showBrowser by rememberSaveable{mutableStateOf(false)}
    var showHistory by rememberSaveable{mutableStateOf(false)}
    var bookmarks by remember{mutableStateOf(loadSites(context,"bookmarks"))}
    var history by remember{mutableStateOf(loadSites(context,"history"))}
    var search by remember{mutableStateOf("")}
    var editor by remember{mutableStateOf<Site?>(null)}
    var editOriginal by remember{mutableStateOf<String?>(null)}
    var pending by remember{mutableStateOf<WebDownload?>(null)}
    var confirmDownload by remember{mutableStateOf<WebDownload?>(null)}
    var downloading by remember{mutableStateOf(false)}
    var progress by remember{mutableStateOf(0f)}
    var error by remember{mutableStateOf<String?>(null)}
    var webView by remember{mutableStateOf<WebView?>(null)}
    var navTarget by remember{mutableStateOf<String?>(null)}
    var canBack by remember{mutableStateOf(false)}
    var canForward by remember{mutableStateOf(false)}
    fun navigate(value:String){
        val url=safeWebUrl(value) ?: safeWebUrl("https://"+value.trim())
        if(url==null){error="Введите корректный HTTPS-адрес";return}
        address=url;current=url;title="";error=null;showBrowser=true;navTarget=url
    }
    fun record(url:String,pageTitle:String){
        val safe=safeWebUrl(url)?:return
        current=safe;address=safe;title=pageTitle.ifBlank{safe}
        history=(listOf(Site(safe,title))+history.filterNot{it.url==safe}).take(100)
        saveSites(context,"history",history)
    }
    fun bookmark(url:String,pageTitle:String){
        editor=Site(url,pageTitle.ifBlank{url});editOriginal=bookmarks.find{it.url==url}?.url
    }
    LaunchedEffect(initialUrl){if(initialUrl!=null){navigate(initialUrl);consumeUrl()}}
    LaunchedEffect(webView,navTarget){
        val view=webView;val target=navTarget
        if(view!=null && target!=null){view.loadUrl(target);navTarget=null}
    }
    BackHandler(enabled=showBrowser){
        if(webView?.canGoBack()==true)webView?.goBack() else showBrowser=false
    }
    val saveDownload=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val job=pending;pending=null
        val dest=result.data?.data
        if(result.resultCode==android.app.Activity.RESULT_OK && dest!=null && job!=null){
            downloading=true;progress=0f;error=null
            scope.launch{
                try{
                    withContext(Dispatchers.IO){downloadWeb(context,job,dest){done,total->
                        if(total>0)progress=(done.toDouble()/total).toFloat().coerceIn(0f,1f)
                    }}
                    Toast.makeText(context,"Файл скачан",Toast.LENGTH_LONG).show()
                }catch(e:CancellationException){throw e}
                catch(e:Exception){error=(e.localizedMessage?:"Ошибка скачивания")+". Проверьте файл назначения: он может быть неполным."}
                finally{downloading=false}
            }
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically){
        Text("Сайты",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
        IconButton(onClick={showBrowser=false;showHistory=false}){Icon(Icons.Outlined.Bookmarks,"Каталог сайтов")}
        IconButton(onClick={showBrowser=false;showHistory=true}){Icon(Icons.Outlined.History,"История")}
    }
    Row(verticalAlignment=Alignment.CenterVertically){
        OutlinedTextField(address,{address=it},Modifier.weight(1f),singleLine=true,label={Text("Адрес HTTPS")})
        IconButton(onClick={navigate(address)}){Icon(Icons.Outlined.ArrowForward,"Перейти")}
    }
    if(downloading){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());Text("Скачивание…")}
    error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    if(showBrowser){
        Row(verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={webView?.goBack()},enabled=canBack){Icon(Icons.Outlined.ArrowBack,"Назад")}
            IconButton(onClick={webView?.goForward()},enabled=canForward){Icon(Icons.Outlined.ArrowForward,"Вперёд")}
            IconButton(onClick={webView?.reload()}){Icon(Icons.Outlined.Refresh,"Обновить")}
            IconButton(onClick={if(current.isNotBlank())bookmark(current,title)},enabled=current.isNotBlank()){Icon(Icons.Outlined.BookmarkAdd,"В закладки")}
            IconButton(onClick={if(current.isNotBlank())addToCollection(LibraryReference(current,title.ifBlank{current},"text/uri-list"))},enabled=current.isNotBlank()){Icon(Icons.Outlined.CollectionsBookmark,"В коллекцию")}
            IconButton(onClick={if(current.isNotBlank())shareLink(context,current)},enabled=current.isNotBlank()){Icon(Icons.Outlined.Share,"Поделиться")}
        }
        AndroidView(factory={ctx->
            WebView(ctx).apply{
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                settings.allowFileAccess=false
                settings.allowContentAccess=false
                settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically=false
                if(Build.VERSION.SDK_INT>=26)settings.safeBrowsingEnabled=true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                        if(safeWebUrl(request.url.toString())!=null)return false
                        if(request.isForMainFrame)error="Этот тип ссылки не поддерживается встроенным браузером"
                        return true
                    }
                    override fun onPageFinished(view:WebView,url:String){
                        record(url,view.title.orEmpty());canBack=view.canGoBack();canForward=view.canGoForward()
                    }
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,err:WebResourceError){
                        if(request.isForMainFrame)error=err.description.toString()
                    }
                }
                setDownloadListener(DownloadListener{url,agent,disposition,mime,_->
                    val safe=safeWebUrl(url)
                    if(safe==null){error="Недоступная ссылка скачивания"}
                    else if(!downloading)confirmDownload=WebDownload(safe,URLUtil.guessFileName(safe,disposition,mime),mime?:"application/octet-stream",agent.orEmpty())
                })
                setOnLongClickListener{
                    val hit=hitTestResult
                    val safe=hit.extra?.let{safeWebUrl(it)}
                    if(safe!=null && (hit.type==WebView.HitTestResult.IMAGE_TYPE || hit.type==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)){
                        if(!downloading)confirmDownload=WebDownload(safe,URLUtil.guessFileName(safe,null,null),"application/octet-stream",settings.userAgentString)
                        true
                    }else false
                }
                webView=this
                val target=navTarget ?: current.takeIf{it.isNotBlank()}
                if(target!=null){loadUrl(target);navTarget=null}
            }
        },modifier=Modifier.weight(1f).fillMaxWidth(),onRelease={view->view.stopLoading();view.destroy();if(webView===view)webView=null})
    }else{
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(if(showHistory)"История" else "Мои сайты",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            if(showHistory) TextButton(onClick={history=emptyList();saveSites(context,"history",history)}){Text("Очистить")}
            else IconButton(onClick={editor=Site("","","");editOriginal=null}){Icon(Icons.Outlined.Add,"Добавить сайт")}
        }
        if(!showHistory){
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                AssistChip(onClick={navigate("https://t.me/")},label={Text("Telegram")})
                AssistChip(onClick={navigate("https://vk.com/")},label={Text("VK")})
                AssistChip(onClick={navigate("https://www.reddit.com/")},label={Text("Reddit")})
            }
        }
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Поиск по названию, адресу, категории")})
        val list=(if(showHistory)history else bookmarks).filter{(it.title+" "+it.url+" "+it.category).contains(search,true)}
        if(list.isEmpty())Text(if(showHistory)"История пуста" else "Добавьте любимый сайт или введите адрес",Modifier.padding(vertical=12.dp))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp)){
            items(list,key={it.url}){site->
                ListItem(headlineContent={Text(site.title,maxLines=2,overflow=TextOverflow.Ellipsis)},
                    supportingContent={Text(listOf(site.category,site.url).filter{it.isNotBlank()}.joinToString(" · "),maxLines=2,overflow=TextOverflow.Ellipsis)},
                    leadingContent={Icon(Icons.Outlined.Language,null)},
                    trailingContent={
                        Row{
                            IconButton(onClick={addToCollection(LibraryReference(site.url,site.title,"text/uri-list"))}){Icon(Icons.Outlined.CollectionsBookmark,"В коллекцию")}
                            if(!showHistory)IconButton(onClick={editor=site;editOriginal=site.url}){Icon(Icons.Outlined.Edit,"Изменить")}
                        }
                    },modifier=Modifier.clickable{navigate(site.url)})
                HorizontalDivider()
            }
        }
    }
    editor?.let{site->
        var name by remember(site){mutableStateOf(site.title)}
        var url by remember(site){mutableStateOf(site.url)}
        var category by remember(site){mutableStateOf(site.category)}
        AlertDialog(onDismissRequest={editor=null},title={Text("Закладка")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(name,{name=it},label={Text("Название")},singleLine=true)
                OutlinedTextField(url,{url=it},label={Text("HTTPS адрес")},singleLine=true)
                OutlinedTextField(category,{category=it},label={Text("Категория")},singleLine=true)
            }
        },confirmButton={TextButton(onClick={
            safeWebUrl(url)?.let{safe->bookmarks=(bookmarks.filterNot{it.url==safe||it.url==editOriginal}+Site(safe,name.trim().ifBlank{safe},category.trim())).sortedBy{it.title.lowercase()};saveSites(context,"bookmarks",bookmarks)}
            editor=null
        },enabled=safeWebUrl(url)!=null){Text("Сохранить")}},dismissButton={
            Row{
                if(editOriginal!=null)TextButton(onClick={bookmarks=bookmarks.filterNot{it.url==editOriginal};saveSites(context,"bookmarks",bookmarks);editor=null}){Text("Удалить")}
                TextButton(onClick={editor=null}){Text("Отмена")}
            }
        })
    }
    confirmDownload?.let{job->AlertDialog(onDismissRequest={confirmDownload=null},title={Text("Скачать файл?")},text={Text(job.name+"\n"+Uri.parse(job.url).host.orEmpty())},
        confirmButton={TextButton(onClick={
            confirmDownload=null;pending=job
            saveDownload.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=job.mime.ifBlank{"application/octet-stream"};putExtra(Intent.EXTRA_TITLE,job.name)})
        }){Text("Выбрать место")}},dismissButton={TextButton(onClick={confirmDownload=null}){Text("Отмена")}})}
}
