package dev.codexpocket.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.PIcon
import dev.codexpocket.app.ui.kit.PLoopGlyph
import dev.codexpocket.app.ui.kit.ToolGlyphs
import dev.codexpocket.app.ui.kit.Pocket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class ActivityMotion { Terminal, Document, Connection, Tool }

/** The clock is read only while drawing; no transcript measurement runs on an animation frame. */
@Composable
internal fun ActivityMotionIcon(kind: ActivityMotion, running: Boolean, modifier: Modifier = Modifier.size(16.dp),
    tint: Color = Pocket.colors.inkMuted, fallback: ImageVector = PocketIcons.Wrench) {
    val preferences=rememberPocketGlassPreferences()
    val motion=preferences.animatorsEnabled&&!preferences.batterySaver&&!preferences.highContrast
    val last=remember(kind) {floatArrayOf(1f)}
    val phase: State<Float> = if (running&&motion) {
        rememberInfiniteTransition(label="activity-$kind").animateFloat(0f,1f,
            infiniteRepeatable(tween(if(kind==ActivityMotion.Connection) 2200 else 2000,easing=LinearEasing)),label="glyph-path")
    } else if(motion) {
        val settle=remember(running,kind) {Animatable(last[0])}
        LaunchedEffect(settle) {settle.animateTo(1f,tween(180,easing=FastOutSlowInEasing))}
        settle.asState()
    } else rememberUpdatedState(1f)
    if(kind==ActivityMotion.Tool) {
        PIcon(fallback,null,modifier.graphicsLayer {
            last[0]=phase.value
            rotationZ=sin(phase.value*2f*PI).toFloat()*9f
        },tint=tint)
        return
    }
    Canvas(modifier) {
        val u=size.minDimension/24f
        val p=phase.value
        last[0]=p
        val stroke=1.55f*u
        val pen=Stroke(stroke,cap=StrokeCap.Round)
        fun step(start:Float,end:Float):Float {
            val value=((p-start)/(end-start)).coerceIn(0f,1f)
            return value*value*(3f-2f*value)
        }
        fun line(x1:Float,y1:Float,x2:Float,y2:Float,alpha:Float=1f) =
            drawLine(tint.copy(alpha=tint.alpha*alpha),Offset(x1*u,y1*u),Offset(x2*u,y2*u),strokeWidth=stroke,cap=StrokeCap.Round)
        when(kind) {
            ActivityMotion.Terminal -> {
                drawRoundRect(tint,Offset(2.5f*u,3.5f*u),Size(19f*u,17f*u),CornerRadius(3f*u),style=pen)
                val advance=step(0f,.22f)-step(.72f,1f)
                val scroll=step(.48f,.8f)*(1f-step(.85f,1f))
                val x=6.5f+advance*2.2f
                line(x,8f,x+3f,11f);line(x+3f,11f,x,14f)
                val write=step(.18f,.48f)*(1f-step(.78f,1f))
                line(12.5f-write,15f-scroll*5f,17.5f,15f-scroll*5f)
                if(scroll>.01f) line(12.5f,15f,12.5f+5f*scroll,15f)
            }
            ActivityMotion.Document -> {
                drawRoundRect(tint,Offset(4f*u,2.5f*u),Size(16f*u,19f*u),CornerRadius(3f*u),style=pen)
                clipRect(7f*u,6.5f*u,18f*u,18f*u) {
                    val travel=step(.12f,.9f)*12f
                    for(row in 0..5) {
                        val y=8f+row*4f-travel
                        val width=if(row%3==2) 6f else 8f
                        val reveal=((18f-y)/3f).coerceIn(0f,1f)
                        if(reveal>0f) line(8f,y,8f+width*reveal,y)
                    }
                }
            }
            ActivityMotion.Connection -> {
                val meet=step(.18f,.48f)-step(.7f,1f)
                val start=p*360f-40f
                val inset=Offset((4f+meet)*u,(4f+meet)*u);val diameter=Size((16f-2f*meet)*u,(16f-2f*meet)*u)
                drawArc(tint,start,100f+meet*70f,false,inset,diameter,style=pen)
                drawArc(tint.copy(alpha=tint.alpha*.75f),start+180f,100f+meet*70f,false,inset,diameter,style=pen)
                val gap=(3f-2f*meet)*u
                drawCircle(tint,1.15f*u,center+Offset(-gap,0f))
                drawCircle(tint,1.15f*u,center+Offset(gap,0f))
            }
            ActivityMotion.Tool -> Unit
        }
    }
}

@Composable
internal fun NativeActivityIcon(icon: ImageVector, running: Boolean, tint: Color) {
    // The terminal keeps its own typing-and-scrolling loop. Every other tool moves the way its icon is drawn:
    // the book turns a leaf, the pencil writes, the glass searches, the globe spins, and each stops on the icon itself.
    if(icon==PocketIcons.Terminal) { ActivityMotionIcon(ActivityMotion.Terminal,running,tint=tint,fallback=icon); return }
    val glyph=ToolGlyphs.working(icon)
    if(glyph==null) ActivityMotionIcon(ActivityMotion.Tool,running,tint=tint,fallback=icon)
    else PLoopGlyph(glyph,running,Modifier.size(16.dp),tint,millis=ToolGlyphs.period(icon))
}
