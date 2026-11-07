package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** All writes are restricted to newly created acceptance chats in the named fixture workspace. */
@RunWith(AndroidJUnit4::class)
class LiveV03Test {
    @Test fun desktopAndCliCommonRemoteOperations() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveV03")=="true")
        val fixtureCwd=args.getString("fixtureCwd")?.takeIf { it.isNotBlank() }
            ?: error("Explicit fixtureCwd required for dedicated acceptance chats")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo=app.repository
        val results=linkedMapOf<String,JsonElement>()
        var stage="connect"
        suspend fun healthy(){
            // Room invalidation is asynchronous after a mutation Job finishes. Wait
            // for that publication; never treat an unknown outcome as accepted.
            withTimeout(5_000){repo.state.first { s ->
                s.error!=null || s.unresolvedCommands.none { it.status=="submitted" } ||
                    s.unresolvedCommands.any { it.status!="submitted" }
            }}
            check(repo.state.value.error==null&&!repo.state.value.uncertainSend){"Operation did not finish conclusively"}
        }
        try {
            runBlocking { withTimeout(720_000) {
                repo.state.first { it.initialized }
                check(repo.state.value.paired)
                withContext(Dispatchers.Main){repo.setForeground(true)}
                withTimeout(30_000){repo.state.first { it.online }}
                val only=args.getString("source")
                for(source in listOf("desktop","cli").filter { only==null||only==it }) {
                    stage="$source discovery"
                    val runtime=withTimeout(30_000){repo.state.first { s->s.runtimes.any { it.source==source&&it.online&&s.supports("thread/start",it) } }}.runtimes.first { it.source==source&&it.online&&repo.state.value.supports("thread/start",it) }
                    stage="$source create"
                    repo.create(runtime,fixtureCwd).join();healthy()
                    val original=repo.state.value.selected!!
                    val checks=linkedMapOf<String,JsonElement>("nativeThreadId" to JsonPrimitive(original.key.nativeId))
                    results[source]=JsonObject(checks)
                    fun supported(method:String)=repo.state.value.supports(method)
                    stage="$source model catalog"
                    repo.refreshModels().join()
                    check(repo.state.value.modelCatalogState==ModelCatalogState.Ready)
                    checks["modelCatalog"]=JsonPrimitive(true)
                    args.getString("testModel")?.let { requested ->
                        val model=repo.state.value.models.firstOrNull { it.model==requested } ?: error("Requested acceptance model is not advertised")
                        repo.chooseModel(model,model.efforts.firstOrNull { it=="low" })
                        checks["selectedModel"]=JsonPrimitive(model.model)
                    }
                    if(supported("thread/name/set")){repo.rename("Codex Pocket 0.3 acceptance $source").join();healthy();checks["rename"]=JsonPrimitive(true)}
                    stage="$source native reply"
                    val marker="POCKET_V03_${source}_${System.currentTimeMillis()}"
                    val metadataOnly=args.getString("metadataOnly")=="true"
                    if(!metadataOnly) {
                    repo.editDraft("Reply exactly $marker. Do not use tools.")
                    repo.send().join();healthy()
                    withTimeout(120_000){repo.state.first { s->s.timeline.activeTurn==null&&s.timeline.items.values.any { it.type=="agentMessage"&&it.complete&&it.text.contains(marker) } }}
                    checks["nativeReply"]=JsonPrimitive(true)
                    checks["queueSteerAndStop"] = JsonPrimitive("notRun: bounded fixture required")
                    }
                    stage="$source metadata"
                    repo.refresh("Codex Pocket 0.3 acceptance $source").join();healthy()
                    val named=repo.state.value.chats.first { it.key==original.key }
                    repo.pin(named).join();healthy()
                    withTimeout(10_000){repo.state.first { s->s.chats.any { it.key==named.key&&it.pinned } }}
                    repo.pin(named.copy(pinned=true)).join();healthy()
                    checks["searchPinUnpin"]=JsonPrimitive(true)
                    if(supported("thread/archive")&&supported("thread/unarchive")) {
                        stage="$source archive"
                        repo.archive(named).join();healthy()
                        stage="$source archived listing"
                        repo.refresh("Codex Pocket 0.3 acceptance $source",archived=true).join();healthy()
                        val archived=repo.state.value.chats.first { it.key==original.key&&it.archived }
                        stage="$source unarchive"
                        repo.archive(archived).join();healthy()
                        repo.refresh("Codex Pocket 0.3 acceptance $source").join();healthy()
                        repo.select(repo.state.value.chats.first { it.key==original.key&&!it.archived }).join();healthy()
                        checks["archiveRestore"]=JsonPrimitive(true)
                    }
                    stage="$source file preview"
                    if(supported("workspace/read")){
                        repo.readSource("README.md").join();healthy();check(repo.state.value.advanced.sourcePreview?.text?.contains("Codex Pocket acceptance fixture")==true)
                        repo.clearSourcePreview();checks["workspaceRead"]=JsonPrimitive(true)
                    }else checks["workspaceRead"]=JsonPrimitive("unsupported")
                    if(supported("references/list")){repo.loadReferences("").join();healthy();checks["references"]=JsonPrimitive(true)}
                    stage="$source goal"
                    if(supported("thread/goal/set")&&supported("thread/goal/clear")){
                        repo.setGoal("Verify the dedicated Pocket acceptance chat").join();healthy()
                        check(repo.state.value.advanced.goal!=null)
                        repo.setGoal(null).join();healthy();checks["goalSetClear"]=JsonPrimitive(true)
                    }else checks["goalSetClear"]=JsonPrimitive("unsupported")
                    stage="$source fork"
                    if(supported("thread/fork")){
                        repo.fork().join();healthy()
                        val fork=repo.state.value.selected!!;check(fork.key.nativeId!=original.key.nativeId)
                        checks["forkIdentity"]=JsonPrimitive(true)
                        repo.select(named).join();healthy()
                        repo.fork(sideChat=true).join();healthy()
                        check(repo.state.value.selected!!.key.nativeId!=original.key.nativeId)
                        checks["sideChatIdentity"]=JsonPrimitive(true)
                        repo.select(named).join();healthy()
                    }else checks["forkIdentity"]=JsonPrimitive("unsupported")
                    stage="$source reconnect and draft"
                    repo.editDraft("保留的中文草稿")
                    withContext(Dispatchers.Main){repo.setForeground(false)}
                    withTimeout(10_000){repo.state.first { !it.online }}
                    withContext(Dispatchers.Main){repo.setForeground(true)}
                    withTimeout(30_000){repo.state.first { it.online }}
                    check(repo.state.value.selected?.key==original.key)
                    check(repo.state.value.draft?.text=="保留的中文草稿")
                    if(!metadataOnly) check(repo.state.value.timeline.items.values.count { it.type=="agentMessage"&&it.text.contains(marker) }==1)
                    checks["draftAndDuplicateFreeReconnect"]=JsonPrimitive(true)
                    repo.editDraft("")
                    results[source]=JsonObject(checks)
                }
                writeLiveReport("acceptance-report.json",buildJsonObject{put("passed",true);put("version","0.3.0");put("metadataOnly",args.getString("metadataOnly")=="true");put("checks",JsonObject(results))})
            } }
        }catch(error:Throwable){
            writeLiveReport("acceptance-report.json",buildJsonObject{
                put("passed",false);put("stage",stage);put("exception",error.javaClass.simpleName);put("checks",JsonObject(results));put("online",repo.state.value.online)
                put("safeUiError",repo.state.value.error);put("uncertain",repo.state.value.uncertainSend)
                put("conflict",repo.state.value.selected?.conflict);put("activeTurn",repo.state.value.timeline.activeTurn)
                put("commands",buildJsonArray { repo.state.value.commands.forEach { add(buildJsonObject {put("method",it.method);put("status",it.status)}) } })
            })
            throw AssertionError("0.3 live acceptance failed at $stage (${error.javaClass.simpleName}); sensitive details omitted")
        }finally{runBlocking{withContext(Dispatchers.Main){repo.setForeground(false)}}}
    }
}
