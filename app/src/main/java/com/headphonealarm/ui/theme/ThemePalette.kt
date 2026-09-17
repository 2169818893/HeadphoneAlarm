package com.headphonealarm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 主题风格定义：每套主题由「Material3 配色」+「背景画布（渐变/光斑/装饰）」两部分构成。
 *
 * 语义色统一走 MaterialTheme.colorScheme（primary/secondary/onSurface...），
 * 无法用 ColorScheme 表达的渐变、光斑、装饰动画则通过 [AppBackdrop] 经
 * [LocalAppBackdrop] 下发，界面层只依赖抽象、不关心具体是哪套主题。
 */
enum class AppTheme(val key: String, val label: String) {
    AURORA("aurora", "极光"),
    CYBER("cyber", "赛博朋克"),
    MATERIAL("material", "安卓原生"),
    ANIME("anime", "动漫"),
    OCEAN("ocean", "海洋"),
    FOREST("forest", "森林");

    companion object {
        /** 默认主题：安卓原生（Material You 浅色） */
        val DEFAULT = MATERIAL
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 背景装饰类型，控制 AppBackground 是否额外绘制动画层 */
enum class Decoration { NONE, GRID, BUBBLES, LEAVES, PETALS }

/** 单个径向光斑：圆心按宽/高比例定位，半径按短边比例 */
data class Glow(
    val cx: Float,
    val cy: Float,
    val r: Float,
    val color: Color
)

/** 主题背景画布（ColorScheme 承载不了的部分） */
data class AppBackdrop(
    val gradient: List<Color>,
    val glows: List<Glow>,
    val ringGradient: List<Color>,
    val decoration: Decoration,
    val isLight: Boolean
)

val LocalAppBackdrop = staticCompositionLocalOf { backdropFor(AppTheme.DEFAULT) }

// region 各主题画布
private val AuroraBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFF050814), Color(0xFF0A1024), Color(0xFF050814)),
    glows = listOf(
        Glow(0.12f, 0.06f, 1.05f, Color(0xFF4F8CFF).copy(alpha = 0.26f)),
        Glow(0.95f, 0.22f, 0.89f, Color(0xFFB490CA).copy(alpha = 0.20f)),
        Glow(0.50f, 1.02f, 1.05f, Color(0xFF5EE7DF).copy(alpha = 0.12f))
    ),
    ringGradient = listOf(Color(0xFF090E22), Color(0xFF141C3C), Color(0xFF090E22)),
    decoration = Decoration.NONE,
    isLight = false
)

private val CyberBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFF0A0118), Color(0xFF140026), Color(0xFF05010D)),
    glows = listOf(
        Glow(0.85f, 0.08f, 1.0f, Color(0xFFFF2DF9).copy(alpha = 0.30f)),
        Glow(0.10f, 0.92f, 1.05f, Color(0xFF00F2C3).copy(alpha = 0.20f))
    ),
    ringGradient = listOf(Color(0xFF0C0119), Color(0xFF1A0130), Color(0xFF0C0119)),
    decoration = Decoration.GRID,
    isLight = false
)

private val MaterialBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFFF7F3FF), Color(0xFFECE6F7), Color(0xFFF3EDF7)),
    glows = listOf(
        Glow(0.50f, -0.05f, 1.1f, Color(0xFF6750A4).copy(alpha = 0.10f))
    ),
    ringGradient = listOf(Color(0xFFF7F3FF), Color(0xFFECE6F7)),
    decoration = Decoration.NONE,
    isLight = true
)

private val AnimeBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFFFFE0EE), Color(0xFFE5EFFF), Color(0xFFFFF0F6)),
    glows = listOf(
        Glow(0.15f, 0.08f, 1.0f, Color(0xFFFFB7D5).copy(alpha = 0.50f)),
        Glow(0.90f, 0.25f, 1.0f, Color(0xFF818CF8).copy(alpha = 0.32f))
    ),
    ringGradient = listOf(Color(0xFFFFDCEC), Color(0xFFE6EFFF)),
    decoration = Decoration.PETALS,
    isLight = true
)

private val OceanBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFF01141F), Color(0xFF063049), Color(0xFF01141F)),
    glows = listOf(
        Glow(0.20f, 0.10f, 1.05f, Color(0xFF56A0FF).copy(alpha = 0.28f)),
        Glow(0.90f, 0.30f, 1.0f, Color(0xFF35E0C2).copy(alpha = 0.20f))
    ),
    ringGradient = listOf(Color(0xFF021A28), Color(0xFF073A55)),
    decoration = Decoration.BUBBLES,
    isLight = false
)

private val ForestBackdrop = AppBackdrop(
    gradient = listOf(Color(0xFF08130D), Color(0xFF123018), Color(0xFF08130D)),
    glows = listOf(
        Glow(0.18f, 0.08f, 1.05f, Color(0xFFE0B86E).copy(alpha = 0.22f)),
        Glow(0.88f, 0.26f, 1.05f, Color(0xFF56A878).copy(alpha = 0.30f))
    ),
    ringGradient = listOf(Color(0xFF0A1C11), Color(0xFF153A1E)),
    decoration = Decoration.LEAVES,
    isLight = false
)

fun backdropFor(theme: AppTheme): AppBackdrop = when (theme) {
    AppTheme.AURORA -> AuroraBackdrop
    AppTheme.CYBER -> CyberBackdrop
    AppTheme.MATERIAL -> MaterialBackdrop
    AppTheme.ANIME -> AnimeBackdrop
    AppTheme.OCEAN -> OceanBackdrop
    AppTheme.FOREST -> ForestBackdrop
}
// endregion

// region Material3 配色
fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.AURORA -> darkColorScheme(
        primary = Color(0xFF5EE7DF), onPrimary = Color(0xFF00201E),
        primaryContainer = Color(0xFF10403E), onPrimaryContainer = Color(0xFFB9FFF8),
        secondary = Color(0xFFB490CA), onSecondary = Color(0xFF20142E),
        secondaryContainer = Color(0xFF33224A), onSecondaryContainer = Color(0xFFEBDCFF),
        tertiary = Color(0xFF4F8CFF), onTertiary = Color(0xFF001A3D),
        background = Color(0xFF070B1A), onBackground = Color(0xFFE9EDFF),
        surface = Color(0xFF070B1A), onSurface = Color(0xFFE9EDFF),
        surfaceVariant = Color(0xFF172145), onSurfaceVariant = Color(0xFF97A3C6),
        outline = Color(0xFF2A3557), error = Color(0xFFFF6B7A), onError = Color(0xFF3A0009)
    )

    AppTheme.CYBER -> darkColorScheme(
        primary = Color(0xFF00F2C3), onPrimary = Color(0xFF001A15),
        primaryContainer = Color(0xFF0A3A33), onPrimaryContainer = Color(0xFFB6FFF2),
        secondary = Color(0xFFFF2DF9), onSecondary = Color(0xFF1C0018),
        secondaryContainer = Color(0xFF3D0140), onSecondaryContainer = Color(0xFFFFD6FE),
        tertiary = Color(0xFF9D4EFF), onTertiary = Color(0xFF12002A),
        background = Color(0xFF0A0118), onBackground = Color(0xFFE8FBFF),
        surface = Color(0xFF0A0118), onSurface = Color(0xFFE8FBFF),
        surfaceVariant = Color(0xFF1B3A44), onSurfaceVariant = Color(0xFF6FB3C4),
        outline = Color(0xFF00F2C3), error = Color(0xFFFF2E63), onError = Color(0xFF1C0011)
    )

    AppTheme.MATERIAL -> lightColorScheme(
        primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7C4DFF), onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
        surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E), error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF)
    )

    AppTheme.ANIME -> lightColorScheme(
        primary = Color(0xFFFF78A8), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD7E6), onPrimaryContainer = Color(0xFF3E0018),
        secondary = Color(0xFF818CF8), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDDE3FF), onSecondaryContainer = Color(0xFF14005B),
        tertiary = Color(0xFFFFAACD), onTertiary = Color(0xFF5B0030),
        background = Color(0xFFFFF0F6), onBackground = Color(0xFF5B4A68),
        surface = Color(0xFFFFF0F6), onSurface = Color(0xFF5B4A68),
        surfaceVariant = Color(0xFFFFDCEA), onSurfaceVariant = Color(0xFF9C8BAB),
        outline = Color(0xFFFFBDD6), error = Color(0xFFFF6B81), onError = Color(0xFFFFFFFF)
    )

    AppTheme.OCEAN -> darkColorScheme(
        primary = Color(0xFF35E0C2), onPrimary = Color(0xFF00252E),
        primaryContainer = Color(0xFF0A3F3A), onPrimaryContainer = Color(0xFFB4FFF2),
        secondary = Color(0xFF56A0FF), onSecondary = Color(0xFF04213D),
        secondaryContainer = Color(0xFF123A55), onSecondaryContainer = Color(0xFFCFE5FF),
        tertiary = Color(0xFF2DD4BF), onTertiary = Color(0xFF00252E),
        background = Color(0xFF01141F), onBackground = Color(0xFFE0F7FF),
        surface = Color(0xFF01141F), onSurface = Color(0xFFE0F7FF),
        surfaceVariant = Color(0xFF123A4A), onSurfaceVariant = Color(0xFF7FB0C4),
        outline = Color(0xFF123A4A), error = Color(0xFFFF7A8A), onError = Color(0xFF3A0009)
    )

    AppTheme.FOREST -> darkColorScheme(
        primary = Color(0xFF7EE08F), onPrimary = Color(0xFF06210F),
        primaryContainer = Color(0xFF173A22), onPrimaryContainer = Color(0xFFBDF5C6),
        secondary = Color(0xFFE0B86E), onSecondary = Color(0xFF2A1D05),
        secondaryContainer = Color(0xFF3D2E10), onSecondaryContainer = Color(0xFFF6E2B8),
        tertiary = Color(0xFF56A878), onTertiary = Color(0xFF06210F),
        background = Color(0xFF08130D), onBackground = Color(0xFFEEF8EE),
        surface = Color(0xFF08130D), onSurface = Color(0xFFEEF8EE),
        surfaceVariant = Color(0xFF1E3A26), onSurfaceVariant = Color(0xFF9BBB9F),
        outline = Color(0xFF1E3A26), error = Color(0xFFFF7D6B), onError = Color(0xFF3A0009)
    )
}
// endregion
