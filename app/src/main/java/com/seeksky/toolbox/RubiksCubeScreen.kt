package com.seeksky.toolbox

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.bigp.rubiksolver.color.FaceReading
import com.bigp.rubiksolver.color.OnDeviceColorDetector
import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Direction
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.FaceletColor
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.validation.CubeScheme
import com.bigp.rubiksolver.cube.validation.CubeValidator
import com.bigp.rubiksolver.cube.validation.ValidationResult
import com.bigp.rubiksolver.data.SolverService
import com.bigp.rubiksolver.render.CubeRenderer
import com.bigp.rubiksolver.ui.components.CubeSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private enum class CubePage { CAPTURE, REVIEW, SOLVING, SOLUTION }

private val captureOrder = listOf(FaceId.F, FaceId.U, FaceId.R, FaceId.D, FaceId.L, FaceId.B)

/** Six-photo Rubik's cube scanner, validator, offline solver and animated move viewer. */
@Composable
fun RubiksCubeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detector = remember { OnDeviceColorDetector() }
    val solverService = remember(context) { SolverService(context.applicationContext) }
    var cubeSize by remember { mutableStateOf(CubeSize.THREE) }
    var page by remember { mutableStateOf(CubePage.CAPTURE) }
    val readings = remember(cubeSize) { mutableStateMapOf<FaceId, FaceReading>() }
    val photos = remember(cubeSize) { mutableStateMapOf<FaceId, Bitmap>() }
    var captureIndex by remember(cubeSize) { mutableIntStateOf(0) }
    var pendingFace by remember { mutableStateOf<FaceId?>(null) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progressLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var scannedState by remember { mutableStateOf<CubeState?>(null) }
    var scheme by remember { mutableStateOf<CubeScheme?>(null) }
    var solution by remember { mutableStateOf<List<Move>>(emptyList()) }

    fun acceptPhoto(bitmap: Bitmap?) {
        val face = pendingFace
        pendingFace = null
        if (bitmap == null || face == null) {
            message = "没有取得照片，请重新拍摄。"
            return
        }
        val reading = runCatching { detector.readSquareBitmap(bitmap, cubeSize.n) }
            .getOrElse {
                message = "照片无法识别：${it.message ?: "未知错误"}"
                return
            }
        photos[face] = bitmap
        readings[face] = reading
        message = if (reading.lowestConfidence < 0.18f) {
            "本面有些色块识别把握较低，请现在检查带红框的色块；可以直接点按改色。"
        } else null
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val capturedUri = pendingPhotoUri
        val bitmap = if (saved) capturedUri?.let { decodeCapturedBitmap(context, it) } else null
        if (capturedUri != null) runCatching { context.contentResolver.delete(capturedUri, null, null) }
        pendingPhotoUri = null
        acceptPhoto(bitmap)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingPhotoUri?.let { cameraLauncher.launch(it) }
        else {
            pendingFace = null
            pendingPhotoUri = null
            message = "需要相机权限才能拍摄魔方；也可以在系统设置中授权后重试。"
        }
    }

    fun photograph(face: FaceId) {
        pendingFace = face
        pendingPhotoUri = runCatching { createCaptureUri(context) }.getOrElse {
            pendingFace = null
            message = "无法创建临时照片：${it.message ?: "存储不可用"}"
            return
        }
        message = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingPhotoUri?.let { cameraLauncher.launch(it) }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun resetCapture(newSize: CubeSize = cubeSize) {
        cubeSize = newSize
        readings.clear()
        photos.clear()
        captureIndex = 0
        page = CubePage.CAPTURE
        message = null
        scannedState = null
        scheme = null
        solution = emptyList()
    }

    when (page) {
        CubePage.CAPTURE -> CaptureCubePage(
            size = cubeSize,
            captureIndex = captureIndex,
            readings = readings,
            photos = photos,
            message = message,
            onSizeChange = { resetCapture(it) },
            onCapture = ::photograph,
            onSelectFace = {
                captureIndex = captureOrder.indexOf(it)
                message = null
            },
            onCellChange = { face, index, color ->
                val old = readings.getValue(face)
                readings[face] = old.copy(
                    cells = old.cells.mapIndexed { i, cell ->
                        if (i == index) cell.copy(color = color, confidence = 1f) else cell
                    }
                )
                message = null
            },
            onConfirmFace = {
                val nextMissing = captureOrder.indexOfFirst { readings[it] == null }
                if (nextMissing >= 0) {
                    captureIndex = nextMissing
                    message = null
                } else {
                    page = CubePage.REVIEW
                }
            },
        )

        CubePage.REVIEW -> ReviewCubePage(
            size = cubeSize,
            readings = readings,
            message = message,
            onCellChange = { face, index, color ->
                val old = readings.getValue(face)
                readings[face] = old.copy(
                    cells = old.cells.mapIndexed { i, cell ->
                        if (i == index) cell.copy(color = color, confidence = 1f) else cell
                    }
                )
                message = null
            },
            onRetake = { face ->
                captureIndex = captureOrder.indexOf(face)
                page = CubePage.CAPTURE
            },
            onBack = { page = CubePage.CAPTURE },
            onSolve = {
                val state = stateFromReadings(cubeSize, readings)
                when (val validation = CubeValidator.validate(state)) {
                    is ValidationResult.Invalid -> {
                        message = validation.issues.joinToString("\n") { translateValidation(it.message) }
                    }
                    is ValidationResult.Valid -> {
                        scannedState = state
                        scheme = validation.scheme
                        page = CubePage.SOLVING
                        progress = 0f
                        progressLabel = "正在准备离线求解表…"
                        scope.launch {
                            val outcome = solverService.solve(state) { update ->
                                scope.launch {
                                    progressLabel = translateStage(update.stage)
                                    progress = update.fraction.coerceIn(0f, 1f)
                                }
                            }
                            when (outcome) {
                                is SolveOutcome.Solved -> {
                                    solution = outcome.moves
                                    page = CubePage.SOLUTION
                                }
                                is SolveOutcome.Failed -> {
                                    message = "未能在时限内得到可靠解法：${outcome.reason}。请检查标出的色块或重新拍摄。"
                                    page = CubePage.REVIEW
                                }
                            }
                        }
                    }
                }
            },
        )

        CubePage.SOLVING -> SolvingCubePage(cubeSize, progressLabel, progress)

        CubePage.SOLUTION -> SolutionCubePage(
            scrambled = scannedState ?: CubeState.solved(cubeSize),
            solution = solution,
            scheme = scheme,
            onBack = { page = CubePage.REVIEW },
            onRestart = { resetCapture() },
        )
    }
}

@Composable
private fun CaptureCubePage(
    size: CubeSize,
    captureIndex: Int,
    readings: Map<FaceId, FaceReading>,
    photos: Map<FaceId, Bitmap>,
    message: String?,
    onSizeChange: (CubeSize) -> Unit,
    onCapture: (FaceId) -> Unit,
    onSelectFace: (FaceId) -> Unit,
    onCellChange: (FaceId, Int, FaceletColor) -> Unit,
    onConfirmFace: () -> Unit,
) {
    val face = captureOrder[captureIndex.coerceIn(0, 5)]
    val guidance = captureGuidance(face)
    val reading = readings[face]
    val scrollState = rememberScrollState()
    LaunchedEffect(captureIndex) { scrollState.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("魔方解答器", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("每拍一面就立即检查识别示意图并纠色，确认无误后再拍下一面。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CubeSize.THREE, CubeSize.FOUR, CubeSize.FIVE).forEach { option ->
                FilterChip(
                    selected = size == option,
                    onClick = { onSizeChange(option) },
                    label = { Text("${option.n} 阶") },
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拍摄前先固定方向", fontWeight = FontWeight.SemiBold)
                Text("任选一面朝向自己并把某一面保持在上方。六次拍摄之间只按提示翻转，不要在手中随意转动魔方，否则照片虽能识别，解法方向会错误。")
            }
        }
        Text(
            "第 ${captureIndex + 1} / 6 面 · ${faceChinese(face)}${if (reading == null) "" else " · 待确认"}",
            style = MaterialTheme.typography.titleLarge,
        )
        LinearProgressIndicator(
            progress = { readings.size / 6f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(guidance.first, fontWeight = FontWeight.SemiBold)
        Text(guidance.second, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
            val bitmap = photos[face]
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "${faceChinese(face)}面照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("将整个正方形面放在画面中央", fontWeight = FontWeight.SemiBold)
                        Text("四边留少量空隙，避免反光和阴影", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (reading != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${faceChinese(face)}面颜色示意图", fontWeight = FontWeight.SemiBold)
                    Text(
                        "请现在核对照片：点按错误色块可依次切换颜色，红框表示识别把握较低。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StickerGrid(size.n, reading, face, onCellChange)
                }
            }
            ColorCountSummary(size, readings)
        }
        message?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (reading == null) {
            Button(onClick = { onCapture(face) }, modifier = Modifier.fillMaxWidth()) {
                Text("拍摄${faceChinese(face)}面")
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onCapture(face) },
                    modifier = Modifier.weight(1f),
                ) { Text("重新拍摄") }
                Button(
                    onClick = onConfirmFace,
                    modifier = Modifier.weight(1.5f),
                ) {
                    Text(if (readings.size == 6) "确认并检查六面" else "确认，拍下一面")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            captureOrder.forEachIndexed { index, item ->
                val selected = item == face
                Box(
                    Modifier.size(34.dp)
                        .background(
                            when {
                                selected -> MaterialTheme.colorScheme.primary
                                readings[item] != null -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        )
                        .clickable(enabled = readings[item] != null) { onSelectFace(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (readings[item] != null) "✓" else "${index + 1}",
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ReviewCubePage(
    size: CubeSize,
    readings: Map<FaceId, FaceReading>,
    message: String?,
    onCellChange: (FaceId, Int, FaceletColor) -> Unit,
    onRetake: (FaceId) -> Unit,
    onBack: () -> Unit,
    onSolve: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("检查识别结果", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("点按错误色块可依次切换颜色。带红框的色块识别置信度较低，请优先核对。")
        ColorCountSummary(size, readings)
        captureOrder.forEach { face ->
            val reading = readings[face] ?: return@forEach
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${faceChinese(face)}面 (${face.letter})", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { onRetake(face) }) { Text("重拍") }
                    }
                    StickerGrid(size.n, reading, face, onCellChange)
                }
            }
        }
        message?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Button(onClick = onSolve, modifier = Modifier.fillMaxWidth()) { Text("校验并生成复原步骤") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回拍摄") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColorCountSummary(size: CubeSize, readings: Map<FaceId, FaceReading>) {
    val expected = size.faceletsPerFace
    val counts = FaceletColor.ALL.associateWith { target ->
        readings.values.sumOf { reading -> reading.cells.count { it.color == target } }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                if (readings.size == 6) "六面颜色数量（每种应为 $expected）"
                else "已拍 ${readings.size} / 6 面 · 累计颜色数量",
                style = MaterialTheme.typography.labelLarge,
            )
            if (readings.size < 6) {
                Text(
                    "随拍摄实时累计；六面完成后每种颜色都应为 $expected。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FaceletColor.ALL.chunked(3).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    line.forEach { color ->
                        val count = counts.getValue(color)
                        val invalidCount = count > expected || (readings.size == 6 && count != expected)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).background(composeColor(color), CircleShape))
                            Text(
                                " ${colorChinese(color)} $count/$expected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (invalidCount) MaterialTheme.colorScheme.error else Color.Unspecified,
                                fontWeight = if (invalidCount) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerGrid(
    n: Int,
    reading: FaceReading,
    face: FaceId,
    onCellChange: (FaceId, Int, FaceletColor) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(n) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(n) { col ->
                    val index = row * n + col
                    val cell = reading.cells[index]
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .background(composeColor(cell.color), RoundedCornerShape(4.dp))
                            .then(
                                if (cell.confidence < 0.18f) Modifier.border(3.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                                else Modifier
                            )
                            .clickable {
                                val next = FaceletColor.ALL[(cell.color.ordinal + 1) % FaceletColor.ALL.size]
                                onCellChange(face, index, next)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cell.confidence < 0.18f) Text("!", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SolvingCubePage(size: CubeSize, label: String, progress: Float) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CircularProgressIndicator()
            Text("正在求解 ${size.n} 阶魔方", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, textAlign = TextAlign.Center)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (size.n >= 4) Text("大阶魔方会依次处理中心块、棱块配对和奇偶情况，通常需要几秒。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SolutionCubePage(
    scrambled: CubeState,
    solution: List<Move>,
    scheme: CubeScheme?,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember(scrambled, solution) { mutableIntStateOf(0) }
    var renderer by remember { mutableStateOf<CubeRenderer?>(null) }
    var animating by remember { mutableStateOf(false) }
    val shownState = remember(scrambled, solution, step) { scrambled.apply(solution.take(step)) }
    LaunchedEffect(shownState) { renderer?.setState(shownState) }
    val nextMove = solution.getOrNull(step)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (step >= solution.size) "复原完成" else "第 ${step + 1} / ${solution.size} 步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("始终保持拍摄时的前面和上面方向", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("纠色") }
        }
        LinearProgressIndicator(
            progress = { if (solution.isEmpty()) 1f else step.toFloat() / solution.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Card(Modifier.fillMaxWidth().weight(1f)) {
            CubeSurface(shownState, Modifier.fillMaxSize()) { renderer = it }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(nextMove?.toString() ?: "✓", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    nextMove?.let { describeMoveChinese(it, scrambled.size, scheme) }
                        ?: if (solution.isEmpty()) "这个魔方已经复原，无需转动。" else "所有步骤已完成，请检查六面。",
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (solution.isNotEmpty()) {
            Text(
                solution.drop(step).take(8).joinToString("  ") { it.toString() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { if (step > 0 && !animating) step-- },
                enabled = step > 0 && !animating,
                modifier = Modifier.weight(1f),
            ) { Text("上一步") }
            Button(
                onClick = {
                    val move = nextMove
                    val activeRenderer = renderer
                    if (move == null) {
                        onRestart()
                    } else if (activeRenderer != null && !animating) {
                        animating = true
                        activeRenderer.animate(move) {
                            scope.launch(Dispatchers.Main) {
                                step++
                                animating = false
                            }
                        }
                    }
                },
                enabled = !animating,
                modifier = Modifier.weight(1.4f),
            ) { Text(if (step >= solution.size) "重新扫描" else if (animating) "转动中…" else "播放并继续") }
        }
    }
}

private fun OnDeviceColorDetector.readSquareBitmap(bitmap: Bitmap, n: Int): FaceReading {
    val side = minOf(bitmap.width, bitmap.height)
    require(side >= n * 6) { "照片分辨率过低" }
    val left = (bitmap.width - side) / 2
    val top = (bitmap.height - side) / 2
    val pixels = IntArray(side * side)
    bitmap.getPixels(pixels, 0, side, left, top, side, side)
    return read(pixels, side, n)
}

private fun createCaptureUri(context: android.content.Context): Uri {
    val directory = File(context.cacheDir, "rubik_photos").apply { mkdirs() }
    val file = File.createTempFile("cube_", ".jpg", directory)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Decodes a camera image at a bounded size and honours its EXIF rotation. */
private fun decodeCapturedBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    // Six photos stay available for review, so cap each one to keep the whole flow comfortably
    // below the heap limit while still leaving far more than enough pixels for a 5x5 grid.
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1000) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val orientation = context.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
}

private fun stateFromReadings(size: CubeSize, readings: Map<FaceId, FaceReading>): CubeState {
    require(readings.size == 6) { "六个面尚未全部拍摄" }
    val colors = FaceId.entries.flatMap { face -> readings.getValue(face).colors() }
    return CubeState.fromColors(size, colors)
}

private fun captureGuidance(face: FaceId): Pair<String, String> = when (face) {
    FaceId.F -> "拍摄当前正对你的面。" to "记住它是前面，后续不要改变上下面方向。"
    FaceId.U -> "把魔方顶部朝自己方向倾倒，拍摄上面。" to "原来的前面应位于画面下边缘。"
    FaceId.R -> "恢复直立，再向左转魔方四分之一圈，拍摄右面。" to "上面仍在上方，原前面位于画面左边缘。"
    FaceId.D -> "恢复直立，再把底部朝自己方向抬起，拍摄下面。" to "原来的前面应位于画面上边缘。"
    FaceId.L -> "恢复直立，再向右转魔方四分之一圈，拍摄左面。" to "上面仍在上方，原前面位于画面右边缘。"
    FaceId.B -> "恢复直立，再水平转动半圈，拍摄后面。" to "原来的上面仍在画面上方。"
}

private fun faceChinese(face: FaceId): String = when (face) {
    FaceId.U -> "上"; FaceId.R -> "右"; FaceId.F -> "前"
    FaceId.D -> "下"; FaceId.L -> "左"; FaceId.B -> "后"
}

private fun composeColor(color: FaceletColor): Color = Color(color.srgb)

private fun describeMoveChinese(move: Move, size: CubeSize, scheme: CubeScheme?): String {
    val face = faceChinese(move.face)
    val color = scheme?.colorOf?.get(move.face)?.let { "（${colorChinese(it)}）" }.orEmpty()
    val layers = when {
        move.wide -> "从${face}面起的 ${move.layer} 层"
        move.layer > 1 -> "从${face}面向内第 ${move.layer} 层"
        else -> "${face}面$color"
    }
    val direction = when (move.direction) {
        Direction.CW -> "顺时针转 90°"
        Direction.CCW -> "逆时针转 90°"
        Direction.DOUBLE -> "转 180°"
    }
    val viewpoint = if (move.direction == Direction.DOUBLE) "" else "（正对${face}面观察方向）"
    return "转动$layers：$direction$viewpoint。${if (size.n >= 4 && (move.wide || move.layer > 1)) "只带动上述层，保持其他层不动。" else ""}"
}

private fun colorChinese(color: FaceletColor): String = when (color) {
    FaceletColor.WHITE -> "白色"; FaceletColor.YELLOW -> "黄色"; FaceletColor.RED -> "红色"
    FaceletColor.ORANGE -> "橙色"; FaceletColor.BLUE -> "蓝色"; FaceletColor.GREEN -> "绿色"
}

private fun translateStage(stage: String): String = when {
    stage.contains("table", true) -> "正在准备三阶搜索表…"
    stage.contains("centre", true) || stage.contains("center", true) -> "正在归位中心块…"
    stage.contains("edge", true) -> "正在配对棱块…"
    stage.contains("parity", true) -> "正在处理大阶奇偶情况…"
    stage.contains("phase", true) -> "正在进行两阶段搜索…"
    else -> stage.ifBlank { "正在计算可靠解法…" }
}

private fun translateValidation(message: String): String = when {
    message.contains("Every colour", true) -> "每种颜色的数量必须正好相同。请检查颜色数量不正确的色块。"
    message.contains("centre", true) -> "中心色存在冲突，请检查各面的中心色块。"
    message.contains("corner", true) -> "角块颜色组合或方向不符合真实魔方，请重点检查八个角。"
    message.contains("edge", true) -> "棱块颜色组合或方向不符合真实魔方，请检查各条棱。"
    message.contains("swapped", true) || message.contains("parity", true) -> "当前状态在物理上不可达，通常是两个色块识别颠倒。"
    else -> message
}
