package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Recorded-looking states for the assistant's stage. Times are fixed; nothing here reads a clock. */
internal object AssistantStageFixtures {
    const val At = 1_893_456_000_000L
    private fun record(id: String, sequence: Long, state: String, unread: Boolean = false) = AssistantTaskRecord(id, sequence, state, null, null, sequence, unread)
    val base = AssistantSnapshot(enabled = true, runtimeId = "r", assistantId = "a", paused = false, ready = true, fresh = true, protocolVersion = 2,
        acceptingMessages = true, settings = AssistantSettings("认真协助", "简洁", null, "UTC"))
    private val greeting = listOf(
        AssistantChatMessage("u1", "User", "把登录超时修一下，顺便看看回归测试为什么不稳定。", At),
        AssistantChatMessage("a1", "Assistant", "好的。我先确认超时发生在哪一步，再安排修复；回归测试的问题单独开一项任务来查。", At + 1_000, "c1"))

    fun idle() = AssistantUiState(base, greeting, computerId = "pc")

    val running = AssistantTask("login", "修复登录超时", AssistantTaskStatus.Running, "run-login", false, "把登录请求的超时从 5 秒改为可配置，并补上重试。")
    val runningJob = AssistantJob("login", "修复登录超时", "running", writes = true, directory = "D:/work/pocket-shop", threadId = "native-login",
        workspaceMode = "worktree", branch = "assistant/login-timeout", diffStats = AssistantJobDiffStats(2, 42, 11))
    fun running(live: Boolean = true) = AssistantUiState(base.copy(tasks = listOf(running), jobs = listOf(runningJob),
        operations = listOf(AssistantOperation("run-login", "Execution", "login", AssistantOperationPhase.Running, false, "native-login", null, null)),
        domain = AssistantDomain("main:a", listOf(record("login", 1, "running", true)), emptyList(), emptyList())),
        greeting + AssistantChatMessage("a2", "Assistant", "已安排「修复登录超时」，在独立分支上进行。", At + 2_000, "c1", taskId = "login"), computerId = "pc",
        liveJobStages = if (live) mapOf("login" to AssistantLiveJobStage("running", "command", "正在跑回归测试", 2, "run-login", "native-login")) else emptyMap())

    val approval = AssistantOperation("run-login", "Execution", "login", AssistantOperationPhase.Waiting, false, "native-login",
        AssistantQuestion("approve-1", AssistantQuestionKind.Approval,
            """{"command":"git push origin assistant/login-timeout","reason":"把修复推送到远端，方便你在电脑上检查","cwd":"D:/work/pocket-shop","availableDecisions":["accept","decline"]}"""), null)
    fun pending(): AssistantUiState = running(false).let { state ->
        state.copy(snapshot = state.snapshot!!.copy(tasks = listOf(running.copy(status = AssistantTaskStatus.WaitingUser)), operations = listOf(approval),
            domain = AssistantDomain("main:a", listOf(record("login", 1, "waiting_user")), emptyList(), emptyList())))
    }

    fun delivered(lastMerge: AssistantJobMerge? = null, ready: Boolean = true): AssistantUiState = running(false).let { state ->
        state.copy(snapshot = state.snapshot!!.copy(tasks = listOf(running.copy(status = AssistantTaskStatus.Done, activeRunId = null)),
            jobs = listOf(runningJob.copy(status = "done", readyToMerge = ready, lastMerge = lastMerge)),
            operations = listOf(AssistantOperation("run-login", "Execution", "login", AssistantOperationPhase.Completed, false, "native-login", null,
                "登录超时改为可配置（默认 15 秒），失败后重试两次；回归测试全部通过。")),
            domain = AssistantDomain("main:a", listOf(record("login", 1, "done")), emptyList(), emptyList())))
    }

    fun streaming(): AssistantUiState = running().let { state ->
        state.copy(snapshot = state.snapshot!!.copy(operations = state.snapshot!!.operations +
            AssistantOperation("c2", "Coordinator", null, AssistantOperationPhase.Running, false, "coordinator", null, null)),
            messages = state.messages + AssistantChatMessage("u2", "User", "测试不稳定的原因找到了吗？", At + 3_000),
            streamingCoordinatorOpId = "c2", streamingCoordinatorText = "找到了一部分：有两个用例共用同一个临时目录，并发运行时会互相覆盖。\n\n我正在确认另外一个")
    }

    fun offline() = idle().copy(error = "连接电脑后可继续使用助理")

    val schedule = AssistantSchedule("s", "每天早上整理待办", buildJsonObject { put("Daily", buildJsonObject { put("hour", 9); put("minute", 0) }) }, "UTC", At + 3_600_000, false, null)

    val models = listOf(NativeModel("native-1", "actual-model", "电脑传入模型", "", emptyList(), "", false, true),
        NativeModel("native-2", "fast-model", "快速模型", "", emptyList(), "", false, true))
    val runtimes = listOf(AssistantRuntimeChoice("r", "助理 CLI · 0.171.0"))
    val workspaces = listOf(AssistantWorkspaceChoice("r", "shop", "pocket-shop", true, permissionStatus = "已核验 · 可写"))

    /** Every secondary page has something to show: tasks in each state, results, plans with history, memory with revisions. */
    fun pages(coordinatorModel: String? = "fast-model"): AssistantUiState {
        val failed = AssistantTask("report", "整理本周发布简报，附上每项改动的来源链接和负责人", AssistantTaskStatus.Failed, null, false, "缺少两项改动的来源。")
        val queued = AssistantTask("docs", "更新安装文档", AssistantTaskStatus.Queued, null, false, null)
        val unknown = AssistantTask("sync", "同步依赖版本", AssistantTaskStatus.Paused, "run-sync", true, null)
        val delivered = delivered()
        val snapshot = delivered.snapshot!!
        return delivered.copy(snapshot = snapshot.copy(
            settings = AssistantSettings("认真协助，先核对再动手", "直接、简洁", "actual-model", "Asia/Shanghai", AssistantNotifications(true, true, false), coordinatorModel),
            tasks = snapshot.tasks + listOf(failed, queued, unknown),
            operations = snapshot.operations + AssistantOperation("run-report", "Execution", "report", AssistantOperationPhase.Failed, false, "native-report", null, "只找到三项改动的来源。"),
            events = listOf(AssistantEvent(1, "e1", "run-login", "started", "开始修复登录超时", At, taskId = "login"),
                AssistantEvent(2, "e2", "run-login", "progress", "回归测试通过", At + 60_000, taskId = "login")),
            domain = AssistantDomain("main:a", listOf(record("login", 1, "done"), record("report", 2, "failed", true), record("docs", 3, "queued"),
                record("sync", 4, "needs_verification")), emptyList(), listOf(AssistantMemoryRevision("m1", 1, "提交前先跑回归测试", "saved", "你在对话中的陈述"),
                AssistantMemoryRevision("m1", 2, "提交前先跑回归测试和静态检查", "saved", "你在对话中的陈述"))),
            artifacts = listOf(AssistantArtifact("a-text", "login", "run-login", "登录超时修复说明", "text", "## 改动\n\n- 超时改为可配置\n- 失败后重试两次", null, "v2-8f31c0", At, "available", "native-login"),
                AssistantArtifact("a-file", "login", "run-login", "regression-report.pdf", "file", null, null, "v1-22ab", At, "available", "native-login", "application/pdf", 482_133),
                AssistantArtifact("a-link", "report", "run-report", "发布看板", "link", null, "https://example.com/releases/board", "v1", At, "external")),
            memories = listOf(AssistantMemory("m1", "提交前先跑回归测试和静态检查", "你在对话中的陈述", "personal", true, At, listOf(1, 2)),
                AssistantMemory("m2", "pocket-shop 的发布分支是 release/*", null, "project", false, At, listOf(1))),
            schedules = listOf(schedule, AssistantSchedule("s2", "每周一汇总上周合入的改动，按模块列出", buildJsonObject { put("Weekly", buildJsonObject { put("weekday", 0); put("hour", 10); put("minute", 30) }) },
                "Asia/Shanghai", At + 86_400_000, true, "上次执行时电脑离线", paused = true, missedPolicy = "run_once", writes = true)),
            scheduleRuns = listOf(AssistantScheduleRun("run-1", "s", "docs", At - 86_400_000, "done", false), AssistantScheduleRun("run-2", "s", null, At - 3_600_000, "skipped", true))))
    }
}
