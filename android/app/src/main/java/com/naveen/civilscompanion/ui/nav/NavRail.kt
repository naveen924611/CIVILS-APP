package com.naveen.civilscompanion.ui.nav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.Fraunces

/** Builds a simple line icon from the SVG path data used in the design mockups. */
fun lineIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** Left navigation rail, 96 dp wide. */
@Composable
fun NavRail(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Cc.colors
    Box(modifier = modifier.fillMaxHeight().width(96.dp).background(colors.rail)) {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "CC",
                color = colors.primary,
                fontFamily = Fraunces,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Destination.entries.forEach { dest ->
                RailItem(dest, selected = dest == selected, onClick = { onSelect(dest) })
            }
        }
        // hairline on the right edge
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp).background(colors.border))
    }
}

@Composable
private fun RailItem(dest: Destination, selected: Boolean, onClick: () -> Unit) {
    val colors = Cc.colors
    val icon = remember(dest) { lineIcon(dest.label, dest.iconPath) }
    val fg = if (selected) colors.primary else colors.muted
    Column(
        modifier = Modifier
            .width(76.dp)
            .heightIn(min = 56.dp) // touch target
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryTint else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = dest.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(fg),
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = dest.label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Bottom navigation bar for the upright (narrow) layout. Same ten destinations as the rail; swipe sideways for more. */
@Composable
fun NavBottomBar(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Cc.colors
    Box(modifier = modifier.fillMaxWidth().background(colors.rail)) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.entries.forEach { dest ->
                RailItem(dest, selected = dest == selected, onClick = { onSelect(dest) })
            }
        }
    }
}
