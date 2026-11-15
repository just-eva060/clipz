package com.swiftget.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private fun gamePrefs(ctx: Context) = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)

fun gameBest(ctx: Context, key: String): Int = gamePrefs(ctx).getInt(key, 0)

/** Swipe detection. onSwipe gets the direction: 0 left, 1 right, 2 up, 3 down. */
@Composable
private fun Modifier.swipes(onSwipe: (Int) -> Unit): Modifier {
    val callback by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragCancel = { total = Offset.Zero },
            onDragEnd = {
                val x = total.x
                val y = total.y
                if (abs(x) > 24f || abs(y) > 24f) {
                    callback(if (abs(x) > abs(y)) (if (x > 0) 1 else 0) else (if (y > 0) 3 else 2))
                }
            },
            onDrag = { change, amount ->
                change.consume()
                total += amount
            },
        )
    }
}

@Composable
private fun ScoreRow(a: String, b: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(Modifier.weight(1f)) {
            Text(a, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        GlassCard(Modifier.weight(1f)) {
            Text(b, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ================================================================ Snake

private fun randomCell(n: Int, avoid: List<Pair<Int, Int>>): Pair<Int, Int> =
    (0 until n * n).map { (it % n) to (it / n) }.filter { it !in avoid }.randomOrNull() ?: (0 to 0)

@Composable
fun SnakeGame() {
    val ctx = LocalContext.current
    val n = 16
    val start = listOf(8 to 8, 7 to 8, 6 to 8)
    var snake by remember { mutableStateOf(start) }
    var dir by remember { mutableIntStateOf(1) }
    var pending by remember { mutableIntStateOf(1) }
    var food by remember { mutableStateOf(randomCell(n, start)) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(gameBest(ctx, "snake_best")) }
    var over by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val dx = intArrayOf(-1, 1, 0, 0)
    val dy = intArrayOf(0, 0, -1, 1)

    fun turn(d: Int) {
        val opposite = intArrayOf(1, 0, 3, 2)[dir]
        if (d != opposite) pending = d
    }

    fun restart() {
        snake = start; dir = 1; pending = 1; food = randomCell(n, start); score = 0; over = false; round++
    }

    LaunchedEffect(round, over) {
        if (over) return@LaunchedEffect
        while (true) {
            delay((190 - minOf(score, 20) * 5).toLong())
            dir = pending
            val head = snake.first()
            val next = (head.first + dx[dir]) to (head.second + dy[dir])
            val hitsSelf = next in snake.dropLast(1)
            if (next.first !in 0 until n || next.second !in 0 until n || hitsSelf) {
                if (score > best) {
                    best = score
                    gamePrefs(ctx).edit().putInt("snake_best", score).apply()
                }
                over = true
                break
            }
            val ate = next == food
            snake = if (ate) listOf(next) + snake else listOf(next) + snake.dropLast(1)
            if (ate) {
                score++
                food = randomCell(n, snake)
            }
        }
    }

    Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoreRow("Score $score", "Best $best")
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0F151C)).swipes { turn(it) },
        ) {
            val c = size.width / n
            drawCircle(Coral, radius = c * 0.38f, center = Offset(food.first * c + c / 2, food.second * c + c / 2))
            snake.forEachIndexed { i, p ->
                drawRoundRect(
                    color = if (i == 0) Mint else Mint.copy(alpha = 0.72f),
                    topLeft = Offset(p.first * c + 1.5f, p.second * c + 1.5f),
                    size = Size(c - 3f, c - 3f),
                    cornerRadius = CornerRadius(c * 0.3f),
                )
            }
        }
        if (over) {
            Text("Game over", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Pill("Play again", accent = true) { restart() }
        } else {
            Text("Swipe on the board, or use the arrows", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundAction("▲", size = 50.dp) { turn(2) }
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    RoundAction("◀", size = 50.dp) { turn(0) }
                    RoundAction("▶", size = 50.dp) { turn(1) }
                }
                RoundAction("▼", size = 50.dp) { turn(3) }
            }
        }
    }
}

// ================================================================ 2048

private fun lines(b: List<Int>, dir: Int): List<List<Int>> = when (dir) {
    0 -> (0..3).map { r -> (0..3).map { c -> b[r * 4 + c] } }
    1 -> (0..3).map { r -> (0..3).map { c -> b[r * 4 + 3 - c] } }
    2 -> (0..3).map { c -> (0..3).map { r -> b[r * 4 + c] } }
    else -> (0..3).map { c -> (0..3).map { r -> b[(3 - r) * 4 + c] } }
}

private fun unlines(l: List<List<Int>>, dir: Int): List<Int> {
    val b = MutableList(16) { 0 }
    for (i in 0..3) for (j in 0..3) {
        val v = l[i][j]
        when (dir) {
            0 -> b[i * 4 + j] = v
            1 -> b[i * 4 + 3 - j] = v
            2 -> b[j * 4 + i] = v
            else -> b[(3 - j) * 4 + i] = v
        }
    }
    return b
}

private fun slide(b: List<Int>, dir: Int): Triple<List<Int>, Int, Boolean> {
    var gained = 0
    val moved = lines(b, dir).map { line ->
        val t = line.filter { it != 0 }
        val out = ArrayList<Int>()
        var i = 0
        while (i < t.size) {
            if (i + 1 < t.size && t[i] == t[i + 1]) {
                out.add(t[i] * 2)
                gained += t[i] * 2
                i += 2
            } else {
                out.add(t[i])
                i++
            }
        }
        while (out.size < 4) out.add(0)
        out
    }
    val nb = unlines(moved, dir)
    return Triple(nb, gained, nb != b)
}

private fun addTile(b: List<Int>): List<Int> {
    val empty = b.indices.filter { b[it] == 0 }
    if (empty.isEmpty()) return b
    val m = b.toMutableList()
    m[empty.random()] = if (Math.random() < 0.9) 2 else 4
    return m
}

private fun canMove(b: List<Int>): Boolean = b.contains(0) || (0..3).any { slide(b, it).third }

private fun newBoard(): List<Int> = addTile(addTile(List(16) { 0 }))

private fun tileColor(v: Int): Color = when (v) {
    0 -> Color(0xFF232D3A)
    2 -> Color(0xFFCFE8E0)
    4 -> Color(0xFFA8E0D0)
    8 -> Color(0xFFFFB38A)
    16 -> Color(0xFFFF9466)
    32 -> Color(0xFFFF7A59)
    64 -> Color(0xFFFF5A3C)
    128 -> Color(0xFF8FD694)
    256 -> Color(0xFF5CCB9B)
    512 -> Color(0xFF2DD4A7)
    1024 -> Color(0xFF7C5CFF)
    else -> Color(0xFFFFC53D)
}

@Composable
fun Game2048() {
    val ctx = LocalContext.current
    var board by remember { mutableStateOf(newBoard()) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(gameBest(ctx, "g2048_best")) }
    var over by remember { mutableStateOf(false) }

    fun play(dir: Int) {
        if (over) return
        val (moved, gained, changed) = slide(board, dir)
        if (!changed) return
        val next = addTile(moved)
        board = next
        score += gained
        if (score > best) {
            best = score
            gamePrefs(ctx).edit().putInt("g2048_best", score).apply()
        }
        if (!canMove(next)) over = true
    }

    Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoreRow("Score $score", "Best $best")
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF131A22)).padding(6.dp).swipes { play(it) },
        ) {
            for (r in 0..3) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0..3) {
                        val v = board[r * 4 + c]
                        Box(
                            Modifier.weight(1f).fillMaxWidth().padding(3.dp).clip(RoundedCornerShape(10.dp))
                                .background(tileColor(v)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (v != 0) {
                                Text(
                                    "$v", fontWeight = FontWeight.ExtraBold,
                                    fontSize = if (v < 100) 28.sp else if (v < 1000) 24.sp else 18.sp,
                                    color = if (v <= 4) Color(0xFF0B0F14) else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (over) Text("No more moves", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        else Text("Swipe to slide tiles. Join equal numbers.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Pill("New game", accent = over) {
            board = newBoard(); score = 0; over = false
        }
    }
}

// ================================================================ Memory match

@Composable
fun MemoryGame() {
    val ctx = LocalContext.current
    val faces = listOf("🐶", "🐱", "🦊", "🐸", "🐵", "🦁", "🐼", "🐙")
    var cards by remember { mutableStateOf((faces + faces).shuffled()) }
    var flipped by remember { mutableStateOf(listOf<Int>()) }
    var matched by remember { mutableStateOf(setOf<Int>()) }
    var moves by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(gameBest(ctx, "memory_best")) } // fewest moves; 0 = none yet
    val won = matched.size == cards.size

    LaunchedEffect(flipped) {
        if (flipped.size == 2) {
            delay(700)
            flipped = emptyList()
        }
    }

    fun tap(i: Int) {
        if (won || i in matched || i in flipped || flipped.size >= 2) return
        val f = flipped + i
        if (f.size == 2) {
            moves++
            if (cards[f[0]] == cards[f[1]]) {
                matched = matched + f
                flipped = emptyList()
                if (matched.size == cards.size && (best == 0 || moves < best)) {
                    best = moves
                    gamePrefs(ctx).edit().putInt("memory_best", moves).apply()
                }
            } else {
                flipped = f
            }
        } else {
            flipped = f
        }
    }

    Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoreRow("Moves $moves", if (best == 0) "Best —" else "Best $best")
        Column(Modifier.fillMaxWidth()) {
            for (r in 0..3) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0..3) {
                        val i = r * 4 + c
                        val up = i in flipped || i in matched
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (i in matched) Mint.copy(alpha = 0.25f) else if (up) MaterialTheme.colorScheme.surfaceVariant else Violet.copy(alpha = 0.55f))
                                .clickable { tap(i) },
                            contentAlignment = Alignment.Center,
                        ) { Text(if (up) cards[i] else "❓", fontSize = 30.sp) }
                    }
                }
            }
        }
        if (won) Text("You won in $moves moves! 🎉", fontWeight = FontWeight.Bold, color = Mint)
        else Text("Find all the matching pairs.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Pill("New game", accent = won) {
            cards = (faces + faces).shuffled(); flipped = emptyList(); matched = emptySet(); moves = 0
        }
    }
}
