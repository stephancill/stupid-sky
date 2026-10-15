package net.stupidtech.sky.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the detail sheet can rest. [Hidden] is below the screen; settling there dismisses the sheet. */
enum class SheetStop { Hidden, Peek, Half, Full }

internal val SheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * The stop a release should settle on: the nearest one for a slow release, otherwise the next one in the
 * fling direction. Positions are y offsets of the sheet's top edge, so a positive velocity moves down.
 */
internal fun <T> pickStop(stops: List<Pair<T, Float>>, offset: Float, velocity: Float, velocityThreshold: Float): T {
    require(stops.isNotEmpty())
    if (abs(velocity) < velocityThreshold) return stops.minBy { abs(it.second - offset) }.first
    return if (velocity > 0) {
        stops.filter { it.second > offset + 1f }.minByOrNull { it.second } ?: stops.maxBy { it.second }
    } else {
        stops.filter { it.second < offset - 1f }.maxByOrNull { it.second } ?: stops.minBy { it.second }
    }.first
}

private fun DraggableAnchors<SheetStop>.toList(): List<Pair<SheetStop, Float>> =
    SheetStop.entries.filter { hasPositionFor(it) }.map { it to positionOf(it) }

internal suspend fun AnchoredDraggableState<SheetStop>.settleTo(velocity: Float, velocityThreshold: Float) {
    if (offset.isNaN()) return
    animateTo(pickStop(anchors.toList(), offset, velocity, velocityThreshold), SheetSpring)
}

/** Peek and the larger of Half/Full, whichever this content has, for the minimise toggle. */
internal fun AnchoredDraggableState<SheetStop>.expandedStop(): SheetStop =
    if (anchors.hasPositionFor(SheetStop.Half)) SheetStop.Half else SheetStop.Full

/**
 * Lets the sheet's own scrollable content drag the sheet: pushing up expands the sheet before the content
 * scrolls, pulling down once the content is at its top collapses it, and leftover fling velocity settles it.
 */
private class SheetNestedScroll(
    private val state: AnchoredDraggableState<SheetStop>,
    private val velocityThreshold: Float,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val d = available.y
        return if (d < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(d)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val v = available.y
        // Flinging up while the sheet isn't fully open opens it instead of flinging the content.
        if (v < 0 && !state.offset.isNaN() && state.offset > state.anchors.minPosition()) {
            state.settleTo(v, velocityThreshold)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settleTo(available.y, velocityThreshold)
        return available
    }
}

/**
 * Bottom sheet for the detail panels with Peek, Half and Full stops (Half is skipped when the content is
 * short). Drags, flings, scrolling inside the content and button-driven moves all run through one
 * [AnchoredDraggableState], so any of them takes over a running animation from wherever the sheet is.
 * Dragging or flinging below Peek dismisses it.
 *
 * [content] receives a modifier for its header; Peek shows the sheet down to the bottom of that header.
 * [key] identifies what is shown: a new key slides the sheet to Half.
 */
@Composable
fun DetailSheet(
    state: AnchoredDraggableState<SheetStop>,
    key: Any,
    containerHeight: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (headerModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    // Full stops below the settings/search row so those stay reachable.
    val topGap = WindowInsets.statusBars.getTop(density) + with(density) { 76.dp.toPx() }
    val minStep = with(density) { 48.dp.toPx() }
    val velocityThreshold = with(density) { 400.dp.toPx() }

    var handleH by remember { mutableIntStateOf(0) }
    var contentH by remember { mutableIntStateOf(0) }
    var headerBottom by remember { mutableFloatStateOf(0f) }
    // Plain holders: the header and content callbacks can arrive in either order.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(2) }
    fun measureHeader() {
        val root = coords[0] ?: return
        val header = coords[1] ?: return
        if (root.isAttached && header.isAttached) headerBottom = root.localBoundingBoxOf(header).bottom
    }

    val measured = contentH > 0 && headerBottom > 0f && containerHeight > 0f
    val fullH = (handleH + contentH + navBottom).coerceAtMost(containerHeight - topGap)
    val peekH = (handleH + headerBottom + navBottom).coerceAtMost(fullH)
    val halfH = (containerHeight * 0.5f).coerceIn(peekH, fullH)

    LaunchedEffect(measured, fullH, peekH, halfH, containerHeight) {
        if (!measured) return@LaunchedEffect
        val anchors = DraggableAnchors {
            SheetStop.Hidden at containerHeight
            SheetStop.Peek at containerHeight - peekH
            if (halfH - peekH > minStep && fullH - halfH > minStep) SheetStop.Half at containerHeight - halfH
            SheetStop.Full at containerHeight - fullH
        }
        val target = state.targetValue.takeIf { anchors.hasPositionFor(it) } ?: SheetStop.Full
        state.updateAnchors(anchors, target)
    }

    // New content slides up to Half once it has been measured.
    LaunchedEffect(key) {
        snapshotFlow { state.anchors.hasPositionFor(SheetStop.Peek) }.first { it }
        state.animateTo(state.expandedStop(), SheetSpring)
    }

    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state) {
        var shown = false
        snapshotFlow { state.settledValue }.collect {
            if (it != SheetStop.Hidden) shown = true
            else if (shown) dismiss()
        }
    }

    val nested = remember(state, velocityThreshold) { SheetNestedScroll(state, velocityThreshold) }
    val scroll = rememberScrollState()
    LaunchedEffect(key) { scroll.scrollTo(0) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Until measured, take the full height off-screen so the content can lay out.
            .height(with(density) { (if (measured) fullH else containerHeight).toDp() })
            .offset { IntOffset(0, (state.offset.takeUnless { it.isNaN() } ?: containerHeight).roundToInt()) }
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Vertical,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state,
                    positionalThreshold = { it * 0.5f },
                    animationSpec = SheetSpring,
                ),
            ),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().onSizeChanged { handleH = it.height }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
            }
            Column(Modifier.weight(1f).nestedScroll(nested).verticalScroll(scroll)) {
                Box(
                    Modifier
                        .onSizeChanged { contentH = it.height }
                        .onGloballyPositioned { coords[0] = it; measureHeader() },
                ) {
                    content(Modifier.onGloballyPositioned { coords[1] = it; measureHeader() })
                }
                Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
