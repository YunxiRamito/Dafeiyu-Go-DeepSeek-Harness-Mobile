package com.dafeiyu.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.dafeiyu.mobile.DafeiyuApp
import com.dafeiyu.mobile.data.DemoRepository

@Preview(name = "Liquid Glass · 浅色", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ChatLightPreview() = PreviewApp()

@Preview(name = "Liquid Glass · 深色", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ChatDarkPreview() = PreviewApp("dark")

@Preview(name = "对话 · 短屏大字体", showBackground = true, widthDp = 320, heightDp = 568, fontScale = 1.5f)
@Composable
private fun ChatCompactPreview() = PreviewApp()

@Composable
private fun PreviewApp(theme: String = "light") {
    val repository = remember { DemoRepository().apply { themeMode = theme } }
    DafeiyuApp(repository)
}
