// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val lightSigner = SignerLight(clock)
    private val site = "https://hongguoduanju.com"
    private fun text(url: String, signed: SignerLight.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        // Website rows use series_name/series_cover; landpage/v rows are flat (title/cover).
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { o.str("title").ifEmpty { item.str("name") } } },cleanUrl(o.str("series_cover").ifEmpty { o.str("cover") }),o.str("series_intro").ifEmpty { o.str("video_desc") },o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        // The website category page no longer carries data for every genre; browse the
        // app landpage/v catalog with server cursors (adapted from jipinwa/hongguo).
        return catalog(type,page)
    }
    /** Server-cursor state for one landpage/v genre feed (adapted from jipinwa/hongguo
     *  provider_hongguo_catalog.go): offset + session_id advance forward only. */
    private class FeedCursor {
        var offset=0; var sessionId=""; var pageSignature=""; var exhausted=false; var updatedAt=0L
        val rows=mutableListOf<Series>(); val pageEnds=mutableListOf<Int>()
    }
    private val feedCursors=linkedMapOf<ContentType,FeedCursor>()
    private fun feedFor(type: ContentType): FeedCursor = synchronized(feedCursors) {
        val existing=feedCursors[type]
        // Sessions older than 30 minutes are no longer honored by the server; rebuild from page 1.
        if(existing!=null && existing.pageEnds.isNotEmpty() && clock()-existing.updatedAt>30*60*1000) feedCursors.remove(type)
        feedCursors.getOrPut(type) { FeedCursor().also { it.updatedAt=clock() } }
    }
    /** Serve one page of a genre feed. The server feed is forward-only, so fetched rows
     *  accumulate locally: 上一页 serves cached rows, 下一页 fetches forward. */
    private fun catalog(type: ContentType,page: Int): CatalogPage {
        val feed=feedFor(type)
        synchronized(feed) {
            while(feed.pageEnds.size<page && !feed.exhausted) fetchFeedPage(type,feed)
            if(page>feed.pageEnds.size) return CatalogPage(emptyList(),false)
            val start=if(page==1) 0 else feed.pageEnds[page-2]
            val end=feed.pageEnds[page-1]
            val hasMore=page<feed.pageEnds.size || !feed.exhausted
            return CatalogPage(feed.rows.subList(start,end).toList(),hasMore)
        }
    }
    /** Fetch the next landpage/v page; advances the cursor only on a valid, advancing response. */
    private fun fetchFeedPage(type: ContentType,feed: FeedCursor): List<Series> {
        val payload=JSONObject().apply {
            put("req_scene",type.scene)
            put("offset",feed.offset)
            put("limit",20)
            put("req_type","only_content")
            put("need_selector_panel",false)
            put("client_req_type",if(feed.offset>0) 2 else 3)
            put("session_id",feed.sessionId)
            put("filter_ids","")
            put("select_items",JSONObject().apply {
                put("genre",JSONArray().put(type.genre))
                put("sort",JSONArray().put("online_time"))
                put("gender",JSONArray())
                put("category_dim_theme",JSONArray())
                put("category_dim_role",JSONArray())
                put("category_dim_epoch",JSONArray())
                put("online_time",JSONArray())
                put("creation_status",JSONArray())
            })
        }
        val signed=lightSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",VendorConstants.device,payload.toString().toByteArray())
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0 && data.optInt("Code",-1)!=0) throw IOException("分类目录暂不可用")
        val section=data.optJSONObject("data") ?: throw IOException("分类目录格式异常")
        val raw=section.optJSONArray("video_data") ?: throw IOException("分类目录未返回剧集")
        val rows=valid(raw.objects().map(::card))
        val hasMore=section.optBoolean("has_more",false)
        val next=section.optInt("next_offset",-1)
        val session=section.str("session_id")
        if(session.length>4096 || session.any { it=='\r' || it=='\n' || it=='\u0000' }) throw IOException("分类分页会话无效，已保留上次位置")
        val signature=raw.objects().map { it.str("series_id") }.filter { it.isNotEmpty() }.sorted().joinToString("\n")
            .toByteArray(Charsets.UTF_8).let { java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
        if(hasMore && (raw.length()==0 || next<=feed.offset || next>1_000_000 || signature==feed.pageSignature)) throw IOException("分类分页未前进，已保留上次位置")
        feed.rows+=rows; feed.pageEnds+=feed.rows.size
        feed.offset=if(hasMore) next else feed.offset
        feed.sessionId=session; feed.pageSignature=signature
        feed.exhausted=!hasMore; feed.updatedAt=clock()
        return rows
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The old search/tab/v endpoint returns an empty 200 for any signature now.
        // Short dramas and AI dramas: website search. Comics: genre feed filtered locally.
        if(type==ContentType.COMIC) return genreFeedSearch(keyword,page,type)
        val data=router("$site/search/${SignerLight.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }
    private fun genreFeedSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        // landpage/v cannot filter by title server-side; pull genre feed pages and match locally.
        // Pagination beyond what we scanned reports no results rather than wrong ones.
        val wanted=keyword.trim()
        val feed=feedFor(type)
        synchronized(feed) {
            val maxScanPages=minOf(page+9,100)
            for(scan in 1..maxScanPages) {
                while(feed.pageEnds.size<scan && !feed.exhausted) fetchFeedPage(type,feed)
                if(feed.pageEnds.size<scan) break
                val hit=feed.rows.filter { it.title.contains(wanted) || wanted.contains(it.title.take(12)) }.distinctBy { it.id }
                if(hit.isNotEmpty() && scan>=page) return CatalogPage(hit,true)
            }
            return CatalogPage(emptyList(),false)
        }
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // video_model/v1 returns the video_list directly (plain main_url, no fallback chain).
        // The old multi_video_model -> fallback_api chain stopped returning data (empty 200).
        val payload="""{"video_id":"$id","content_type":1,"biz_param":{"need_all_video_definition":true,"video_platform":3}}""".toByteArray()
        val signed=lightSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/",VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("Code",data.optInt("code",0))!=0) throw IOException("播放接口暂不可用")
        val dataObj=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val rawModel=dataObj.opt("video_model")
        val model=when(rawModel) { is JSONObject -> rawModel; else -> runCatching { JSONObject(rawModel.toString()) }.getOrElse { throw IOException("播放信息格式异常") } }
        // video_list may be a JSONObject keyed by quality or a plain JSONArray.
        val entries: List<Pair<String,JSONObject>> =when(val rawList=model.opt("video_list")) {
            is JSONObject -> rawList.keys().asSequence().mapNotNull { k -> rawList.optJSONObject(k)?.let { k to it } }.toList()
            is JSONArray -> (0 until rawList.length()).mapNotNull { i -> rawList.optJSONObject(i)?.let { "$i" to it } }
            else -> emptyList()
        }
        val rows=entries.filter {
            (it.second.str("main_url").isNotEmpty() || it.second.optJSONObject("encrypt_info")?.str("spade_a")?.let { spade -> spade.isNotEmpty() } == true) &&
                it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) ->
            val meta=o.optJSONObject("video_meta") ?: JSONObject()
            val q=qualityNumber(o.str("quality_desc").ifEmpty { o.str("definition").ifEmpty { meta.str("definition").ifEmpty { meta.str("vheight").ifEmpty { o.str("vheight").ifEmpty { k } } } } })
            Triple(q,k,o)
        }.sortedWith(compareByDescending<Triple<Int,String,JSONObject>> { it.first }.thenBy { if (it.third.str("codec_type").lowercase() in setOf("h264","avc1","")) 0 else 1 }).toList()
        val selected=rows.firstOrNull { it.first in 1..maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third
        var url=cleanUrl(item.str("main_url"))
        if(url.isEmpty()) {
            // Encoded entries may hide the address behind base64; try decoding the string forms.
            val enc=item.optJSONObject("encrypt_info")
            val candidate=enc?.str("main_url") ?: item.str("backup_url")
            if(candidate.isNotEmpty()) {
                val decoded=runCatching { String(decode64(candidate),Charsets.UTF_8) }.getOrDefault("")
                if(decoded.startsWith("http")) url=cleanUrl(decoded)
            }
        }
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val encryption=item.optJSONObject("encrypt_info")
        val spade=encryption?.str("spade_a") ?: item.str("spade_a")
        val key=spade.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
