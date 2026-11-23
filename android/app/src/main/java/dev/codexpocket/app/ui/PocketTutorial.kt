package dev.codexpocket.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.*

internal class TourPoint(val icon: ImageVector, val text: String)
internal class TourChapter(val scene: TourScene, val kicker: String, val title: String, val body: String, val points: List<TourPoint>)

/** The whole tutorial, in the order a new user meets the app. Add a chapter here and it gets a page, a dot and a scene. */
internal val PocketTourChapters: List<TourChapter> = listOf(
    TourChapter(TourScene.Welcome, "欢迎", "把 Codex 装进口袋",
        "电脑上的 Codex 继续干活，你在手机上接着聊、看进度、点确认。花一分钟走一遍，之后随时能在设置里重看。", listOf(
            TourPoint(PocketIcons.Computer, "会话、草稿和历史都留在原处，手机只是另一扇窗"),
            TourPoint(PocketIcons.ShieldCheck, "连接全程加密，配对过的手机才能访问"),
            TourPoint(PocketIcons.Sparkle, "左右滑动翻页，右上角可以跳过"))),
    TourChapter(TourScene.Pair, "第一步", "连接你的电脑",
        "在电脑上打开 Codex Pocket 助手，选择「配对设备」，用手机扫描屏幕上的二维码。", listOf(
            TourPoint(PocketIcons.QrCodeScanner, "二维码 5 分钟内有效，只在扫码时申请相机权限"),
            TourPoint(PocketIcons.Link, "扫不了码时，可以粘贴配对内容手动连接"),
            TourPoint(PocketIcons.Smartphone, "给这台手机起个名字，方便在电脑端认出它"))),
    TourChapter(TourScene.Locations, "执行位置", "Codex 在哪里运行，由你选",
        "项目、对话和用量都跟随当前的执行位置。在首页顶部或「设置 › 电脑连接」里切换。", listOf(
            TourPoint(PocketIcons.Computer, "电脑：扫码配对的那台电脑"),
            TourPoint(PocketIcons.Terminal, "Linux · SSH：填好主机和密钥，Pocket 自动装好运行环境"),
            TourPoint(PocketIcons.Smartphone, "此手机：安装包带本机 Codex 时，直接在手机上运行"))),
    TourChapter(TourScene.Home, "首页", "所有会话，一眼看全",
        "底部三个页签：会话、待处理、管理。点一行，会话从那一行展开；返回时收回原处。", listOf(
            TourPoint(PocketIcons.Edit, "右上角新建会话，选好项目和模型就能开聊"),
            TourPoint(PocketIcons.Search, "搜索会话标题，会话按项目归组"),
            TourPoint(PocketIcons.Tune, "底栏和分段控件都可以按住拖动切换"))),
    TourChapter(TourScene.Send, "对话", "像发消息一样指挥 Codex",
        "在输入框写下要做的事并发送。任务运行中也能继续输入：插话立即生效，或排队等这一轮结束。", listOf(
            TourPoint(PocketIcons.Add, "「+」添加图片、文件和引用；输入 @ 选文件，/ 用命令"),
            TourPoint(PocketIcons.Tune, "输入框上方切换模型、思考强度和权限"),
            TourPoint(PocketIcons.StopCircle, "运行中发送键变成停止，随时可以叫停"))),
    TourChapter(TourScene.Process, "过程", "它做了什么，都看得见",
        "命令、读写文件、搜索和思考按顺序列出。正在运行的那一步图标会动，做完后收成一行。", listOf(
            TourPoint(PocketIcons.ChevronRight, "点任意一步展开输出，再点进入完整详情"),
            TourPoint(PocketIcons.Description, "每一轮的文件改动汇总成卡片，可逐个查看差异"),
            TourPoint(PocketIcons.ArrowDownward, "往上翻历史时，点输入框上方的箭头回到最新"))),
    TourChapter(TourScene.Approve, "确认", "需要你点头的事，不会错过",
        "Codex 要执行命令、修改文件或向你提问时，会停下来等你。请求出现在会话里，也汇总在「待处理」页签。", listOf(
            TourPoint(PocketIcons.ShieldCheck, "允许这一次、本次会话都允许，或者拒绝"),
            TourPoint(PocketIcons.Checklist, "计划模式下先看计划，确认后才动手"),
            TourPoint(PocketIcons.NotificationsNone, "开启后台通知，离开应用也能收到提醒"))),
    TourChapter(TourScene.Edit, "修改", "发出去的消息也能改",
        "点消息下方的铅笔，气泡原地变成输入框。改好后重新发送，这一轮及之后的对话会撤回重来。", listOf(
            TourPoint(PocketIcons.Edit, "就在气泡里编辑，不会弹出别的窗口"),
            TourPoint(PocketIcons.Info, "撤回的是对话，已经写入的文件修改不会被撤销"),
            TourPoint(PocketIcons.ContentCopy, "每条消息和回复下方都有复制按钮"))),
    TourChapter(TourScene.Assistant, "口袋助理", "把琐事交给助理",
        "首页右下角的圆形按钮是助理的入口。告诉它目标，它会拆成任务，在原生 Codex 会话里执行，再把成果带回来。", listOf(
            TourPoint(PocketIcons.Checklist, "任务进度和成果都在助理页，点卡片看详情"),
            TourPoint(PocketIcons.PendingActions, "计划任务可以定时或重复执行"),
            TourPoint(PocketIcons.BookOpen, "记忆让它记住你的偏好，随时可以查看和删除"))),
    TourChapter(TourScene.Usage, "用量与设置", "心里有数，随手可调",
        "「管理」页签里看用量和已配对的设备，「设置」里调整外观和通知。", listOf(
            TourPoint(PocketIcons.Tune, "按时间范围和模型查看用量与额度"),
            TourPoint(PocketIcons.Moon, "浅色、深色或跟随系统，液态玻璃可以关掉"),
            TourPoint(PocketIcons.Refresh, "连接不顺时，用「连接诊断」和「重新连接」"))),
)

/** Opens the tutorial, and remembers that it has been seen. Null where there is nothing to open it with. */
@Stable
internal class PocketTutorialController(private val context: Context?, first: Boolean) {
    var open by mutableStateOf(first)
        private set
    /** True on the visit that follows installing, when it opens by itself. */
    val firstRun = first
    fun show() { open = true }
    fun finish() {
        open = false
        context?.getSharedPreferences(Store, Context.MODE_PRIVATE)?.edit()?.putInt(Seen, Version)?.apply()
    }
    internal companion object {
        const val Store = "pocket-tutorial"
        const val Seen = "seen"
        /** Raise this when the tutorial has changed enough to be worth showing to everyone once more. */
        const val Version = 1
    }
}

internal val LocalPocketTutorial = staticCompositionLocalOf<PocketTutorialController?> { null }

/** Tests drive the app from its first real screen; nothing opens over it by itself there. */
private fun drivenByTests(): Boolean = runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess

/** The tutorial for this install: open on the first visit, closed and remembered afterwards. */
@Composable
internal fun rememberPocketTutorial(): PocketTutorialController {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val seen = context.getSharedPreferences(PocketTutorialController.Store, Context.MODE_PRIVATE).getInt(PocketTutorialController.Seen, 0)
        PocketTutorialController(context, seen < PocketTutorialController.Version && !drivenByTests())
    }
}

/**
 * Shows [content] and, over it while [controller] says so, the tutorial. The tutorial rises into place
 * and sinks away; underneath, the app stays as it was but is out of reach of touch and of a screen reader.
 */
@Composable
internal fun PocketTutorialHost(controller: PocketTutorialController, content: @Composable () -> Unit) {
    val motion = rememberPocketMotion()
    val shown = remember { Animatable(if (controller.open) 1f else 0f) }
    LaunchedEffect(controller.open, motion) {
        val target = if (controller.open) 1f else 0f
        if (motion) shown.animateTo(target, PocketMotion.page()) else shown.snapTo(target)
    }
    val visible by remember { derivedStateOf { controller.open || shown.value > 0f } }
    // Both boxes hand their own size down, so the app is measured exactly as it was without them.
    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        Box(if (controller.open) Modifier.clearAndSetSemantics { } else Modifier, propagateMinConstraints = true) { content() }
        if (visible) PocketTutorial(controller::finish, Modifier.graphicsLayer {
            val t = shown.value
            alpha = t; translationY = (1f - t) * 28.dp.toPx()
            val size = .97f + .03f * t; scaleX = size; scaleY = size
        })
    }
}

/**
 * The first-run tutorial: one chapter to a page, each with a moving picture of the thing it explains.
 * Pages follow a finger, the dots travel with them, and the buttons do the same for anyone who would
 * rather tap. Back steps to the previous chapter and leaves from the first.
 */
@Composable
internal fun PocketTutorial(onDone: () -> Unit, modifier: Modifier = Modifier, chapters: List<TourChapter> = PocketTourChapters) {
    val c = Pocket.colors
    val scope = rememberCoroutineScope()
    val pager = rememberPPager(chapters.size)
    val last = pager.page == chapters.lastIndex
    val backShown = rememberGlyphToggle(pager.page > 0, PocketMotion.state())
    val skipShown = rememberGlyphToggle(!last, PocketMotion.state())
    BackHandler { if (pager.page > 0) pager.open(pager.page - 1, scope) else onDone() }
    Column(modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.safeDrawing).testTag("tutorial")) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PBrandMark(Modifier.size(30.dp))
            PText("使用教程", Modifier.padding(start = 10.dp).weight(1f), style = Pocket.type.titleSmall)
            PTextAction("跳过", onDone, Modifier.graphicsLayer { alpha = skipShown.value }.testTag("tutorial-skip"), enabled = !last)
        }
        PPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("tutorial-pager")) { index, offset ->
            TourPage(chapters[index], index, chapters.size, pager.page == index, offset)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PPageDots(pager)
            Row(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Always in the row, so the main button never changes width; it only shows from the second chapter on.
                Box(Modifier.graphicsLayer { val t = backShown.value; alpha = t; val size = .7f + .3f * t; scaleX = size; scaleY = size }
                    .clip(CircleShape).background(c.fill)) {
                    PIconButton(PocketIcons.ArrowBack, "上一步", { pager.open(pager.page - 1, scope) }, Modifier.size(52.dp).testTag("tutorial-back"), enabled = pager.page > 0)
                }
                PButton(if (last) "开始使用" else "下一步", { if (last) onDone() else pager.open(pager.page + 1, scope) },
                    Modifier.weight(1f).testTag("tutorial-next"), haptic = if (last) PocketHaptic.Confirm else PocketHaptic.Tap)
            }
        }
    }
}

@Composable
private fun TourPage(chapter: TourChapter, index: Int, count: Int, active: Boolean, offset: () -> Float) {
    val c = Pocket.colors
    val entrance = rememberPocketEntrance(windowMillis = 520)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 8.dp)) {
        // The picture runs a little ahead of the page and the words a little behind it, which gives the turn its depth.
        Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp).fillMaxWidth().graphicsLayer {
            val away = kotlin.math.abs(offset())
            translationX = offset() * size.width * .16f
            val scale = 1f - .07f * away; scaleX = scale; scaleY = scale
            alpha = (1f - .6f * away).coerceIn(0f, 1f)
        }.clip(PocketRadii.sheet).background(c.sunken).testTag("tutorial-scene:${chapter.scene.name}")) {
            TourStage(chapter.scene, active, Modifier.padding(6.dp))
        }
        Column(Modifier.padding(top = 26.dp).graphicsLayer {
            translationX = -offset() * size.width * .1f
            alpha = (1f - 1.5f * kotlin.math.abs(offset())).coerceIn(0f, 1f)
        }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PText("${index + 1} / $count  ·  ${chapter.kicker}", Modifier.pocketEntrance(entrance, 0), style = Pocket.type.labelSmall, color = c.accent)
            PText(chapter.title, Modifier.pocketEntrance(entrance, 1).semantics { heading() }.testTag("tutorial-title"), style = Pocket.type.headline)
            PText(chapter.body, Modifier.pocketEntrance(entrance, 2), style = Pocket.type.body, color = c.inkMuted)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                chapter.points.forEachIndexed { row, point ->
                    Row(Modifier.pocketEntrance(entrance, 3 + row), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).background(c.fill, CircleShape), contentAlignment = Alignment.Center) {
                            // Each point's icon plays once as its chapter comes to rest.
                            PTapIcon(point.icon, active, Modifier.size(18.dp), tint = c.ink)
                        }
                        PText(point.text, Modifier.weight(1f), style = Pocket.type.bodySmall.copy(fontSize = Pocket.type.titleSmall.fontSize), color = c.ink)
                    }
                }
            }
        }
    }
}
