package com.example.silverageassistant.ui.role

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Elderly
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme

@Composable
fun RoleSelectionScreen(
    onElderSelected: () -> Unit,
    onFamilySelected: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ElderSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "银龄助手",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(ElderSpacing.medium))
            Text(
                text = "请选择谁来使用这部手机",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.extraLarge))
            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                Text(
                    text = "正在恢复登录状态",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
            } else {
                LargeActionButton(
                    text = "给老人使用",
                    contentDescription = "进入老人模式",
                    icon = Icons.Rounded.Elderly,
                    onClick = onElderSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                LargeActionButton(
                    text = "我是家属",
                    contentDescription = "进入家属模式",
                    icon = Icons.Rounded.Groups,
                    onClick = onFamilySelected,
                    outlined = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(ElderSpacing.large))
                Text(
                    text = "以后可以在设置中更改",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 800)
@Composable
private fun RoleSelectionPreview() {
    SilverAgeAssistantTheme(darkTheme = false) {
        RoleSelectionScreen(onElderSelected = {}, onFamilySelected = {})
    }
}
