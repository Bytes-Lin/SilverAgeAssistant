package com.example.silverageassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun LargeActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String = text,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    outlined: Boolean = false,
) {
    val buttonModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = ElderSpacing.largeButtonHeight)
        .semantics { this.contentDescription = contentDescription }
    val colors = if (isDestructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    val content: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null)
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            content = { content() },
        )
    } else {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = colors,
            content = { content() },
        )
    }
}
