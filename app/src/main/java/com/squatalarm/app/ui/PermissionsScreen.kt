package com.squatalarm.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squatalarm.app.util.PermItem
import com.squatalarm.app.util.PermissionUtils

@Composable
fun PermissionsScreen(resumeTick: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val items = remember(resumeTick, tick) { PermissionUtils.items(context) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("Permisos", onBack = onDone)
            Text(
                "Para que la alarma suene sobre la pantalla bloqueada, vuelva al frente y no se pueda apagar el teléfono, concede todo lo siguiente.",
                style = MaterialTheme.typography.bodyMedium,
            )
            items.forEach { item ->
                PermissionRow(item) {
                    val intent = item.intent
                    if (intent == null) {
                        launcher.launch(PermissionUtils.POST_NOTIFICATIONS)
                    } else {
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Abre Ajustes y busca: ${item.title}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(item: PermItem, onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (item.granted) "✓ " else "✗ ") + item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
            if (!item.granted) Button(onClick = onRequest) { Text("Conceder") }
        }
    }
}
