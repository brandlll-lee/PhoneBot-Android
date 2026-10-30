package dev.phonebot.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebot.android.engine.EngineClient
import dev.phonebot.android.engine.EnginePhase

class MainActivity : ComponentActivity() {
    private lateinit var engine: EngineClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = EngineClient(applicationContext)
        engine.attach()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B54), secondary = Color(0xFF765126))) {
                val state by engine.state.collectAsStateWithLifecycle()
                var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
                var model by remember { mutableStateOf("") }
                var key by remember { mutableStateOf("") }
                Scaffold { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("PhoneBot", style = MaterialTheme.typography.headlineMedium)
                        Text(when (state.phase) {
                            EnginePhase.STOPPED -> "Engine stopped"
                            EnginePhase.STARTING -> "Engine starting"
                            EnginePhase.READY -> "Engine ready"
                            EnginePhase.RUNNING -> "Verification running"
                            EnginePhase.STOPPING -> "Engine stopping"
                            EnginePhase.FAILED -> "Engine failed"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text("App PID ${Process.myPid()} / Engine PID ${state.pid}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { requestNotifications(); engine.start() }, enabled = state.canStart) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text("Start")
                            }
                            OutlinedButton(onClick = engine::stop, enabled = state.canStop) { Text("Stop") }
                        }
                        OutlinedButton(onClick = engine::probe, enabled = state.canRun) { Text("Run self-check") }
                        HorizontalDivider()
                        Text("Model verification", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("API base URL") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
                        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation())
                        Button(onClick = { engine.verify(baseUrl.trim(), model.trim(), key.trim()) },
                            enabled = state.canRun && baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank()) { Text("Verify model") }
                        if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onDestroy() {
        engine.detach()
        super.onDestroy()
    }
}
