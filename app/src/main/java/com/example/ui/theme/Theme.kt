package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = CyberBlue,
    secondary = CyberGreen,
    tertiary = GoldAccent,
    background = CarbonDark,
    surface = SlateSurface,
    onPrimary = CarbonDark,
    onSecondary = CarbonDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = CyberRed
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CyberBlue,
    secondary = CyberGreen,
    tertiary = GoldAccent,
    background = SlateBackground,
    surface = SlateSurface,
    onPrimary = CarbonDark,
    onSecondary = CarbonDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = CyberRed
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is disabled to preserve custom Editorial Theme styling
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
