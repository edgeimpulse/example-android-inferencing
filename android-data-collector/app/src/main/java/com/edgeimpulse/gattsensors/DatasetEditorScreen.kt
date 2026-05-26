package com.edgeimpulse.gattsensors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Spreadsheet-style CSV editor — load the whole file into a scrollable grid,
 * edit individual cells, filter rows by column-contains, multi-select rows,
 * bulk-relabel a column for the selection, snip the selection out into a
 * brand new dataset, or save the edits back over the original file.
 *
 * Designed for the offline-logged CSVs the app produces (tens of MB at most),
 * not for arbitrarily large data; everything is kept in memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetEditorScreen(
    viewModel: SensorViewModel,
    dataset: StoredDataset,
    onClose: (statusMessage: String?) -> Unit,
) {
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }
    // SnapshotStateList of mutable rows so cell edits trigger recomposition
    // without rewriting the entire list. Each row is itself mutable.
    val rows = remember { mutableStateListOf<SnapshotStringRow>() }
    var loading by remember { mutableStateOf(true) }
    val selection = remember { mutableStateMapOf<Int, Boolean>() }
    var dirty by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // Filter state — a single column-contains filter is enough for triage.
    var filterColumn by remember { mutableStateOf<Int?>(null) }
    var filterText by remember { mutableStateOf("") }

    var editingCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showSaveAs  by remember { mutableStateOf(false) }
    var showRelabel by remember { mutableStateOf(false) }
    var showSnip    by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(dataset.file.absolutePath) {
        loading = true
        val full = viewModel.loadDatasetFull(dataset.file)
        headers = full.headers
        rows.clear()
        full.rows.forEach { rows.add(SnapshotStringRow(it)) }
        selection.clear()
        dirty = false
        // Default the filter column to the timestamp column (or column 0 as a
        // fallback) so the chip never reads "off" — picking "off" with a
        // stale filterText leaves the grid looking empty to the user. With a
        // real column selected and the text box empty, all rows show.
        filterColumn = full.headers.indexOfFirst { it.equals("timestamp", ignoreCase = true) }
            .let { if (it >= 0) it else if (full.headers.isNotEmpty()) 0 else null }
        loading = false
    }

    // Materialised filtered view -> (originalIndex, row). We keep the
    // original index so selections remain stable across filter changes.
    val visibleRows: List<Pair<Int, SnapshotStringRow>> = remember(rows, filterColumn, filterText) {
        if (filterText.isBlank() || filterColumn == null) {
            rows.mapIndexed { i, r -> i to r }
        } else {
            val col = filterColumn!!
            val needle = filterText.trim().lowercase()
            rows.mapIndexedNotNull { i, r ->
                val cell = r.values.getOrNull(col)?.lowercase() ?: return@mapIndexedNotNull null
                if (cell.contains(needle)) i to r else null
            }
        }
    }

    val selectedCount = selection.count { it.value }

    Column(Modifier.fillMaxSize()) {
        // ── Top bar ─────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Column {
                    Text(dataset.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${rows.size} rows \u2022 ${headers.size} cols" +
                            (if (selectedCount > 0) " \u2022 $selectedCount selected" else "") +
                            (if (dirty) " \u2022 unsaved" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (dirty) showDiscard = true else onClose(null)
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close editor")
                }
            },
            actions = {
                IconButton(onClick = {
                    viewModel.saveDataset(
                        dataset.file,
                        headers,
                        rows.map { it.values.toList() },
                    ) { ok ->
                        statusMsg = if (ok) "Saved" else "Save failed"
                        if (ok) dirty = false
                    }
                }, enabled = dirty) {
                    Icon(Icons.Default.Save, contentDescription = "Save",
                        tint = if (dirty) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                IconButton(onClick = { showSaveAs = true }) {
                    Icon(Icons.Default.SaveAs, contentDescription = "Save as new")
                }
            }
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // ── Filter row ──────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    label   = {
                        Text(
                            "Filter: " + (filterColumn?.let { headers.getOrNull(it) ?: "col $it" }
                                ?: "off"),
                            maxLines = 1
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("(off)") },
                        onClick = { filterColumn = null; menuOpen = false }
                    )
                    headers.forEachIndexed { idx, h ->
                        DropdownMenuItem(
                            text = { Text(h.ifEmpty { "col $idx" }) },
                            onClick = { filterColumn = idx; menuOpen = false }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                singleLine = true,
                placeholder = { Text("contains…") },
                enabled = filterColumn != null,
                modifier = Modifier.weight(1f),
            )
            if (filterText.isNotEmpty()) {
                IconButton(onClick = { filterText = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear filter")
                }
            }
        }

        // ── Selection toolbar ───────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val allVisibleSelected = visibleRows.isNotEmpty() &&
                visibleRows.all { selection[it.first] == true }
            TextButton(onClick = {
                if (allVisibleSelected) {
                    visibleRows.forEach { selection[it.first] = false }
                } else {
                    visibleRows.forEach { selection[it.first] = true }
                }
            }) {
                Icon(if (allVisibleSelected) Icons.Default.CheckBox
                     else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (allVisibleSelected) "Deselect visible" else "Select visible")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showRelabel = true }, enabled = selectedCount > 0) {
                Icon(Icons.Default.Label, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Relabel")
            }
            TextButton(onClick = { showSnip = true }, enabled = selectedCount > 0) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Snip\u2192New")
            }
            IconButton(onClick = {
                // Delete selected rows from the in-memory grid.
                val toDelete = selection.filterValues { it }.keys.sortedDescending()
                if (toDelete.isNotEmpty()) {
                    toDelete.forEach { rows.removeAt(it) }
                    selection.clear()
                    dirty = true
                    statusMsg = "Removed ${toDelete.size} rows (unsaved)"
                }
            }, enabled = selectedCount > 0) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected",
                    tint = MaterialTheme.colorScheme.error)
            }
        }

        statusMsg?.let {
            Text(it,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        Divider()

        // ── Grid ────────────────────────────────────────────────────────
        val hScroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
            // Header row (sticky-ish — drawn above the LazyColumn but shares
            // horizontal scroll). Includes a left checkbox spacer.
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(CHECKBOX_COL_WIDTH).padding(horizontal = 4.dp))
                Text("#", modifier = Modifier.width(INDEX_COL_WIDTH).padding(horizontal = 4.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                headers.forEachIndexed { i, h ->
                    Text(
                        h.ifEmpty { "col $i" },
                        modifier = Modifier.width(CELL_WIDTH).padding(horizontal = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)
                .heightIn(min = 200.dp)) {
                itemsIndexed(
                    items = visibleRows,
                    key   = { _, pair -> "row-${pair.first}" },
                ) { _, (originalIdx, row) ->
                    val selected = selection[originalIdx] == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { selection[originalIdx] = it },
                            modifier = Modifier.width(CHECKBOX_COL_WIDTH),
                        )
                        Text(
                            "${originalIdx + 1}",
                            modifier = Modifier.width(INDEX_COL_WIDTH).padding(horizontal = 4.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        // Always render the same number of cells as headers so
                        // every row lines up with the column titles, even when
                        // the underlying row is short.
                        for (c in headers.indices) {
                            val cell = row.values.getOrNull(c) ?: ""
                            Box(
                                Modifier
                                    .width(CELL_WIDTH)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .border(1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(4.dp))
                                    .clickable { editingCell = originalIdx to c }
                                    .padding(6.dp),
                            ) {
                                Text(cell,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Cell edit dialog ───────────────────────────────────────────────
    editingCell?.let { (r, c) ->
        val row = rows.getOrNull(r) ?: run { editingCell = null; return@let }
        var draft by remember(r, c) {
            mutableStateOf(row.values.getOrNull(c) ?: "")
        }
        AlertDialog(
            onDismissRequest = { editingCell = null },
            title = { Text("Edit cell  row ${r + 1} \u2022 ${headers.getOrNull(c) ?: "col $c"}") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val padded = ensureLength(row.values, headers.size)
                    padded[c] = draft
                    row.values = padded
                    dirty = true
                    editingCell = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingCell = null }) { Text("Cancel") }
            },
        )
    }

    // ── Bulk relabel dialog ────────────────────────────────────────────
    if (showRelabel) {
        var column by remember {
            mutableStateOf(headers.indexOfFirst { it.equals("label", ignoreCase = true) }
                .let { if (it >= 0) it else headers.lastIndex.coerceAtLeast(0) })
        }
        var newValue by remember { mutableStateOf("") }
        var menuOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showRelabel = false },
            title = { Text("Relabel $selectedCount rows") },
            text = {
                Column {
                    Box {
                        AssistChip(
                            onClick = { menuOpen = true },
                            label = { Text("Column: " + (headers.getOrNull(column) ?: "col $column")) },
                            leadingIcon = { Icon(Icons.Default.ViewColumn, contentDescription = null) },
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            headers.forEachIndexed { idx, h ->
                                DropdownMenuItem(
                                    text = { Text(h.ifEmpty { "col $idx" }) },
                                    onClick = { column = idx; menuOpen = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        singleLine = true,
                        label = { Text("New value") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = selection.filterValues { it }.keys
                    targets.forEach { idx ->
                        val r = rows.getOrNull(idx) ?: return@forEach
                        val padded = ensureLength(r.values, headers.size)
                        padded[column] = newValue
                        r.values = padded
                    }
                    if (targets.isNotEmpty()) dirty = true
                    statusMsg = "Set \"${headers.getOrNull(column) ?: column}\"=\"$newValue\" " +
                        "on ${targets.size} rows (unsaved)"
                    showRelabel = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showRelabel = false }) { Text("Cancel") }
            },
        )
    }

    // ── Snip-to-new dialog ─────────────────────────────────────────────
    if (showSnip) {
        var baseName by remember {
            mutableStateOf(dataset.name.removeSuffix(".csv") + "_snippet")
        }
        var alsoDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSnip = false },
            title = { Text("Snip $selectedCount rows to new dataset") },
            text = {
                Column {
                    OutlinedTextField(
                        value = baseName,
                        onValueChange = { baseName = it },
                        singleLine = true,
                        label = { Text("New file name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)) {
                        Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                        Text("Also remove from this dataset",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = selection.filterValues { it }.keys.sorted()
                    val snippet = targets.mapNotNull { rows.getOrNull(it)?.values?.toList() }
                    scope.launch {
                        viewModel.saveDatasetAs(baseName, headers, snippet) { out ->
                            if (out != null && alsoDelete) {
                                targets.sortedDescending().forEach { rows.removeAt(it) }
                                selection.clear()
                                dirty = true
                            }
                            statusMsg = if (out != null) "Wrote ${out.name}" else "Snip failed"
                        }
                    }
                    showSnip = false
                }) { Text("Save snippet") }
            },
            dismissButton = {
                TextButton(onClick = { showSnip = false }) { Text("Cancel") }
            },
        )
    }

    // ── Save-as (whole edited grid) ────────────────────────────────────
    if (showSaveAs) {
        var baseName by remember {
            mutableStateOf(dataset.name.removeSuffix(".csv") + "_edited")
        }
        AlertDialog(
            onDismissRequest = { showSaveAs = false },
            title = { Text("Save as new dataset") },
            text = {
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    singleLine = true,
                    label = { Text("New file name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveDatasetAs(
                        baseName, headers, rows.map { it.values.toList() }
                    ) { out ->
                        statusMsg = if (out != null) "Saved as ${out.name}" else "Save failed"
                    }
                    showSaveAs = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAs = false }) { Text("Cancel") }
            },
        )
    }

    // ── Unsaved-changes confirmation ───────────────────────────────────
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text  = { Text("You have unsaved edits. Leaving now will lose them.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; onClose(null) }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Stay") }
            },
        )
    }
}

// Holder so a single cell edit doesn't force the entire row list to recompose.
private class SnapshotStringRow(initial: List<String>) {
    var values: MutableList<String> by mutableStateOf(initial.toMutableList())
}

/** Right-pad a row to [size] so column writes never index out of bounds. */
private fun ensureLength(src: List<String>, size: Int): MutableList<String> {
    val out = src.toMutableList()
    while (out.size < size) out.add("")
    return out
}

private val CHECKBOX_COL_WIDTH = 40.dp
private val INDEX_COL_WIDTH    = 48.dp
private val CELL_WIDTH         = 112.dp
