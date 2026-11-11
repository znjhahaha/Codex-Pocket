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
        assertTrue("Only read-only usage RPC is allowed",transport.calls.all {it.first=="usage/read"})
    }

    @Test fun managementReadUsesFixedSourceExplicitUtcQueryAndPreservesPerformanceAndCosts()=runBlocking<Unit> {
        repo.loadUsage(filter).join()
        val params=transport.calls.single().second
        assertEquals(setOf("source_id","query"),params.keys)
        assertEquals("cc_switch",params.string("source_id"))
        assertEquals(filter.toJson(),params.obj("query"))
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
        assertEquals(newFilter.toJson(),repo.state.value.usage.visibleSnapshot!!.response.query)
    }

    private class UsageConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        val calls=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        var locked=false
        var beforeReply:(suspend ()->Unit)?=null
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            require(method=="usage/read" && commandId==null)
            calls+=method to params
            beforeReply?.invoke()
            val query=params.obj("query")
            val base=usageFixtureJson()
            val source=JsonObject(base.obj("source")+mapOf("app_type" to query.obj("filters")["app_type"]!!,"data_source" to query.obj("filters")["data_source"]!!))
            val result=JsonObject(base+mapOf("availability" to JsonPrimitive(if(locked) "locked" else "ready"),"source" to source))
            return buildJsonObject {
                put("schema",1);put("query",query);put("result",result)
                put("cache",buildJsonObject {put("state",if(locked) "stale" else "fresh");put("sampled_at",1794268800);put("age_seconds",if(locked) 90 else 0);put("ttl_seconds",60);put("stale_reason",if(locked) JsonPrimitive("locked") else JsonNull)})
            }
        }
        override fun close()=Unit
    }
}
