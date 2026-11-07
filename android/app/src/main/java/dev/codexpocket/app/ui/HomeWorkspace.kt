package dev.codexpocket.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R

/** A stable horizontal project selector; every conversation remains in the vertical list. */
@Composable
internal fun HomeProjectChips(projects: List<HomeProject>, selected: HomeProject?, onSelect: (HomeProject?) -> Unit, onProjects: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).selectableGroup().testTag("home-project-chips"),
            contentPadding = PaddingValues(start = 20.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "all") { HomeProjectChip(stringResource(R.string.all_projects), selected == null, { onSelect(null) },
                Modifier.testTag("home-project-all"), stringResource(R.string.all_projects)) }
            items(projects, key = { it.id }) { project ->
                HomeProjectChip(project.title, selected == project, { onSelect(project) }, Modifier.testTag("home-project-${project.id}"),
                    listOf(project.title, project.cwd, project.computerId).filter(String::isNotBlank).joinToString("，"))
            }
        }
        HomeIconAction(PocketIcons.FolderOpen, stringResource(R.string.v03_home_choose_project), onProjects, Modifier.testTag("home-project-filter"))
    }
}

@Composable
private fun HomeProjectChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, description: String) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background by animateColorAsState(when {
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        pressed || focused -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.background.copy(alpha = 0f)
    }, tween(pocketMotionMillis(180)), label = "home-project-selection")
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(pocketMotionMillis(120)), label = "home-project-press")
    // 44dp touch target around a 34dp pill, so the chip row reads as a light filter, not a second header.
    Box(modifier.heightIn(min = 44.dp).widthIn(min = 56.dp, max = 168.dp)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(title, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketOval).background(background).padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HomeLibraryHeader(archived: Boolean, onArchived: (Boolean) -> Unit, count: Int, searching: Boolean = false, onSearch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeScopeTab(stringResource(R.string.v02_recent), !archived, { onArchived(false) }, Modifier.testTag("home-filter-all"))
            HomeScopeTab(stringResource(R.string.archived), archived, { onArchived(true) }, Modifier.testTag("home-filter-archive"))
        }
        Text(stringResource(R.string.v05_home_count, count), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        if (onSearch != null) HomeIconAction(if (searching) PocketIcons.Close else PocketIcons.Search,
            stringResource(if (searching) R.string.v03_home_hide_search else R.string.v03_home_search), onSearch)
    }
}
