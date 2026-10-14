package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else {
            assertEquals("/novel/player/video_model/v1/",request.url.encodedPath)
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("$index",JSONObject().put("video_meta",JSONObject().put("vheight",height)).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows))).toString()
        }
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun homeBrowsesLandpageFeedWithServerCursor() {
        val bodies=mutableListOf<String>()
        var call=0
        val repo=repository { request ->
            assertEquals("/reading/distribution/category/landpage/v/",request.url.encodedPath)
            val body=request.body?.let { b -> val sink=okio.Buffer(); b.writeTo(sink); sink.readUtf8() } ?: ""
            bodies+=body
            call++
            landpagePage(landpageRows("99$call" to "第${call}页漫剧"),hasMore=true,nextOffset=call*20,sessionId="s$call")
        }
        val first=repo.home(1,ContentType.COMIC)
        assertEquals(listOf("991"),first.items.map { it.id })
        assertTrue(first.hasMore)
        val second=repo.home(2,ContentType.COMIC)
        assertEquals(listOf("992"),second.items.map { it.id })
        assertEquals(20,JSONObject(bodies[1]).getInt("offset"))
        assertEquals("s1",JSONObject(bodies[1]).getString("session_id"))
        assertEquals("comic_series",JSONObject(bodies[0]).getJSONObject("select_items").getJSONArray("genre").getString(0))
    }

    @Test fun homeGoingBackServesCachedRowsWithoutRefetching() {
        var calls=0
        val repo=repository { request ->
            calls++
            landpagePage(landpageRows("$calls" to "第${calls}页"),hasMore=calls<2,nextOffset=calls*20,sessionId="s$calls")
        }
        repo.home(1,ContentType.SHORT)
        repo.home(2,ContentType.SHORT)
        assertEquals(2,calls)
        val back=repo.home(1,ContentType.SHORT)
        assertEquals(listOf("1"),back.items.map { it.id })
        assertEquals(2,calls) // cached page 1 is served without a new server call
    }

    @Test fun homeStopsAdvancingWhenServerReportsExhaustion() {
        var call=0
        val repo=repository { request ->
            call++
            landpagePage(landpageRows("$call" to "第${call}页"),hasMore=call<3,nextOffset=call*20,sessionId="s$call")
        }
        repo.home(1,ContentType.AI)
        assertTrue(repo.home(2,ContentType.AI).hasMore)
        // Page 3 is the last one the server offers (has_more=false on its third response).
        val third=repo.home(3,ContentType.AI)
        assertEquals(listOf("3"),third.items.map { it.id })
        assertFalse(third.hasMore)
        assertEquals(3,call)
        // Page 4 was never fetched and the feed is exhausted: empty page, no server call.
        val fourth=repo.home(4,ContentType.AI)
        assertTrue(fourth.items.isEmpty())
        assertFalse(fourth.hasMore)
        assertEquals(3,call)
    }

    @Test fun aiHomeUsesAiSeriesGenre() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            val body=request.body?.let { b -> val sink=okio.Buffer(); b.writeTo(sink); sink.readUtf8() } ?: ""
            bodies+=body
            landpagePage(landpageRows("777" to "AI短剧"),hasMore=false,nextOffset=20,sessionId="s1")
        }
        assertEquals(listOf("777"),repo.home(1,ContentType.AI).items.map { it.id })
        assertEquals("ai_series",JSONObject(bodies.single()).getJSONObject("select_items").getJSONArray("genre").getString(0))
    }

    @Test fun stalledFeedPageIsRejectedAndCursorKept() {
        var call=0
        val repo=repository { request ->
            call++
            // Second page repeats the first page's rows with an advancing next_offset.
            landpagePage(landpageRows("999" to "第一页漫剧"),hasMore=true,nextOffset=20,sessionId="s$call")
        }
        repo.home(1,ContentType.COMIC)
        assertThrows(IOException::class.java) { repo.home(2,ContentType.COMIC) }
        assertEquals(2,call)
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.comicRanking(1) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.comicRanking(2)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchMatchesGenreCatalogAndKeepsScanningForward() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            assertEquals("/reading/distribution/category/landpage/v/",request.url.encodedPath)
            val body=request.body?.let { b -> val sink=okio.Buffer(); b.writeTo(sink); sink.readUtf8() } ?: ""
            bodies+=body
            val offset=JSONObject(body).getInt("offset")
            // Feed exhausts after one page; further scans stop instead of looping.
            landpagePage(landpageRows("999" to "修仙漫剧","888" to "其他漫剧"),hasMore=false,nextOffset=offset+20,sessionId="s${bodies.size}")
        }
        assertEquals(listOf("999"),repo.search("修仙",1,ContentType.COMIC).items.map { it.id })
        assertEquals(0,JSONObject(bodies.first()).getInt("offset"))
        // Page 2 of the same search: the feed is exhausted, no more pages to scan.
        assertTrue(repo.search("修仙",2,ContentType.COMIC).items.isEmpty())
        assertEquals(1,bodies.size)
    }

    private fun landpageRows(vararg ids: Pair<String,String>) = JSONArray().apply {
        ids.forEach { (id,title) -> put(JSONObject().put("series_id",id).put("title",title).put("episode_cnt",8).put("cover","https://example.com/c.jpg")) }
    }

    private fun landpagePage(rows: JSONArray,hasMore: Boolean,nextOffset: Int,sessionId: String) =
        JSONObject().put("code",0).put("data",JSONObject()
            .put("video_data",rows).put("has_more",hasMore).put("next_offset",nextOffset).put("session_id",sessionId)).toString()

    @Test fun comicSearchScansGenreCatalogAndMatchesTitles() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            assertEquals("/reading/distribution/category/landpage/v/",request.url.encodedPath)
            val body=request.body?.let { b -> val sink=okio.Buffer(); b.writeTo(sink); sink.readUtf8() } ?: ""
            bodies+=body
            val offset=JSONObject(body).getInt("offset")
            landpagePage(landpageRows("999" to "修仙漫剧","888" to "其他漫剧"),hasMore=false,nextOffset=offset+20,sessionId="s${bodies.size}")
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),result.items.map { it.id })
        assertEquals(0,JSONObject(bodies.single()).getInt("offset"))
        assertEquals("comic_series",JSONObject(bodies.single()).getJSONObject("select_items").getJSONArray("genre").getString(0))
    }

    @Test fun comicSearchMissReportsEmptyWithoutServerFallback() {
        val repo=repository { request ->
            val body=request.body?.let { b -> val sink=okio.Buffer(); b.writeTo(sink); sink.readUtf8() } ?: ""
            val offset=JSONObject(body).getInt("offset")
            landpagePage(landpageRows("888" to "无关漫剧"),hasMore=false,nextOffset=offset+20,sessionId="s")
        }
        assertTrue(repo.search("不存在",1,ContentType.COMIC).items.isEmpty())
    }

    @Test fun shortSearchUsesWebsiteDirectly() {
        val repo=repository { request ->
            assertEquals("/search/%E7%9F%AD%E5%89%A7",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
    }
}
