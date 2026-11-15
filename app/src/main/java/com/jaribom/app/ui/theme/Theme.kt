package com.jaribom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Pleos 기본 앱(차량 설정 등)과 같은 밝은 화면: 흰 바탕, 검은 글자, 검은 선택 상태, 초록 포인트.
 * 대비 4.5:1 이상(Pleos 디자인 가이드 '색상 및 대비').
 */
private val Scheme = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    secondary = Color(0xFF5F6368),
    tertiary = Color(0xFF00A651),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFD0D3D8),
    error = Color(0xFFD32F2F),
)

@Composable
fun JariBomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}