package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.wire.*
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual management Repository RPC → strict UsageResponse → visibleSnapshot pipeline, entirely on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ManagementUsageRepositoryTest {
    private lateinit var repo:PocketRepository
    private lateinit var db:PocketDatabase
    private lateinit var transport:UsageConnection
    private val filter=UsageFilter.lastDays(7,"Asia/Shanghai",LocalDate.of(2026,11,10))
    private fun assign(name:String,value:Any?)=PocketRepository::class.java.getDeclaredField(name).apply {isAccessible=true}.set(repo,value)
    @Suppress("UNCHECKED_CAST")
    private fun state()=PocketRepository::class.java.getDeclaredField("_state").apply {isAccessible=true}.get(repo) as MutableStateFlow<PocketState>
    private fun computer(id:String)=PairedComputer(ConnectionConfig("wss://fixture.invalid","room","fixture-token","00".repeat(32)),"phone","fixture-secret",id,"fixture")

    @Before fun prepare()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        db=Room.inMemoryDatabaseBuilder(app,PocketDatabase::class.java).build()
        val storage=object:PairingStorage {override fun load():PairedComputer?=null;override fun save(pair:PairedComputer)=Unit;override fun forget()=Unit}
        repo=PocketRepository(app,{error("Fixture must never connect")},storage,db,emptyFlow())
        withTimeout(5_000) {repo.state.first {it.initialized}}
        transport=UsageConnection();assign("pair",computer("pc"));assign("client",transport)
        state().update {it.copy(paired=true,online=true,computerId="pc",capabilities=setOf("usage/read"))}
    }
    @After fun release()=runBlocking<Unit> {
        val scope=PocketRepository::class.java.getDeclaredField("scope").apply {isAccessible=true}.get(repo) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
        assertTrue("Only read-only usage RPC is allowed",transport.calls.all {it.first in setOf("usage/read", "usage/dashboard/read", "usage/requests/read", "usage/v2/read", "usage/v2/requests/read")})
    }

    @Test fun managementReadUsesFixedSourceExplicitUtcQueryAndPreservesPerformanceAndCosts()=runBlocking<Unit> {
        repo.loadUsage(filter).join()
        val params=transport.calls.single().second
        assertEquals(setOf("source_id","query"),params.keys)
        assertEquals("cc_switch",params.string("source_id"))
        assertEquals(filter.legacyJson(),params.obj("query"))
        assertEquals("proxy",params.obj("query").obj("filters").string("data_source"))
        val screen=repo.state.value.usage
        assertEquals(UsagePhase.Ready,screen.phase)
        val response=screen.visibleSnapshot!!.response
        assertEquals(24L,response.source.schemaVersion)
        assertEquals(2uL,response.data!!.totals.performance.ttft.sampleRows)
        assertEquals(100.0,response.data!!.totals.performance.outputSpeed.tokensPerSecond!!,0.0)
        assertEquals("0.1000000000000000000000000001",response.data!!.totals.costs.total.knownUsd)
        assertEquals(1uL,response.coverage.zeroCostRows)
        assertNull(response.coverage.confirmedFreeRows)
        assertEquals("stored_zero_free_status_unknown",response.coverage.zeroCostInterpretation)
        assertFalse(screen.refreshing)
    }

    @Test fun nativeLedgerIsOfferedAndReadOnlyThroughUsageV2UnderItsOwnName()=runBlocking<Unit> {
        enableDashboard()
        repo.loadUsage(filter).join()
        assertEquals(listOf(UsageSourceKind.CcSwitch),repo.state.value.usage.sources)
        state().update {it.copy(capabilities=it.capabilities+"usage.v2")}
        val native=filter.withSource(UsageSourceKind.NativeCodex)
        repo.loadUsage(native,refresh=true).join()
        val (method,params)=transport.calls.last()
        assertEquals("usage/v2/read",method)
        assertEquals(setOf("source","query","refresh"),params.keys)
        assertEquals("native_codex",params.string("source"))
        assertEquals(native.toJson(),params.obj("query"))
        val screen=repo.state.value.usage
        assertEquals(listOf(UsageSourceKind.NativeCodex,UsageSourceKind.CcSwitch),screen.sources)
        assertEquals(UsagePhase.Ready,screen.phase)
        val shown=screen.visibleSnapshot!!.response
        assertEquals("native_codex",shown.source.kind)
        assertFalse(shown.capabilities.cost)
        assertEquals(0uL,shown.data!!.totals.costs.total.knownRows)
        // CC Switch stays on the calls every desktop answers, and each source shows only its own figures.
        repo.loadUsage(filter).join()
        assertEquals("usage/dashboard/read",transport.calls.last().first)
        assertEquals("cc_switch",transport.calls.last().second.string("source_id"))
        assertEquals("cc_switch",repo.state.value.usage.visibleSnapshot!!.response.source.kind)
        assertEquals(listOf(UsageSourceKind.NativeCodex,UsageSourceKind.CcSwitch),repo.state.value.usage.sources)
    }

    @Test fun aComputerWithoutTheNativeLedgerIsAskedForCcSwitchInstead()=runBlocking<Unit> {
        repo.loadUsage(filter.withSource(UsageSourceKind.NativeCodex)).join()
        assertEquals("usage/read",transport.calls.single().first)
        val screen=repo.state.value.usage
        assertEquals(UsageSourceKind.CcSwitch,screen.filter.source)
        assertEquals(listOf(UsageSourceKind.CcSwitch),screen.sources)
        assertEquals("cc_switch",screen.visibleSnapshot!!.response.source.kind)
    }

    @Test fun nativeRequestPagesUseTheSameSourceAndNeverCcSwitchs()=runBlocking<Unit> {
        enableDashboard();state().update {it.copy(capabilities=it.capabilities+"usage.v2")}
        val native=filter.withSource(UsageSourceKind.NativeCodex)
        transport.nativeMore=true
        repo.loadUsage(native).join()
        val first=repo.state.value.usage.visibleRequests!!
        assertTrue(repo.state.value.usage.canPageRequests)
        repo.loadUsageRequests(first.nextCursor!!).join()
        val (method,params)=transport.calls.last()
        assertEquals("usage/v2/requests/read",method)
        assertEquals("native_codex",params.string("source"))
        assertFalse(params.containsKey("source_id"))
        assertEquals(listOf("41","40"),repo.state.value.usage.visibleRequests!!.items.map {it.requestId})
        assertNull(repo.state.value.usage.requestError)
    }

    @Test fun dashboardCapabilitySelectsNewReadContract()=runBlocking<Unit> {
        state().update { it.copy(capabilities=setOf("usage/read", "usage/dashboard/read", "usage/requests/read")) }
        repo.loadUsage(filter).join()
        assertEquals("usage/dashboard/read", transport.calls.single().first)
        assertNotNull(repo.state.value.usage.visibleSnapshot!!.response.data!!.recentRequests)
    }

    @Test fun manualRefreshBypassesCacheOnlyWhenBackendAdvertisesIt()=runBlocking<Unit> {
        enableDashboard()
        repo.loadUsage(filter, refresh=true).join()
        assertFalse(transport.calls.last().second.containsKey("refresh"))
        state().update { it.copy(capabilities=it.capabilities + "usage/refresh") }
        repo.loadUsage(filter, refresh=true).join()
        assertTrue(transport.calls.last().second.flag("refresh"))
        repo.loadUsage(filter).join()
        assertFalse(transport.calls.last().second.containsKey("refresh"))
    }

    @Test fun sourceAndFilterChangesNeverReuseAnotherSourcesSnapshot()=runBlocking<Unit> {
        repo.loadUsage(filter).join()
        val first=repo.state.value.usage.visibleSnapshot!!
        val session=filter.withDataSource(UsageDataSource.CodexSession).withProvider("source-provider").withModel("source-model")
        repo.loadUsage(session).join()
        val current=repo.state.value.usage
        assertEquals(session,current.visibleSnapshot!!.filter)
        assertEquals(UsageDataSource.CodexSession,current.visibleSnapshot!!.response.source.dataSource)
        assertNotEquals(first.filter,current.visibleSnapshot!!.filter)
        assertEquals("codex_session",transport.calls.last().second.obj("query").obj("filters").string("data_source"))
        assertEquals("source-provider",transport.calls.last().second.obj("query").obj("filters").string("provider_id"))
        assertEquals("source-model",transport.calls.last().second.obj("query").obj("filters").string("model"))
        assertNull(current.copy(filter=filter).visibleSnapshot)
    }

    @Test fun lockedStaleRpcRemainsClearlyMarkedAndOfflineRefreshDoesNotSend()=runBlocking<Unit> {
        repo.loadUsage(filter).join()
        transport.locked=true
        repo.loadUsage(filter).join()
        val current=repo.state.value.usage
        assertEquals(UsagePhase.Locked,current.phase)
        assertTrue(current.cached)
        assertEquals("stale",current.visibleSnapshot!!.response.cache!!.state)
        assertEquals("locked",current.visibleSnapshot!!.response.cache!!.staleReason)
        val reads=transport.calls.size
        state().update {it.copy(online=false)}
        repo.loadUsage(filter).join()
        assertEquals(reads,transport.calls.size)
        assertEquals(UsagePhase.Offline,repo.state.value.usage.phase)
        assertFalse(repo.state.value.usage.canQuery)
        assertNotNull(repo.state.value.usage.visibleSnapshot)
    }

    @Test fun lateOldComputerReadCannotReplaceNewComputerUsage()=runBlocking<Unit> {
        val arrived=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        transport.beforeReply={arrived.complete(Unit);withContext(NonCancellable) {release.await()}}
        val old=repo.loadUsage(filter)
        withTimeout(5_000) {arrived.await()}
        assign("pair",computer("pc-new"))
        state().update {it.copy(computerId="pc-new",usage=UsageScreenState())}
        transport.beforeReply=null
        val newFilter=filter.withModel("new-computer-model")
        repo.loadUsage(newFilter).join()
        release.complete(Unit);old.join()
        assertEquals(newFilter,repo.state.value.usage.visibleSnapshot!!.filter)
        assertEquals(newFilter.legacyJson(),repo.state.value.usage.visibleSnapshot!!.response.query)
    }

    private fun enableDashboard() { state().update { it.copy(capabilities=setOf("usage/read", "usage/dashboard/read", "usage/requests/read")) } }
    @Test fun boundedRequestPageAppendsWithoutReplacingDashboardTotals()=runBlocking<Unit> {
        enableDashboard(); repo.loadUsage(filter).join()
        val totals = repo.state.value.usage.visibleSnapshot!!.response.data!!.totals
        val cursor = repo.state.value.usage.visibleRequests!!.nextCursor!!
        repo.loadUsageRequests(cursor).join()
        assertEquals("usage/requests/read", transport.calls.last().first)
        assertEquals(cursor.toJson(), transport.calls.last().second.obj("query").obj("request_page")["cursor"])
        assertEquals(listOf("request-1", "request-2"), repo.state.value.usage.visibleRequests!!.items.map { it.requestId })
        assertEquals(totals, repo.state.value.usage.visibleSnapshot!!.response.data!!.totals)
        assertFalse(repo.state.value.usage.visibleRequests!!.hasMore)
    }
    @Test fun staleRequestPageAndOfflineCallsPreserveExistingRows()=runBlocking<Unit> {
        enableDashboard(); repo.loadUsage(filter).join()
        val page = repo.state.value.usage.visibleRequests!!
        transport.locked = true
        repo.loadUsageRequests(page.nextCursor!!).join()
        assertEquals(page, repo.state.value.usage.visibleRequests)
        assertNotNull(repo.state.value.usage.requestError)
        val count = transport.calls.size
        state().update { it.copy(online=false) }
        repo.loadUsageRequests(page.nextCursor!!).join()
        assertEquals(count, transport.calls.size)
    }
    @Test fun lateRequestPageCannotCrossFilterRevision()=runBlocking<Unit> {
        enableDashboard(); repo.loadUsage(filter).join()
        val arrived = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport.beforeReply = { arrived.complete(Unit); withContext(NonCancellable) { release.await() } }
        val page = repo.loadUsageRequests(repo.state.value.usage.visibleRequests!!.nextCursor!!)
        withTimeout(5_000) { arrived.await() }
        transport.beforeReply = null
        val next = filter.withModel("other-model")
        repo.loadUsage(next).join()
        release.complete(Unit); page.join()
        assertEquals(next, repo.state.value.usage.filter)
        assertNull(repo.state.value.usage.requestFeed)
        assertEquals(listOf("request-1"), repo.state.value.usage.visibleRequests!!.items.map { it.requestId })
    }

    private class UsageConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        val calls=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        var locked=false
        var nativeMore=false
        var beforeReply:(suspend ()->Unit)?=null
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            require(method in setOf("usage/read", "usage/dashboard/read", "usage/requests/read", "usage/v2/read", "usage/v2/requests/read") && commandId==null)
            calls+=method to params
            beforeReply?.invoke()
            val query=params.obj("query")
            if(method.startsWith("usage/v2/")) return nativeReply(method,params)
            val base=if (method=="usage/read") usageFixtureJson() else usageV070Fixture()
            val source=JsonObject(base.obj("source")+mapOf("app_type" to query.obj("filters")["app_type"]!!,"data_source" to query.obj("filters")["data_source"]!!))
            var result=JsonObject(base+mapOf("availability" to JsonPrimitive(if(locked) "locked" else "ready"),"source" to source))
            if (method=="usage/requests/read") {
                val first = base.obj("data").obj("recent_requests")["items"]!!.jsonArray.single().jsonObject
                val row = JsonObject(first + mapOf("request_id" to JsonPrimitive("request-2"), "created_at" to JsonPrimitive(1794268799)))
                result = JsonObject(result + ("data" to buildJsonObject { put("requests", buildJsonObject {
                    put("items", JsonArray(listOf(row))); put("limit", 50); put("has_more", false); put("next_cursor", JsonNull)
                }) }))
            }
            return buildJsonObject {
                put("schema",1);put("query",query);put("result",result)
                put("cache",buildJsonObject {put("state",if(locked) "stale" else "fresh");put("sampled_at",1794268800);put("age_seconds",if(locked) 90 else 0);put("ttl_seconds",60);put("stale_reason",if(locked) JsonPrimitive("locked") else JsonNull)})
            }
        }
        /** The desktop's native-ledger reply for whatever dates were asked, one request per page. */
        private fun nativeReply(method:String,params:JsonObject):JsonObject {
            require(params.string("source")=="native_codex" && "source_id" !in params)
            val query=params.obj("query")
            val edges=query["bucket_edges"]!!.jsonArray.map {it.jsonPrimitive.long}
            val zone=java.time.ZoneId.of("Asia/Shanghai")
            val filter=UsageFilter(java.time.Instant.ofEpochSecond(edges.first()).atZone(zone).toLocalDate(),
                java.time.Instant.ofEpochSecond(edges.last()).atZone(zone).toLocalDate().minusDays(1),"Asia/Shanghai",source=UsageSourceKind.NativeCodex)
            val reply=usageNativeFixtureJson(filter)
            val result=reply.obj("result")
            val row=result.obj("data").obj("recent_requests")["items"]!!.jsonArray.single().jsonObject
            fun requests(item:JsonObject,more:Boolean)=buildJsonObject {
                put("items",JsonArray(listOf(item)));put("limit",50);put("has_more",more)
                put("next_cursor",if(more) buildJsonObject {put("created_at",item["created_at"]!!);put("request_id",item["request_id"]!!)} else JsonNull)
            }
            if(method=="usage/v2/requests/read") {
                val older=JsonObject(row+mapOf("request_id" to JsonPrimitive("40"),"created_at" to JsonPrimitive(row["created_at"]!!.jsonPrimitive.long-30)))
                return JsonObject(reply+mapOf("query" to query,"result" to buildJsonObject {
                    put("availability","ready");put("source",result["source"]!!);put("capabilities",result["capabilities"]!!);put("errors",result["errors"]!!)
                    put("data",buildJsonObject {put("requests",requests(older,false))})
                }))
            }
            val data=JsonObject(result.obj("data")+("recent_requests" to requests(row,nativeMore)))
            return JsonObject(reply+mapOf("query" to query,"result" to JsonObject(result+("data" to data))))
        }
        override fun close()=Unit
    }
}
