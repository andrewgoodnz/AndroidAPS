package app.aaps.pump.ypso.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icon for the YpsoPump plugin.
 *
 * Bounding box: viewport 24x24, matching the other plugin icons in `core:ui`.
 *
 * This replaces `res/drawable/ic_ypso_pump_128.xml`, which 4.0.0 cannot use: [PluginDescription]
 * now takes a Compose `ImageVector` rather than a drawable resource id. The old drawable was a
 * detailed 128dp illustration with gradients, which is a different thing from these icons - they
 * are single-colour glyphs, drawn small in a list and tinted by the theme. So this is a plain
 * outline of the pump rather than a conversion of the artwork, which would not have survived being
 * reduced to one colour at list size. The original is still in git history on `ypso-integration`.
 */
val IcPluginYpso: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcPluginYpso",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Pump body: a tall rounded case.
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8.5f, 2f)
            lineTo(15.5f, 2f)
            curveTo(16.6f, 2f, 17.5f, 2.9f, 17.5f, 4f)
            lineTo(17.5f, 20f)
            curveTo(17.5f, 21.1f, 16.6f, 22f, 15.5f, 22f)
            lineTo(8.5f, 22f)
            curveTo(7.4f, 22f, 6.5f, 21.1f, 6.5f, 20f)
            lineTo(6.5f, 4f)
            curveTo(6.5f, 2.9f, 7.4f, 2f, 8.5f, 2f)
            close()
        }
        // Display.
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 5.5f)
            lineTo(15f, 5.5f)
            lineTo(15f, 11f)
            lineTo(9f, 11f)
            close()
        }
        // Control button below the display.
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 14f)
            curveTo(13.1f, 14f, 14f, 14.9f, 14f, 16f)
            curveTo(14f, 17.1f, 13.1f, 18f, 12f, 18f)
            curveTo(10.9f, 18f, 10f, 17.1f, 10f, 16f)
            curveTo(10f, 14.9f, 10.9f, 14f, 12f, 14f)
            close()
        }
    }.build()
}
