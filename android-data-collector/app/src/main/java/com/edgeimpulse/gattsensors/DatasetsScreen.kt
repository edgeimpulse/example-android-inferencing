package com.edgeimpulse.gattsensors

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device dataset manager. Lets you preview, rename, delete, share, or
 * upload (individually) the CSV files produced by Offline Logging mode.
 * Backing storage is `getExternalFilesDir(null)/sensor_logs/`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetsScreen(viewModel: SensorViewModel) {
    val datasets by viewModel.datasets.collectAsState()
    var statusMsg by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StoredDataset?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshDatasets() }

    // Spreadsheet editor takes over the screen when a dataset is opened for
    // editing. Returning from it refreshes the list.
    editing?.let { ds ->
        DatasetEditorScreen(
            viewModel = viewModel,
            dataset   = ds,
            onClose   = { msg ->
                if (msg != null) statusMsg = msg
                editing = null
                viewModel.refreshDatasets()
            },
        )
        return
    }

    var renameTarget  by remember { mutableStateOf<StoredDataset?>(null) }
    var deleteTarget  by remember { mutableStateOf<StoredDataset?>(null) }
    var uploadTarget  by remember { mutableStateOf<StoredDataset?>(null) }
    var previewTarget by remember { mutableStateOf<StoredDataset?>(null) }

    val context = LocalContext.current
    val doShare: (StoredDataset) -> Unit = remember(context) {
        { ds ->
            val authority = context.packageName + ".fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, ds.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, ds.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${ds.name}"))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("On-device datasets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("Browse, edit and upload locally-recorded CSV samples.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = { viewModel.refreshDatasets() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        if (datasets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No local CSVs yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("Enable \"Offline logging\" on the Collect tab to record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(datasets, key = { it.file.absolutePath }) { ds ->
                    DatasetCard(
                        ds        = ds,
                        onPreview = { previewTarget = ds },
                        onEdit    = { editing       = ds },
                        onRename  = { renameTarget  = ds },
                        onDelete  = { deleteTarget  = ds },
                        onShare   = { doShare(ds) },
                        onUpload  = { uploadTarget  = ds },
                    )
                }
            }
        }
    }

    // ── Rename ─────────────────────────────────────────────────────────
    renameTarget?.let { ds ->
        var newName by remember(ds) { mutableStateOf(ds.name.removeSuffix(".csv")) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename dataset") },
            text = {
                Column {
                    Text("Allowed: letters, digits, . _ - ; other chars become '_'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("New name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = ds
                    viewModel.renameDataset(target.file, newName) { ok ->
                        statusMsg = if (ok) "Renamed \"${target.name}\""
                                    else "Rename failed (already exists or in use)"
                    }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    // ── Delete ─────────────────────────────────────────────────────────
    deleteTarget?.let { ds ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete dataset?") },
            text  = { Text("\"${ds.name}\" will be permanently removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDataset(ds.file)
                    statusMsg = "Deleted \"${ds.name}\""
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    // ── Upload ─────────────────────────────────────────────────────────
    uploadTarget?.let { ds ->
        var label by remember(ds) { mutableStateOf("idle") }
        var deleteAfter by remember(ds) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { uploadTarget = null },
            title = { Text("Upload to Edge Impulse") },
            text = {
                Column {
                    Text("Sends \"${ds.name}\" to /api/training/files with the chosen label.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        singleLine = true,
                        label = { Text("Label") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)) {
                        Checkbox(checked = deleteAfter,
                            onCheckedChange = { deleteAfter = it })
                        Text("Delete from device on success",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = ds
                    val effLabel = label.trim().ifEmpty { "idle" }
                    statusMsg = "Uploading \"${target.name}\"\u2026"
                    viewModel.uploadDataset(target.file, effLabel, deleteAfter) { result ->
                        statusMsg = result.fold(
                            onSuccess = { "Uploaded \"${target.name}\" as \"$effLabel\"" },
                            onFailure = { "Upload failed: ${it.message}" },
                        )
                    }
                    uploadTarget = null
                }) { Text("Upload") }
            },
            dismissButton = { TextButton(onClick = { uploadTarget = null }) { Text("Cancel") } },
        )
    }

    // ── Preview ────────────────────────────────────────────────────────
    previewTarget?.let { ds ->
        var preview by remember(ds) { mutableStateOf<DatasetPreview?>(null) }
        LaunchedEffect(ds) { preview = viewModel.previewDataset(ds.file, maxRows = 50) }
        AlertDialog(
            onDismissRequest = { previewTarget = null },
            title = { Text(ds.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                val p = preview
                if (p == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Column {
                        Text("${ds.sampleCount} samples \u2022 ${formatBytes(ds.sizeBytes)} " +
                             "\u2022 first ${p.rows.size} rows",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier
                                .heightIn(max = 360.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(p.headers.joinToString(","),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            p.rows.forEach { row ->
                                Text(row, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewTarget = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun DatasetCard(
    ds: StoredDataset,
    onPreview: () -> Unit,
    onEdit:    () -> Unit,
    onRename:  () -> Unit,
    onDelete:  () -> Unit,
    onShare:   () -> Unit,
    onUpload:  () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(ds.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${ds.sampleCount} samples \u2022 ${formatBytes(ds.sizeBytes)} \u2022 ${formatDate(ds.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (ds.headers.isNotEmpty()) {
                Text(
                    "Columns: ${ds.headers.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onPreview) {
                    Icon(Icons.Default.Visibility, contentDescription = "Preview")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.GridOn, contentDescription = "Edit as spreadsheet",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
                IconButton(onClick = onUpload) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Upload",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / (1024.0 * 1024.0))
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
