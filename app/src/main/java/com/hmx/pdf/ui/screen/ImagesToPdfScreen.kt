package com.hmx.pdf.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.hmx.pdf.R
import com.hmx.pdf.imageeditor.engine.ImageIo
import com.hmx.pdf.ui.components.DestructiveGlassButton
import com.hmx.pdf.ui.components.GlassScreenHeaderRow
import com.hmx.pdf.ui.components.GlassScreenScaffold
import com.hmx.pdf.ui.components.LiquidButton
import com.hmx.pdf.ui.components.LiquidSaveDialog
import com.hmx.pdf.ui.components.UnsavedChangesDialog
import com.hmx.pdf.ui.components.liquidGlassPanel
import com.hmx.pdf.ui.components.rememberScreenBackdrop
import com.hmx.pdf.ui.theme.LocalIsDarkMode
import com.hmx.pdf.ui.utils.rememberUISensor
import com.hmx.pdf.ui.viewmodel.ImagesToPdfViewModel
import com.hmx.pdf.ui.viewmodel.SelectedImage
import com.hmx.pdf.ui.viewmodel.rotateQuarterTurnsAt
import com.hmx.pdf.ui.viewmodel.viewerIndexAfterRemoval
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun ImagesToPdfScreen(
    backdrop: LayerBackdrop,
    viewModel: ImagesToPdfViewModel,
    onBack: () -> Unit,
    onViewOutput: (android.net.Uri) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val uiSensor = rememberUISensor()
    val isDark = LocalIsDarkMode.current
    val text = if (isDark) Color(0xFFF0F0F0) else Color(0xFF222222)
    val sub = if (isDark) Color(0xFFAAAAAA) else Color(0xFF777777)
    val accent = Color(0xFF5E35B1)

    var showSaveDialog by remember { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableStateOf(-1) }

    LaunchedEffect(state.images.size) {
        if (viewerIndex >= state.images.size) viewerIndex = -1
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.onPickImages(uris)
    }

    var isVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { isVisible = true }
    val density = LocalDensity.current.density

    val topBarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "imagesTopBarAlpha"
    )

    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600, delayMillis = 100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "imagesContentAlpha"
    )
    val contentOffsetY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isVisible) 0f else 24f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600, delayMillis = 100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "imagesContentOffsetY"
    )

    val screenBackdrop = rememberScreenBackdrop(backdrop)
    Box(Modifier.fillMaxSize()) {
    GlassScreenScaffold(
        backdrop = backdrop,
        screenBackdrop = screenBackdrop,
        contentBottomPadding = 16.dp,
        header = { headerBackdrop ->
            // Fade only — the header is glass, and translating glass re-runs its blur+lens.
            GlassScreenHeaderRow(
                title = stringResource(R.string.images_to_pdf_title),
                backdrop = headerBackdrop,
                onBack = onBack,
                modifier = Modifier.graphicsLayer { alpha = topBarAlpha }
            )
        }
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffsetY * density
                },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidButton(onClick = { picker.launch("image/*") }, backdrop = backdrop, tint = accent) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp), Color.White)
                    BasicText(stringResource(R.string.images_add), style = TextStyle(Color.White, 14.sp, FontWeight.Medium))
                }
                if (state.images.isNotEmpty()) {
                    DestructiveGlassButton(
                        stringResource(R.string.viewer_clear),
                        {
                            viewModel.clearImages()
                            viewerIndex = -1
                        },
                        backdrop
                    )
                }
            }

            // Page-size toggle
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidButton(
                    onClick = { viewModel.setFitToA4(true) }, backdrop = backdrop,
                    surfaceColor = if (state.fitToA4) accent.copy(0.30f) else Color.White.copy(0.06f)
                ) { BasicText(stringResource(R.string.images_fit_a4), style = TextStyle(text, 13.sp, FontWeight.Medium)) }
                LiquidButton(
                    onClick = { viewModel.setFitToA4(false) }, backdrop = backdrop,
                    surfaceColor = if (!state.fitToA4) accent.copy(0.30f) else Color.White.copy(0.06f)
                ) { BasicText(stringResource(R.string.images_original_size), style = TextStyle(text, 13.sp, FontWeight.Medium)) }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.images.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        BasicText(stringResource(R.string.images_hint), style = TextStyle(sub, 13.sp))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(state.images) { index, image ->
                            Box(
                                Modifier
                                    .aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(0.06f))
                                    .clickable {
                                        showSaveDialog = false
                                        viewerIndex = index
                                    }
                            ) {
                                AsyncImage(
                                    model = image.uri,
                                    contentDescription = stringResource(R.string.image_number, index + 1),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            rotationZ = image.rotationQuarterTurns * 90f
                                        }
                                )
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                                        .clip(CircleShape).background(Color.Black.copy(0.55f))
                                        .clickable {
                                            viewModel.removeImage(index)
                                            viewerIndex = viewerIndexAfterRemoval(
                                                viewerIndex,
                                                index,
                                                state.images.size - 1
                                            )
                                        },
                                    Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.delete), Modifier.size(14.dp), Color.White)
                                }
                            }
                        }
                    }
                }
            }

            state.errorMessage?.let { BasicText(it, style = TextStyle(Color(0xFFEF5350), 12.sp)) }
            state.resultMessage?.let { msg ->
                Row(Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    BasicText(msg, style = TextStyle(Color(0xFFB9F6CA), 12.sp), modifier = Modifier.weight(1f))
                    state.lastOutputUri?.let { uri ->
                        LiquidButton(onClick = { onViewOutput(uri) }, backdrop = backdrop, tint = accent) {
                            BasicText(stringResource(R.string.open), style = TextStyle(Color.White, 12.sp, FontWeight.Medium))
                        }
                    }
                }
            }

            LiquidButton(
                onClick = { if (!state.isSaving && state.images.isNotEmpty()) showSaveDialog = true },
                backdrop = backdrop, tint = accent, modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    if (state.isSaving) stringResource(R.string.images_to_pdf_working) else "${stringResource(R.string.tool_create)} (${state.images.size})",
                    style = TextStyle(Color.White, 15.sp, FontWeight.Medium),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // In-window glass (not a Dialog window) so it refracts the live screen.
    LiquidSaveDialog(
        visible = showSaveDialog,
        initialFileName = "HMX_PDF_Images",
        backdrop = screenBackdrop.glass,
        uiSensor = uiSensor,
        onDismiss = { showSaveDialog = false },
        onSave = { fileName, overrideUri ->
            showSaveDialog = false
            viewModel.save(context, fileName, overrideUri)
        }
    )

    if (viewerIndex in state.images.indices) {
        ImageViewerOverlay(
            images = state.images,
            initialIndex = viewerIndex,
            backdrop = screenBackdrop.glass,
            onIndexChanged = { viewerIndex = it },
            onApply = viewModel::applyRotations,
            onClose = { viewerIndex = -1 }
        )
    }
    }
}

private const val PREVIEW_MAX_DIM = 2048
private const val PREVIEW_MAX_PIXELS = 4_000_000L

@Composable
private fun ImageViewerOverlay(
    images: List<SelectedImage>,
    initialIndex: Int,
    backdrop: Backdrop,
    onIndexChanged: (Int) -> Unit,
    onApply: (List<Int>) -> Unit,
    onClose: () -> Unit
) {
    if (images.isEmpty()) return

    val view = LocalView.current
    val isDark = LocalIsDarkMode.current
    val committed = remember(images) { images.map { it.rotationQuarterTurns }.toIntArray() }
    var draft by rememberSaveable {
        mutableStateOf(images.map { it.rotationQuarterTurns }.toIntArray())
    }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size }
    )

    LaunchedEffect(images.size) {
        if (draft.size != images.size) draft = committed.copyOf()
    }
    LaunchedEffect(pagerState.currentPage) {
        onIndexChanged(pagerState.currentPage)
    }

    val hasChanges = !draft.contentEquals(committed)
    val requestClose: () -> Unit = {
        if (hasChanges) showDiscardDialog = true else onClose()
    }
    val applyAndClose: () -> Unit = {
        onApply(draft.toList())
        onClose()
    }

    DisposableEffect(view, isDark) {
        val ctrl = view.context.findActivity()?.window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            ctrl?.isAppearanceLightStatusBars = !isDark
            ctrl?.isAppearanceLightNavigationBars = !isDark
        }
    }

    BackHandler(enabled = !showDiscardDialog) { requestClose() }

    Box(Modifier.fillMaxSize().background(Color(0xFF08080A))) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ViewerImagePage(
                uri = images[page].uri,
                rotationQuarterTurns = draft.getOrElse(page) { 0 },
                contentDescription = stringResource(R.string.image_number, page + 1),
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(0.78f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                ViewerCircleButton(
                    onClick = requestClose,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.Rounded.ArrowBackIosNew,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                }
                BasicText(
                    text = stringResource(
                        R.string.images_viewer_counter,
                        pagerState.currentPage + 1,
                        images.size
                    ),
                    modifier = Modifier.align(Alignment.Center),
                    style = TextStyle(Color.White, 15.sp, FontWeight.SemiBold)
                )
                ViewerPillButton(
                    onClick = applyAndClose,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = Color.White
                    )
                    BasicText(
                        stringResource(R.string.done),
                        style = TextStyle(Color.White, 13.sp, FontWeight.SemiBold)
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(0.82f))
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ViewerToolButton(
                    onClick = {
                        draft = rotateQuarterTurnsAt(
                            draft.toList(),
                            pagerState.currentPage,
                            -1
                        ).toIntArray()
                    },
                    icon = Icons.Rounded.RotateLeft,
                    label = stringResource(R.string.image_rotate_left),
                    modifier = Modifier.weight(1f)
                )
                ViewerToolButton(
                    onClick = {
                        draft = rotateQuarterTurnsAt(
                            draft.toList(),
                            pagerState.currentPage,
                            1
                        ).toIntArray()
                    },
                    icon = Icons.Rounded.RotateRight,
                    label = stringResource(R.string.image_rotate_right),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        UnsavedChangesDialog(
            visible = showDiscardDialog,
            onDiscard = {
                showDiscardDialog = false
                onClose()
            },
            onCancel = { showDiscardDialog = false },
            onSave = {
                showDiscardDialog = false
                applyAndClose()
            },
            backdrop = backdrop,
            body = stringResource(R.string.images_viewer_pending_body),
            saveLabel = stringResource(R.string.images_viewer_apply)
        )
    }
}

@Composable
private fun ViewerCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun ViewerPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun ViewerToolButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = Color.White
        )
        Spacer(Modifier.size(8.dp))
        BasicText(label, style = TextStyle(Color.White, 13.sp, FontWeight.Medium))
    }
}

@Composable
private fun ViewerImagePage(
    uri: Uri,
    rotationQuarterTurns: Int,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = remember(uri) { mutableStateOf<Bitmap?>(null) }
    val failedState = remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        failedState.value = false
        val decoded = try {
            withContext(Dispatchers.IO + NonCancellable) {
                ImageIo.decode(context, uri, PREVIEW_MAX_DIM, PREVIEW_MAX_PIXELS)
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (!isActive) {
            decoded?.recycle()
            return@LaunchedEffect
        }
        bitmapState.value = decoded
        failedState.value = decoded == null
    }

    DisposableEffect(uri) {
        onDispose {
            bitmapState.value?.recycle()
            bitmapState.value = null
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = bitmapState.value
        if (bitmap == null) {
            if (failedState.value) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color.White.copy(0.7f)
                    )
                    BasicText(
                        stringResource(R.string.open_image_failed),
                        style = TextStyle(Color.White.copy(0.75f), 14.sp)
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
            return@BoxWithConstraints
        }

        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        val fitUnrotated = minOf(width / imageWidth, height / imageHeight)
        val fitRotated = minOf(width / imageHeight, height / imageWidth)
        val rotated = rotationQuarterTurns % 2 != 0
        val layerFit = if (rotated) fitRotated / fitUnrotated else 1f
        val baseWidth = if (rotated) imageHeight * fitRotated else imageWidth * fitUnrotated
        val baseHeight = if (rotated) imageWidth * fitRotated else imageHeight * fitUnrotated

        var zoom by remember(uri) { mutableFloatStateOf(1f) }
        var pan by remember(uri) { mutableStateOf(Offset.Zero) }
        LaunchedEffect(rotationQuarterTurns, width, height) {
            zoom = 1f
            pan = Offset.Zero
        }

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uri, width, height, rotationQuarterTurns) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var owned = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) owned = true
                            if (owned || zoom > 1f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                val maxX = maxOf(0f, (baseWidth * newZoom - width) / 2f)
                                val maxY = maxOf(0f, (baseHeight * newZoom - height) / 2f)
                                pan = Offset(
                                    (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                    (pan.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                                zoom = newZoom
                                if (newZoom <= 1.001f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    rotationZ = rotationQuarterTurns * 90f
                    scaleX = layerFit * zoom
                    scaleY = layerFit * zoom
                    translationX = pan.x
                    translationY = pan.y
                }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
