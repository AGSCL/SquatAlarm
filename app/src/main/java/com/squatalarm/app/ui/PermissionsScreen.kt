package com.squatalarm.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squatalarm.app.util.PermItem
import com.squatalarm.app.util.PermissionUtils

@Composable
fun PermissionsScreen(resumeTick: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val items = remember(resumeTick) { PermissionUtils.items(context) }

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
                    try {
                        context.startActivity(item.intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Abre Ajustes y busca: ${item.title}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (items.any { !it.granted && it.id in PermissionUtils.RESTRICTED }) {
                SectionCard("¿Te sale «A la app se le negó el acceso» o «Ajuste restringido»?") {
                    Text(
                        "Android bloquea estos permisos en las apps instaladas fuera de una tienda. No hay que pedirle nada a " +
                            "Google: se desbloquea aquí mismo, una sola vez.\n\n" +
                            "1. Toca «Conceder» en el permiso bloqueado y cierra el aviso (sin este intento, la opción del paso 2 no aparece).\n" +
                            "2. Toca «Abrir info de la app» → menú ⋮ (arriba a la derecha) → «Permitir ajustes restringidos» → confirma con tu PIN o huella.\n" +
                            "3. Vuelve aquí y toca «Conceder» de nuevo en cada permiso bloqueado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Abre Ajustes → Aplicaciones → Alarma Sentadillas", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Abrir info de la app") }
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
