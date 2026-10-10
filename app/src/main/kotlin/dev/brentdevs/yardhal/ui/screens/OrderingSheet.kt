package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.OrderKind
import dev.brentdevs.yardhal.core.data.OrderMove
import kotlin.math.roundToInt

internal data class OrderingItem(val key: String, val label: String)

internal data class OrderingSection(val id: String, val label: String, val kind: OrderKind, val items: List<OrderingItem>)

internal fun orderingSections(
    entries: List<OverviewEntry>,
    order: dev.brentdevs.yardhal.core.data.ChannelOrderState,
): List<OrderingSection> {
    val result = mutableListOf(
        OrderingSection("networks", "Networks", OrderKind.NETWORK, entries.filterIsInstance<OverviewEntry.NetworkHeader>().map {
            OrderingItem(it.network.id, it.network.name)
        }),
        OrderingSection("groups", "Groups", OrderKind.GROUP, (order.groupOrder + order.groups.map { it.id }).distinct().mapNotNull { id ->
            order.groups.firstOrNull { it.id == id }?.let { OrderingItem(it.id, it.name) }
        }),
    )
    var networkName = ""
    var networkId = ""
    var section: OverviewEntry.SectionHeader? = null
    var items = mutableListOf<OrderingItem>()
    fun flush() {
        val current = section ?: return
        if (items.isNotEmpty()) result.add(OrderingSection("$networkId|${current.id}", "$networkName · ${current.label}", OrderKind.CHANNEL, items))
        items = mutableListOf()
    }
    for (entry in entries) {
        when (entry) {
            is OverviewEntry.NetworkHeader -> {
                flush()
                section = null
                networkName = entry.network.name
                networkId = entry.network.id
            }
            is OverviewEntry.SectionHeader -> {
                flush()
                section = entry
            }
            is OverviewEntry.BufferRow -> items.add(OrderingItem(entry.buffer.key, entry.buffer.displayName))
            is OverviewEntry.ServerRow -> Unit
        }
    }
    flush()
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderingSheet(
    sections: List<OrderingSection>,
    sortUnreadFirst: Boolean,
    onReorder: (OrderMove) -> Boolean,
    onSetUnreadSorting: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragDestination by remember { mutableStateOf(0) }
    val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
    fun move(section: OrderingSection, key: String, destination: Int): Boolean {
        val saved = onReorder(OrderMove(section.kind, key, section.items.map { it.key }, destination))
        error = if (saved) null else "Order could not be saved. Previous order was retained. Check device storage, then try again."
        return saved
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Reorder networks, groups and conversations", style = MaterialTheme.typography.titleLarge)
            Text("Long-press a drag handle, or use each item's move controls. Channels stay in their pinned or group section.", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unread first (alternate sorting)", modifier = Modifier.weight(1f))
                Switch(
                    checked = sortUnreadFirst,
                    onCheckedChange = { enabled ->
                        error = if (onSetUnreadSorting(enabled)) null else "Sorting could not be saved. Check device storage, then try again."
                    },
                    modifier = Modifier.semantics { stateDescription = if (sortUnreadFirst) "Unread first" else "Manual order" },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn {
                for (section in sections) {
                    if (section.items.isEmpty()) continue
                    item(key = "heading-${section.id}") {
                        Text(section.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(section.items.size, key = { "${section.id}|${section.items[it].key}" }) { index ->
                        val item = section.items[index]
                        var menuVisible by remember { mutableStateOf(false) }
                        val destinations = listOf("Move up" to index - 1, "Move down" to index + 1, "Move to top" to 0, "Move to bottom" to section.items.lastIndex)
                        Row(
                            modifier = Modifier.fillMaxWidth().height(72.dp).semantics {
                                stateDescription = if (draggingKey == item.key) "Drop at position ${dragDestination + 1}" else "Position ${index + 1} of ${section.items.size}"
                                customActions = destinations.filter { (_, destination) -> destination in section.items.indices && destination != index }.map { (label, destination) ->
                                    CustomAccessibilityAction("$label: ${item.label}") { move(section, item.key, destination) }
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "Drag to reorder ${item.label}",
                                modifier = Modifier.padding(12.dp).pointerInput(section, item.key) {
                                    var distance = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { distance = 0f; draggingKey = item.key; dragDestination = index },
                                        onDragCancel = { draggingKey = null },
                                        onDragEnd = {
                                            if (dragDestination != index) move(section, item.key, dragDestination)
                                            draggingKey = null
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        distance += amount.y
                                        dragDestination = (index + (distance / rowHeight).roundToInt()).coerceIn(section.items.indices)
                                    }
                                },
                            )
                            Text(
                                if (draggingKey == item.key) "${item.label} → position ${dragDestination + 1}" else item.label,
                                modifier = Modifier.weight(1f),
                            )
                            Column {
                                IconButton(onClick = { menuVisible = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Move ${item.label}")
                                }
                                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                                    for ((label, destination) in destinations) {
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            enabled = destination in section.items.indices && destination != index,
                                            onClick = { move(section, item.key, destination); menuVisible = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
