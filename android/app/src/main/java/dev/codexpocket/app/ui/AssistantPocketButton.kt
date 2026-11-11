package dev.codexpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AssistantPocketButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, attention: Boolean = false) {
    val c = MaterialTheme.colorScheme
    PocketGlassSurface(modifier.requiredSize(60.dp).testTag("assistant-pocket-button").semantics {
        contentDescription = if(attention) "打开口袋助理，有待确认事项" else "打开口袋助理"
        if(!enabled) disabled()
    },shape=RoundedCornerShape(20.dp),onClick=if(enabled) onClick else null) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val scale = size.minDimension / 48f
                val ink = c.onSurface.copy(alpha=if(enabled) .9f else .35f)
                withTransform({scale(scale,scale,Offset.Zero)}) {
                    // An open U silhouette and a separate dipped lip; no closed top edge.
                    val pocket = Path().apply {
                        moveTo(8f,20f);lineTo(10.5f,34f);cubicTo(11.5f,40f,17f,43f,23f,43f)
                        cubicTo(30f,43f,36f,39f,37.5f,33f);lineTo(40f,20f)
                    }
                    drawPath(pocket,c.secondary.copy(alpha=if(enabled) .10f else .04f))
                    drawPath(pocket,ink,style=Stroke(2f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                    val lip = Path().apply {moveTo(8f,20f);cubicTo(13f,25f,20f,27f,25f,26f);cubicTo(28f,26f,31f,24.5f,33f,23f)}
                    drawPath(lip,ink,style=Stroke(1.6f,cap=StrokeCap.Round))
                    val seam = Path().apply {moveTo(14f,32f);cubicTo(15f,36f,19f,38f,23f,38f);cubicTo(28f,38f,31f,36f,33f,32f)}
                    drawPath(seam,ink.copy(alpha=.3f),style=Stroke(1f,cap=StrokeCap.Round))
                    val star = Path().apply {
                        moveTo(34f,3f);cubicTo(35.5f,10f,37f,12f,44f,13.5f)
                        cubicTo(37f,15f,35.5f,17f,34f,24f);cubicTo(32.5f,17f,31f,15f,24f,13.5f)
                        cubicTo(31f,12f,32.5f,10f,34f,3f);close()
                    }
                    drawPath(star,c.secondary.copy(alpha=if(enabled) 1f else .35f))
                    if(attention) drawCircle(c.secondary,2.1f,Offset(11f,9f))
                }
            }
        }
    }
}
