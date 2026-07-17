package com.example.silverageassistant.ui.family

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun FamilyContactsRoute(
    onBack: () -> Unit,
    viewModel: FamilyContactsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.syncContacts() }
    FamilyContactsScreen(
        state = state,
        onRefresh = viewModel::syncContacts,
        onCall = { mobileNumber ->
            context.startActivity(
                Intent(
                    Intent.ACTION_DIAL,
                    Uri.fromParts("tel", mobileNumber, null),
                ),
            )
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun FamilyContactsScreen(
    state: FamilyContactsUiState,
    onRefresh: () -> Unit,
    onCall: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "联系家人", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text(
                    text = "已绑定的家人",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (state.message != null) {
                item {
                    Surface(
                        color = if (state.isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(ElderSpacing.medium),
                        )
                    }
                }
            }
            item {
                LargeActionButton(
                    text = if (state.isSyncing) "正在同步" else "更新家属信息",
                    contentDescription = "从中台更新已绑定家属信息",
                    icon = Icons.Rounded.Refresh,
                    enabled = !state.isSyncing,
                    outlined = true,
                    onClick = onRefresh,
                )
            }
            if (!state.isLoadingLocal && state.contacts.isEmpty() && state.message == null) {
                item {
                    Text(
                        text = "暂时没有可联系的家人，请先完成绑定或检查网络。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(state.contacts, key = FamilyContactProfile::bindingId) { contact ->
                FamilyContactCard(contact = contact, onCall = { onCall(contact.mobileNumber) })
            }
            item { Spacer(modifier = Modifier.height(ElderSpacing.large)) }
        }
    }
}

@Composable
private fun FamilyContactCard(
    contact: FamilyContactProfile,
    onCall: () -> Unit,
) {
    val relationship = contact.relationship.toRelationshipLabel()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(text = contact.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(text = relationship, style = MaterialTheme.typography.bodyLarge)
                    if (contact.emergencyContact) {
                        Text(
                            text = "紧急联系人",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(ElderSpacing.medium))
            Text(
                text = contact.mobileNumber.toReadableMobile(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.medium))
            LargeActionButton(
                text = "给${relationship}打电话",
                contentDescription = "拨打${contact.displayName}的电话",
                icon = Icons.Rounded.Phone,
                onClick = onCall,
            )
        }
    }
}

private fun String.toRelationshipLabel(): String = when (uppercase()) {
    "CHILD" -> "子女"
    "RELATIVE" -> "其他亲属"
    "CAREGIVER" -> "照护人"
    else -> "家人"
}

private fun String.toReadableMobile(): String {
    val digits = filter(Char::isDigit)
    return if (digits.length == 11) {
        "${digits.take(3)} ${digits.substring(3, 7)} ${digits.takeLast(4)}"
    } else {
        this
    }
}
