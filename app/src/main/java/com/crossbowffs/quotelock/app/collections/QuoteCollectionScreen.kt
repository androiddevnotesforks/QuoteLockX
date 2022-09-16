@file:OptIn(ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class)

package com.crossbowffs.quotelock.app.collections

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.crossbowffs.quotelock.app.collections.QuoteCollectionUiEvent.ProgressMessage
import com.crossbowffs.quotelock.app.collections.QuoteCollectionUiEvent.SnackBarMessage
import com.crossbowffs.quotelock.data.api.*
import com.crossbowffs.quotelock.data.modules.collections.database.QuoteCollectionEntity
import com.crossbowffs.quotelock.ui.components.*
import com.crossbowffs.quotelock.ui.theme.QuoteLockTheme
import com.yubyf.quotelockx.R
import kotlinx.coroutines.launch

@Composable
fun QuoteCollectionRoute(
    modifier: Modifier = Modifier,
    viewModel: QuoteCollectionViewModel = hiltViewModel(),
    onItemClick: (QuoteDataWithCollectState) -> Unit,
    onBack: () -> Unit,
) {
    val listUiState by viewModel.uiListState
    val context = LocalContext.current
    val menuUiState by viewModel.uiMenuState
    val uiEvent by viewModel.uiEvent.collectAsState(initial = null)
    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            if (!result) {
                viewModel.onPermissionDenied()
            }
        }
    var pickedDbFileUri by remember { mutableStateOf<Uri?>(null) }
    val pickedDbFileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickedDbFileUri = uri
        }
    var pickedCsvFileUri by remember { mutableStateOf<Uri?>(null) }
    val pickedCsvFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> pickedCsvFileUri = uri }
    val googleSignInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.handleSignInResult(result.data)
        }
    QuoteCollectionScreen(
        modifier = modifier,
        listUiState = listUiState,
        menuUiState = menuUiState,
        uiEvent = uiEvent,
        onItemClick = { onItemClick(it.withCollectState(true)) },
        onBack = onBack,
        onExportDatabase = {
            if (ensurePermissions(context,
                    requestPermissionLauncher) { viewModel.onPermissionDenied() }
            ) {
                viewModel.export(LocalBackupType.DB)
            }
        },
        onExportCsv = {
            if (ensurePermissions(context,
                    requestPermissionLauncher) { viewModel.onPermissionDenied() }
            ) {
                viewModel.export(LocalBackupType.CSV)
            }
        },
        onImportDatabase = { pickedDbFileLauncher.launch(arrayOf("*/*")) },
        onImportCsv = { pickedCsvFileLauncher.launch(arrayOf("*/*")) },
        onSignIn = { googleSignInLauncher.launch(viewModel.getGoogleAccountSignInIntent()) },
        onSignOut = { viewModel.signOut() },
        onGdriveBackup = { viewModel.gDriveBackup() },
        onGdriveRestore = { viewModel.gDriveRestore() },
        onDeleteMenuClicked = { viewModel.delete(it) },
    )
    pickedDbFileUri?.let { uri ->
        viewModel.import(LocalBackupType.DB, uri)
        pickedDbFileUri = null
    }
    pickedCsvFileUri?.let { uri ->
        viewModel.import(LocalBackupType.CSV, uri)
        pickedCsvFileUri = null
    }
}

@Composable
fun QuoteCollectionScreen(
    modifier: Modifier = Modifier,
    listUiState: QuoteCollectionListUiState,
    menuUiState: QuoteCollectionMenuUiState,
    uiEvent: QuoteCollectionUiEvent?,
    onItemClick: (QuoteData) -> Unit,
    onBack: () -> Unit,
    onDeleteMenuClicked: (Long) -> Unit,
    onExportDatabase: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onImportDatabase: () -> Unit = {},
    onImportCsv: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onGdriveBackup: () -> Unit = {},
    onGdriveRestore: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CollectionAppBar(onBack = onBack) {
                CollectionDataRetentionMenu(
                    menuUiState.exportEnabled,
                    menuUiState.syncEnabled,
                    menuUiState.googleAccount,
                    menuUiState.syncTime,
                    onExportDatabase,
                    onExportCsv,
                    onImportDatabase,
                    onImportCsv,
                    onSignIn,
                    onSignOut,
                    onGdriveBackup,
                    onGdriveRestore,
                )
            }
        }
    ) { padding ->
        when (uiEvent) {
            is ProgressMessage -> {
                if (uiEvent.show) {
                    LoadingDialog(message = uiEvent.message) {}
                }
            }
            is SnackBarMessage -> {
                uiEvent.message?.let {
                    val messageText = it
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = messageText,
                            duration = uiEvent.duration,
                            actionLabel = uiEvent.actionText
                        )
                    }
                }
            }
            null -> {}
        }
        CollectionItemList(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .consumedWindowInsets(padding),
            entities = listUiState.items,
            onItemClick = onItemClick,
            onDeleteMenuClicked = onDeleteMenuClicked,
        )
    }
}

@Composable
fun CollectionDataRetentionMenu(
    enableExport: Boolean = false,
    enableSync: Boolean = false,
    account: GoogleAccount? = null,
    lastSyncTime: String? = null,
    onExportDatabase: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onImportDatabase: () -> Unit = {},
    onImportCsv: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onGdriveBackup: () -> Unit = {},
    onGdriveRestore: () -> Unit = {},
) {
    var backupMenuExpanded by remember { mutableStateOf(false) }
    var syncMenuExpanded by remember { mutableStateOf(false) }
    TopAppBarDropdownMenu(iconContent = {
        Icon(Icons.Rounded.Restore, contentDescription = "Backup&Restore")
    }, content = { _, closeMenu ->
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.ImportExport, contentDescription = null)
            },
            text = {
                Text(text = stringResource(id = R.string.import_export))
            },
            trailingIcon = {
                Icon(Icons.Rounded.NavigateNext, contentDescription = null)
            },
            onClick = {
                closeMenu()
                backupMenuExpanded = true
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.CloudSync, contentDescription = null)
            },
            text = { Text(text = stringResource(id = R.string.sync)) },
            enabled = enableSync,
            trailingIcon = {
                Icon(Icons.Rounded.NavigateNext, contentDescription = null)
            },
            onClick = {
                closeMenu()
                syncMenuExpanded = true
            }
        )
    }) { modifier, _ ->
        if (backupMenuExpanded) {
            CollectionBackupMenu(
                modifier,
                enableExport,
                onExportDatabase,
                onExportCsv,
                onImportDatabase,
                onImportCsv,
            ) { backupMenuExpanded = false }
        }
        if (syncMenuExpanded) {
            CollectionSyncMenu(
                modifier,
                account,
                lastSyncTime,
                onSignIn,
                onSignOut,
                onGdriveBackup,
                onGdriveRestore,
            ) { syncMenuExpanded = false }
        }
    }
}

@Composable
fun CollectionBackupMenu(
    modifier: Modifier = Modifier,
    enableExport: Boolean = false,
    onExportDatabase: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onImportDatabase: () -> Unit = {},
    onImportCsv: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        modifier = modifier,
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.FileUpload, contentDescription = null)
            },
            text = { Text(text = stringResource(id = R.string.export_database)) },
            enabled = enableExport,
            onClick = {
                onDismiss.invoke()
                onExportDatabase.invoke()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.FileUpload, contentDescription = null)
            },
            text = { Text(text = stringResource(id = R.string.export_csv)) },
            enabled = enableExport,
            onClick = {
                onDismiss.invoke()
                onExportCsv.invoke()
            }
        )
        Divider()
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.FileDownload, contentDescription = null)
            },
            text = { Text(text = stringResource(id = R.string.import_database)) },
            onClick = {
                onDismiss.invoke()
                onImportDatabase.invoke()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Rounded.FileDownload, contentDescription = null)
            },
            text = { Text(text = stringResource(id = R.string.import_csv)) },
            onClick = {
                onDismiss.invoke()
                onImportCsv.invoke()
            }
        )
    }
}

@Composable
fun CollectionSyncMenu(
    modifier: Modifier = Modifier,
    account: GoogleAccount? = null,
    lastSyncTime: String? = null,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onGdriveBackup: () -> Unit = {},
    onGdriveRestore: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        modifier = modifier,
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        if (account == null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                },
                text = { Text(text = stringResource(id = R.string.connect_account)) },
                onClick = {
                    onDismiss.invoke()
                    onSignIn.invoke()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text(text = account.email) },
                leadingIcon = {
                    AsyncImage(
                        model = account.avatar,
                        modifier = Modifier.size(24.dp),
                        contentDescription = null
                    )
                },
                onClick = {}
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.LinkOff, contentDescription = null)
                },
                text = { Text(text = stringResource(id = R.string.disconnect_account)) },
                onClick = {
                    onDismiss.invoke()
                    onSignOut.invoke()
                }
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                },
                text = { Text(text = stringResource(id = R.string.backup)) },
                onClick = {
                    onDismiss.invoke()
                    onGdriveBackup.invoke()
                }
            )
            if (!lastSyncTime.isNullOrBlank()) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    },
                    text = {
                        Column {
                            Text(text = stringResource(id = R.string.restore))
                            Text(text = lastSyncTime,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                modifier = Modifier.alpha(ContentAlpha.disabled)
                            )
                        }
                    },
                    onClick = {
                        onDismiss.invoke()
                        onGdriveRestore.invoke()
                    }
                )
            }
        }
    }
}

@Composable
private fun CollectionItemList(
    modifier: Modifier = Modifier,
    entities: List<QuoteEntity>,
    onItemClick: (QuoteData) -> Unit,
    onDeleteMenuClicked: (Long) -> Unit,
) {
    Surface {
        LazyColumn(
            modifier = modifier
        ) {
            val animationSpec: FiniteAnimationSpec<IntOffset> = tween(
                durationMillis = 300,
                easing = LinearOutSlowInEasing,
            )
            itemsIndexed(entities, key = { _, item -> item.id ?: -1 }) { index, entity ->
                DeletableQuoteListItem(
                    modifier = Modifier
                        .animateItemPlacement(animationSpec)
                        .fillMaxWidth(),
                    entity = entity,
                    onClick = onItemClick
                ) {
                    entity.id?.let {
                        onDeleteMenuClicked.invoke(it.toLong())
                    }
                }
                if (index < entities.lastIndex) {
                    Divider(Modifier
                        .animateItemPlacement(animationSpec)
                        .fillMaxWidth())
                }
            }
        }
    }
}

private fun ensurePermissions(
    context: Context,
    requestPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    block: () -> Unit,
): Boolean =
    // Use MediaStore to save files in public directories above Android Q.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        && !verifyPermissions(context, requestPermissionLauncher)
    ) {
        block.invoke()
        false
    } else true

/** Check necessary permissions. */
private fun verifyPermissions(
    context: Context,
    requestPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
): Boolean {
    // Check if we have write permission
    return if (ContextCompat.checkSelfPermission(context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        // We don't have permission so prompt the user
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        false
    } else true
}

class CollectionPreviewParameterProvider : PreviewParameterProvider<List<QuoteCollectionEntity>> {
    override val values: Sequence<List<QuoteCollectionEntity>> = sequenceOf(List(20) {
        QuoteCollectionEntity(it, "", "落霞与孤鹜齐飞，秋水共长天一色", "《滕王阁序》", "王勃")
    })
}

@Preview(name = "Collection Screen Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Collection Screen Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CollectionScreenPreview(
    @PreviewParameter(CollectionPreviewParameterProvider::class) entities: List<QuoteCollectionEntity>,
) {
    QuoteLockTheme {
        Surface {
            QuoteCollectionScreen(
                listUiState = QuoteCollectionListUiState(entities),
                menuUiState = QuoteCollectionMenuUiState(exportEnabled = true),
                uiEvent = null,
                onItemClick = {},
                onBack = {},
                onDeleteMenuClicked = {},
            )
        }
    }
}

@Preview(name = "Collection App Bar Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Collection App Bar Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CollectionDataRetentionMenuPreview() {
    QuoteLockTheme {
        Surface {
            CollectionBackupMenu(
                enableExport = true,
            ) {}
        }
    }
}