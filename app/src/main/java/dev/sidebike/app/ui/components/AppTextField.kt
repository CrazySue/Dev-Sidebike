package dev.sidebike.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled

/**
 * 统一的 MD3 文本输入框（OutlinedTextField）。
 *
 * Robolectric 测试里 `OutlinedTextField` 聚焦后的光标闪烁/IME 是无限动画，Espresso 永远等不到 idle
 * （0.1.1-alpha 实测 AppNotIdleException）。因此测试模式（[LocalInfiniteAnimationsEnabled]=false）
 * 下渲染一个**带 SetText semantics 的静态文本**：既避免无限动画，又能让 `performTextInput` 驱动
 * onValueChange（测试照常覆盖输入逻辑）。生产 UI 仍为纯 MD3 OutlinedTextField。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val tagged = modifier
        .fillMaxWidth()
        .let { base -> if (testTag != null) base.testTag(testTag) else base }
    if (!LocalInfiniteAnimationsEnabled.current) {
        val shown = when {
            value.isNotEmpty() -> value
            else -> label ?: placeholder.orEmpty()
        }
        Text(
            text = shown,
            modifier = tagged.semantics {
                editableText = AnnotatedString(value)
                setText { newText ->
                    onValueChange(newText.text)
                    true
                }
                requestFocus { true }
                onClick { true }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = tagged,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.small,
    )
}
