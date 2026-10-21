package com.pocklet.companion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap?)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
            val icon = try {
                pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppItem(pkg, label, icon)
        }
        .sortedBy { it.label.lowercase() }
}

fun testHealth(host: String): String = try {
    val conn = (URL("http://$host:${LocalServer.PORT}/health").openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000
        readTimeout = 3000
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    "Balasan dari $host: $body"
} catch (e: Exception) {
    "Gagal: ${e.message}"
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<RequestLog.Entry>>(emptyList()) }
    var listenerOn by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var message by remember { mutableStateOf("") }

    val selectedFlow = remember { Prefs.selectedApps(ctx) }
    val modelFlow = remember { Prefs.model(ctx) }
    val autostartFlow = remember { Prefs.autostart(ctx) }
    val selected by selectedFlow.collectAsState(initial = emptySet())
    val model by modelFlow.collectAsState(initial = "")
    val autostart by autostartFlow.collectAsState(initial = false)

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ServerService.start(ctx) }

    LaunchedEffect(Unit) {
        val (key, tok) = withContext(Dispatchers.IO) { Prefs.apiKey(ctx) to Prefs.token(ctx) }
        apiKey = key
        token = tok
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            running = ServerService.running
            logs = RequestLog.snapshot()
            listenerOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            ip = NetUtil.lanIp()
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Pocklet Companion", style = MaterialTheme.typography.headlineSmall)
        }

        if (message.isNotBlank()) {
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }

        item {
            Section("Layanan") {
                Text(if (running) "Berjalan di port ${LocalServer.PORT}" else "Berhenti")
                ServerService.lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = {
                    when {
                        running -> ServerService.stop(ctx)
                        Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED ->
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> ServerService.start(ctx)
                    }
                }) {
                    Text(if (running) "Hentikan layanan" else "Mulai layanan")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autostart,
                        onCheckedChange = { v -> scope.launch { Prefs.setAutostart(ctx, v) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai otomatis setelah HP menyala")
                }
            }
        }

        item {
            Section("Akses notifikasi") {
                Text(
                    if (listenerOn) "Aktif"
                    else "Belum aktif. Aktifkan Pocklet Companion di daftar akses notifikasi."
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("Buka pengaturan akses")
                }
            }
        }

        item {
            Section("Baterai") {
                Text(
                    "Izinkan aplikasi berjalan tanpa dibatasi. Xiaomi/Poco: aktifkan Autostart " +
                        "dan atur Penghemat baterai ke Tanpa batasan. Samsung: matikan pembatasan " +
                        "di Perawatan perangkat > Baterai. Oppo/Realme: izinkan Auto-launch dan Latar belakang.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }) {
                    Text("Minta pengecualian baterai")
                }
            }
        }

        item {
            Section("Koneksi untuk ESP") {
                Text("Alamat HP: ${ip ?: "tidak ada WiFi"}:${LocalServer.PORT}")
                Text("Token (salin ke Config.h):", style = MaterialTheme.typography.bodySmall)
                Text(token.ifBlank { "..." }, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("token", token))
                        message = "Token disalin"
                    }) { Text("Salin") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            token = withContext(Dispatchers.IO) { Prefs.newToken(ctx) }
                            message = "Token baru dibuat"
                        }
                    }) { Text("Buat token baru") }
                }
                OutlinedButton(onClick = {
                    val host = ip
                    if (host == null) {
                        message = "Tidak ada IP WiFi"
                    } else {
                        scope.launch {
                            message = "Menguji..."
                            message = withContext(Dispatchers.IO) { testHealth(host) }
                        }
                    }
                }) { Text("Tes koneksi") }
            }
        }

        item {
            Section("Ollama") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key Ollama") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Prefs.setApiKey(ctx, apiKey)
                        message = "API key disimpan"
                    }) { Text("Simpan key") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            message = "Memuat model..."
                            val result = withContext(Dispatchers.IO) {
                                runCatching { Ollama.listModels(apiKey.trim()) }
                            }
                            result.onSuccess {
                                models = it
                                message = "${it.size} model ditemukan"
                            }.onFailure {
                                message = "Gagal memuat model: ${it.message}"
                            }
                        }
                    }) { Text("Muat model") }
                }
                Text("Model aktif: ${model.ifBlank { "belum dipilih" }}")
                models.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch { Prefs.setModel(ctx, m) }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = m == model, onClick = null)
                        Text(m)
                    }
                }
            }
        }

        item {
            Section("Log 20 request terakhir (tanpa isi)") {
                if (logs.isEmpty()) Text("Belum ada request")
                logs.forEach {
                    Text(
                        "${it.time}  ${it.line}  ${it.status}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text("Aplikasi notifikasi yang diteruskan", style = MaterialTheme.typography.titleMedium)
        }

        if (apps.isEmpty()) {
            item { Text("Memuat daftar aplikasi...") }
        }

        items(apps, key = { it.pkg }) { app ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    val newSet = if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                    scope.launch { Prefs.setSelectedApps(ctx, newSet) }
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                app.icon?.let {
                    Image(bitmap = it, contentDescription = null, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(checked = app.pkg in selected, onCheckedChange = null)
            }
        }
    }
}
