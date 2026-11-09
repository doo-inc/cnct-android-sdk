package ooo.doo.cnct.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few icons the chat needs, drawn here as strokes rather than pulled in from an icon library.
 * Tinted at the call site. The ones that point somewhere — send, the chevron — mirror themselves in
 * a right-to-left layout.
 */
internal object ChatIcons {
    private fun icon(name: String, autoMirror: Boolean = false, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float = 2f, pathBuilder: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
    }

    private fun ImageVector.Builder.fill(pathBuilder: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
    }

    /** A paper plane, pointing the way the line reads. */
    val Send: ImageVector by lazy {
        icon("send", autoMirror = true) {
            fill {
                moveTo(3.4f, 20.4f)
                lineTo(20.85f, 12.92f)
                curveTo(21.66f, 12.57f, 21.66f, 11.43f, 20.85f, 11.08f)
                lineTo(3.4f, 3.6f)
                curveTo(2.74f, 3.31f, 2.01f, 3.8f, 2.01f, 4.51f)
                lineTo(2f, 9.12f)
                curveTo(2f, 9.62f, 2.37f, 10.05f, 2.87f, 10.11f)
                lineTo(17f, 12f)
                lineTo(2.87f, 13.88f)
                curveTo(2.37f, 13.95f, 2f, 14.38f, 2f, 14.88f)
                lineTo(2.01f, 19.49f)
                curveTo(2.01f, 20.2f, 2.74f, 20.69f, 3.4f, 20.4f)
                close()
            }
        }
    }

    val Attach: ImageVector by lazy {
        icon("attach") {
            group(rotate = 45f, pivotX = 12f, pivotY = 12f) {
                stroke(1.8f) {
                    moveTo(15.5f, 6f)
                    verticalLineTo(15.5f)
                    arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.5f, 15.5f)
                    verticalLineTo(5f)
                    arcTo(2.25f, 2.25f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13f, 5f)
                    verticalLineTo(15f)
                    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11f, 15f)
                    verticalLineTo(7.5f)
                }
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("close") {
            stroke {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }
    }

    val Camera: ImageVector by lazy {
        icon("camera") {
            stroke(1.8f) {
                moveTo(4f, 7.5f)
                horizontalLineTo(7.5f)
                lineTo(9f, 5f)
                horizontalLineTo(15f)
                lineTo(16.5f, 7.5f)
                horizontalLineTo(20f)
                verticalLineTo(19f)
                horizontalLineTo(4f)
                close()
                moveTo(15.5f, 13f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8.5f, 13f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.5f, 13f)
                close()
            }
        }
    }

    val Photo: ImageVector by lazy {
        icon("photo") {
            stroke(1.8f) {
                moveTo(4f, 5f)
                horizontalLineTo(20f)
                verticalLineTo(19f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 16f)
                lineTo(9f, 11f)
                lineTo(13f, 15f)
                lineTo(15.5f, 12.5f)
                lineTo(20f, 17f)
                moveTo(16.5f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.1f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.5f, 8.5f)
            }
        }
    }

    val File: ImageVector by lazy {
        icon("file") {
            stroke(1.8f) {
                moveTo(14f, 3f)
                horizontalLineTo(6.5f)
                verticalLineTo(21f)
                horizontalLineTo(17.5f)
                verticalLineTo(6.5f)
                close()
                moveTo(14f, 3f)
                verticalLineTo(6.5f)
                horizontalLineTo(17.5f)
            }
        }
    }

    val Play: ImageVector by lazy {
        icon("play") {
            fill {
                moveTo(8f, 5.5f)
                lineTo(19f, 12f)
                lineTo(8f, 18.5f)
                close()
            }
        }
    }

    val Audio: ImageVector by lazy {
        icon("audio") {
            stroke(1.8f) {
                moveTo(9f, 18f)
                verticalLineTo(5f)
                lineTo(19f, 3f)
                verticalLineTo(16f)
                moveTo(9f, 18f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 18f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 18f)
                moveTo(19f, 16f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 16f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 16f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("check") {
            stroke {
                moveTo(5f, 12.5f)
                lineTo(10f, 17.5f)
                lineTo(19f, 7f)
            }
        }
    }

    /** Two ticks: "Seen". */
    val Seen: ImageVector by lazy {
        icon("seen") {
            stroke {
                moveTo(2f, 12.5f)
                lineTo(6.5f, 17f)
                lineTo(15f, 7f)
                moveTo(11.5f, 15f)
                lineTo(13.5f, 17f)
                lineTo(22f, 7f)
            }
        }
    }

    val Chevron: ImageVector by lazy {
        icon("chevron", autoMirror = true) {
            stroke {
                moveTo(9f, 6f)
                lineTo(15f, 12f)
                lineTo(9f, 18f)
            }
        }
    }

    /** The assistant's face: a spark. */
    val Spark: ImageVector by lazy {
        icon("spark") {
            fill {
                moveTo(12f, 2.5f)
                curveTo(12.6f, 7.8f, 14.2f, 9.4f, 19.5f, 10f)
                curveTo(14.2f, 10.6f, 12.6f, 12.2f, 12f, 17.5f)
                curveTo(11.4f, 12.2f, 9.8f, 10.6f, 4.5f, 10f)
                curveTo(9.8f, 9.4f, 11.4f, 7.8f, 12f, 2.5f)
                close()
                moveTo(18f, 15f)
                curveTo(18.25f, 17.2f, 18.8f, 17.75f, 21f, 18f)
                curveTo(18.8f, 18.25f, 18.25f, 18.8f, 18f, 21f)
                curveTo(17.75f, 18.8f, 17.2f, 18.25f, 15f, 18f)
                curveTo(17.2f, 17.75f, 17.75f, 17.2f, 18f, 15f)
                close()
            }
        }
    }
}
