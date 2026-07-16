package com.example.silverageassistant.ui.family

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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

private data class FamilyContactUi(val name: String, val relation: String)

@Composable
fun FamilyContactsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts = listOf(
        FamilyContactUi(name = "小林", relation = "女儿"),
        FamilyContactUi(name = "小周", relation = "儿子"),
    )
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }

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
                    text = "选择要联系的家人",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (selectedName != null) {
                item {
                    Text(
                        text = "当前是界面演示，尚未拨打给${selectedName}。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(contacts, key = { it.name }) { contact ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text(text = contact.name, style = MaterialTheme.typography.titleLarge)
                                Text(text = contact.relation, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Spacer(modifier = Modifier.height(ElderSpacing.medium))
                        LargeActionButton(
                            text = "给${contact.relation}打电话",
                            contentDescription = "给${contact.name}打电话",
                            icon = Icons.Rounded.Phone,
                            onClick = { selectedName = contact.name },
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(ElderSpacing.large)) }
        }
    }
}
