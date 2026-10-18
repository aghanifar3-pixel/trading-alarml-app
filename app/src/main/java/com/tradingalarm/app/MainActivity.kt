package com.tradingalarm.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.graphics.Paint as AndroidPaint
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import android.app.Service
import android.os.IBinder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PREFS_NAME = "trading_alarm_secure_prefs"
private const val API_KEY_PREF = "encrypted_twelvedata_api_key"
private const val KEY_ALIAS = "TradingAlarmTwelveDataAesKey"
private const val SIGNAL_PREFS = "trading_alarm_signal_state"
private const val LAST_SIGNAL_TIME_KEY = "last_signal_activation_time"
private const val SIGNAL_BASELINE_SET_KEY = "signal_baseline_set"
private const val NOTIFICATION_PREFS = "trading_alarm_notification_settings"
private const val NOTIFICATION_SOUND_URI_KEY = "notification_sound_uri"
private const val NOTIFICATION_CHANNEL_PREFIX = "trading_alarm_signals"
private const val M5_MS = 5L * 60L * 1000L
private const val M15_MS = 15L * 60L * 1000L
private const val H1_MS = 60L * 60L * 1000L

private const val EMA6_LENGTH = 6
private const val EMA11_LENGTH = 11
private const val WVF_PD = 50
private const val WVF_BBL = 20
private const val WVF_MULT = 5.0
private const val WVF_LOOKBACK = 50
private const val WVF_PERCENTILE = 0.90
private const val MSB_ZIGZAG_LENGTH = 9
private const val MSB_FIB_FACTOR = 0.33
private const val M15_SEARCH_BACK_BARS = 500
private const val PAST_SIGNALS_TO_SHOW = 20

private data class Candle(
    val time: String,
    val timeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

private data class CandleFetchResult(
    val candles: List<Candle>,
    val rawCount: Int,
    val removedOutsideForexSession: Int,
    val removedUnclosed: Int,
    val requestedCount: Int
)

private data class H1WilliamsState(
    val active: Boolean,
    val greenStart: Boolean,
    val redStart: Boolean
)

private data class M5MsbEvent(
    val direction: Int,
    val level: Double,
    val startTime: Long,
    val endTime: Long,
    val labelTime: Long,
    val eventTime: Long
)

private data class StrategyRectangle(
    val direction: Int,
    val leftIndex: Int,
    val activationIndex: Int,
    var rightIndex: Int,
    val top: Double,
    val bottom: Double,
    val activationTime: Long,
    var armed: Boolean,
    var touched: Boolean
)

private data class StrategySignal(
    val direction: Int,
    val activationTime: Long,
    val leftTime: Long,
    val top: Double,
    val bottom: Double
)

private data class ChartLine(
    val title: String,
    val colorArgb: Long,
    val values: List<Double?>,
    val widthDp: Float = 1.5f
)

private data class StrategyAnalysis(
    val h1Candles: List<Candle>,
    val m15Candles: List<Candle>,
    val m5Candles: List<Candle>,
    val rectangles: List<StrategyRectangle>,
    val msbLines: List<M5MsbEvent>,
    val emaLines: List<ChartLine>,
    val latestSignal: StrategySignal?,
    val gateReady: Boolean,
    val pendingDirection: Int,
    val latestM5Direction: Int,
    val h1WilliamsEvents: Int,
    val status: String
)

private data class PairAnalysisResult(
    val symbol: String,
    val analysis: StrategyAnalysis?,
    val error: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this, loadSavedNotificationSoundUri(this))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
        }
        setContent { TradingAlarmScreen() }
    }
}

@Composable
private fun TradingAlarmScreen() {
    val context = LocalContext.current.applicationContext
    var apiKey by remember { mutableStateOf("") }
    var apiKeyLoaded by remember { mutableStateOf(false) }
    var audAnalysis by remember { mutableStateOf<StrategyAnalysis?>(null) }
    var nzdAnalysis by remember { mutableStateOf<StrategyAnalysis?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("کلید API را بارگذاری یا وارد کن؛ سپس «بررسی استراتژی» را بزن.") }
    var lastSuccess by remember { mutableStateOf(false) }
    var notificationSoundUri by remember {
        mutableStateOf(loadSavedNotificationSoundUri(context))
    }
    var notificationSoundLabel by remember {
        mutableStateOf(resolveNotificationSoundLabel(context, loadSavedNotificationSoundUri(context)))
    }
    val scope = rememberCoroutineScope()

    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val pickedValue = pickedUri?.toString()
            saveNotificationSoundUri(context, pickedValue)
            notificationSoundUri = pickedValue
            notificationSoundLabel = resolveNotificationSoundLabel(context, pickedValue)
            createNotificationChannel(context, pickedValue)
            status = "صدای اعلان Trading Alarm تغییر کرد: $notificationSoundLabel"
        }
    }

    fun openNotificationSoundPicker() {
        val existingUri = notificationSoundUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای اختصاصی Trading Alarm")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        soundPickerLauncher.launch(intent)
    }

    fun resetNotificationSound() {
        saveNotificationSoundUri(context, null)
        notificationSoundUri = null
        notificationSoundLabel = "صدای پیش‌فرض اندروید"
        createNotificationChannel(context, null)
        status = "صدای اعلان به صدای پیش‌فرض اندروید برگشت."
    }

    fun testNotificationSound() {
        playNotificationSound(context, notificationSoundUri)
    }

    LaunchedEffect(Unit) {
        try {
            apiKey = withContext(Dispatchers.IO) { loadSavedApiKey(context) }
        } catch (_: Exception) {
            apiKey = ""
        } finally {
            apiKeyLoaded = true
            if (apiKey.isNotBlank()) status = "کلید ذخیره‌شده بارگذاری شد. آماده بررسی AUD/USD و NZD/USD هستیم."
        }
    }

    fun runStrategyCheck() {
        if (!apiKeyLoaded) {
            status = "در حال بارگذاری کلید ذخیره‌شده؛ کمی صبر کن."
            return
        }
        if (apiKey.isBlank()) {
            status = "ابتدا API Key از Twelve Data را وارد کن."
            lastSuccess = false
            return
        }
        if (isLoading) return

        isLoading = true
        audAnalysis = null
        nzdAnalysis = null
        lastSuccess = false
        status = "در حال دریافت H1، M15 و M5 برای AUD/USD و NZD/USD و بررسی استراتژی..."
        val apiKeyForRequest = apiKey.trim()

        scope.launch {
            try {
                val pairResults = withContext(Dispatchers.IO) {
                    // Each pair is processed independently so an issue with one symbol
                    // does not prevent the other chart from being shown.
                    listOf(
                        analyzePairForSymbol(apiKeyForRequest, "AUD/USD"),
                        analyzePairForSymbol(apiKeyForRequest, "NZD/USD")
                    )
                }

                val successfulPairs = pairResults.filter { it.analysis != null }
                if (successfulPairs.isEmpty()) {
                    throw IOException(
                        pairResults.joinToString(" | ") { "${it.symbol}: ${it.error ?: "داده‌ای دریافت نشد"}" }
                    )
                }

                withContext(Dispatchers.IO) {
                    saveApiKeySecurely(context, apiKeyForRequest)
                }

                audAnalysis = pairResults.firstOrNull { it.symbol == "AUD/USD" }?.analysis
                nzdAnalysis = pairResults.firstOrNull { it.symbol == "NZD/USD" }?.analysis
                lastSuccess = true

                val notificationMessages = mutableListOf<String>()
                for (pairResult in successfulPairs) {
                    val pairData = pairResult.analysis ?: continue
                    val signal = pairData.latestSignal
                    val latestClosedTime = pairData.m15Candles.lastOrNull()?.timeMillis
                        ?: System.currentTimeMillis()
                    val isNewSignal = withContext(Dispatchers.IO) {
                        registerNewSignalIfNeeded(
                            context,
                            pairResult.symbol,
                            signal,
                            latestClosedTime
                        )
                    }
                    if (isNewSignal && signal != null) {
                        sendSignalNotification(context, signal, pairResult.symbol)
                        notificationMessages.add("${pairResult.symbol}: آلارم ${directionName(signal.direction)}")
                    }
                }

                status = buildString {
                    pairResults.forEachIndexed { index, pairResult ->
                        if (index > 0) append("\n")
                        val pairData = pairResult.analysis
                        if (pairData != null) {
                            append("${pairResult.symbol}: H1=${pairData.h1Candles.size}، M15=${pairData.m15Candles.size}، M5=${pairData.m5Candles.size} کندل؛ ")
                            append(pairData.status)
                        } else {
                            append("${pairResult.symbol}: دریافت ناموفق بود؛ ${pairResult.error ?: "خطای نامشخص"}")
                        }
                    }
                    if (notificationMessages.isNotEmpty()) {
                        append("\n")
                        append(notificationMessages.joinToString("؛ "))
                    }
                }
            }
                            try {
                    val monitorIntent = Intent(
                        context,
                        TradingAlarmService::class.java
                    )

                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O
                    ) {
                        context.startForegroundService(
                            monitorIntent
                        )
                    } else {
                        context.startService(
                            monitorIntent
                        )
                    }

                    status += "\nمانیتورینگ پس‌زمینه راه‌اندازی شد."

                } catch (e: Exception) {
                    status += "\nشروع مانیتورینگ ناموفق بود: ${
                        e.message ?: "خطای نامشخص"
                    }"
                }
            catch (e: Exception) {
                audAnalysis = null
                nzdAnalysis = null
                lastSuccess = false
                status = "بررسی استراتژی ناموفق بود: ${e.message ?: "خطای نامشخص"}"
            } finally {
                isLoading = false
            }
        }
    }

    val scheme = darkColorScheme(
        primary = Color(0xFF80CBC4),
        secondary = Color(0xFF90CAF9),
        background = Color(0xFF0D131B),
        surface = Color(0xFF17212D),
        onBackground = Color(0xFFEAF0F6),
        onSurface = Color(0xFFEAF0F6),
        outline = Color(0xFF3B4857),
        tertiary = Color(0xFF80CBC4)
    )

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Resource-independent in-app brand tile. This avoids
                            // referencing R.mipmap.ic_launcher when the project has
                            // not added launcher resources yet.
                            Box(
                                modifier = Modifier
                                    .size(108.dp)
                                    .background(
                                        color = Color(0xFF101B28),
                                        shape = RoundedCornerShape(24.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "🔔",
                                        style = MaterialTheme.typography.displayMedium
                                    )
                                    Text(
                                        text = "TA",
                                        color = Color(0xFF80CBC4),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    "Trading Alarm",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Williams Gate + EMA 6/11 + M15 Rectangle + M5 MSB",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "AUD/USD · NZD/USD",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Text(
                        "منطق استراتژی برای AUD/USD و NZD/USD جداگانه اجرا می‌شود. چارت‌ها پایین صفحه به‌صورت مستقل نمایش داده می‌شوند؛ اپ معامله باز نمی‌کند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKeyLoaded && !isLoading,
                        label = { Text("کلید API از Twelve Data") },
                        placeholder = { Text("کلید را اینجا وارد کن") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(if (apiKeyLoaded && apiKey.isNotBlank()) "کلید ذخیره‌شده بارگذاری شده است." else "بعد از بررسی موفق، کلید به‌صورت رمزگذاری‌شده روی همین گوشی ذخیره می‌شود.")
                        }
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "صدای اختصاصی اعلان",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "صدای فعلی: $notificationSoundLabel",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { openNotificationSoundPicker() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("انتخاب صدا")
                                }
                                OutlinedButton(
                                    onClick = { resetNotificationSound() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("صدای پیش‌فرض")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { testNotificationSound() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("پخش صدا")
                                }
                                OutlinedButton(
                                    onClick = { sendTestNotification(context, notificationSoundUri) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("تست اعلان")
                                }
                            }
                            Text(
                                "نمونه متن آلارم:\nBUY LIMIT NZDUSD AT >>>> 0.65523\nSL >>>> 0.65322",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                            )
                            Text(
                                "برای شنیدن صدای متفاوت، از انتخاب صدا استفاده کن؛ اگر صدای دلخواهت در فهرست نیست، آن را ابتدا در پوشه Ringtones گوشی قرار بده.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            )
                        }
                    }

                    Button(
                        onClick = { runStrategyCheck() },
                        enabled = apiKeyLoaded && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text("در حال بررسی داده‌های هر دو نماد...")
                        } else Text("بررسی هر دو استراتژی و به‌روزرسانی چارت‌ها")
                    }

                    Text(
                        status,
                        color = when {
                            isLoading -> MaterialTheme.colorScheme.secondary
                            lastSuccess -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    audAnalysis?.let { data ->
                        key("AUD/USD") {
                            PairAnalysisCard(symbol = "AUD/USD", data = data)
                        }
                    }

                    nzdAnalysis?.let { data ->
                        key("NZD/USD") {
                            PairAnalysisCard(symbol = "NZD/USD", data = data)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PairAnalysisCard(
    symbol: String,
    data: StrategyAnalysis
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("وضعیت استراتژی · $symbol", style = MaterialTheme.typography.titleMedium)
                Text("Williams Gate: ${if (data.gateReady) "فعال" else "غیرفعال"}")
                Text("H1 در انتظار: ${directionName(data.pendingDirection)}")
                Text("آخرین M5 MSB: ${directionName(data.latestM5Direction)}")
                Text("Williams H1 events در داده دریافت‌شده: ${data.h1WilliamsEvents}")
                Text("سیگنال آخر: ${data.latestSignal?.let { directionName(it.direction) + " · " + formatUtc(it.activationTime) } ?: "هنوز مستطیل جدیدی در داده‌های فعلی پیدا نشد"}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$symbol · M15", style = MaterialTheme.typography.titleMedium)
                    Text("${data.m15Candles.size} کندل", color = MaterialTheme.colorScheme.secondary)
                }

                data.m15Candles.lastOrNull()?.let { last ->
                    Text("آخرین Close: ${formatPrice(last.close)}", style = MaterialTheme.typography.titleLarge)
                    Text("زمان آخرین کندل UTC: ${last.time}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }

                CandleChart(
                    candles = data.m15Candles,
                    emaLines = data.emaLines,
                    rectangles = data.rectangles,
                    msbLines = data.msbLines,
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
                Text(
                    "${data.rectangles.size} مستطیل در تاریخچه تحلیل‌شده پیدا شد (حداکثر نگهداری: $PAST_SIGNALS_TO_SHOW). از کنترل‌های زیر نمودار برای جابه‌جایی و بزرگ‌نمایی تاریخچه استفاده کن.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}

private fun analyzePairForSymbol(
    apiKey: String,
    symbol: String
): PairAnalysisResult {
    return try {
        val h1 = fetchCandles(apiKey, symbol, "1h", 500)
        val m15 = fetchCandles(apiKey, symbol, "15min", 3000)
        val m5 = fetchCandles(apiKey, symbol, "5min", 5000)
        PairAnalysisResult(
            symbol = symbol,
            analysis = analyzeStrategy(h1.candles, m15.candles, m5.candles)
        )
    } catch (e: Exception) {
        PairAnalysisResult(
            symbol = symbol,
            analysis = null,
            error = e.message ?: "خطای نامشخص"
        )
    }
}

private fun analyzeStrategy(
    h1: List<Candle>,
    m15: List<Candle>,
    m5: List<Candle>
): StrategyAnalysis {
    require(h1.size >= 60) { "داده H1 کافی نیست؛ حداقل ۶۰ کندل لازم است." }
    require(m15.size >= 30) { "داده M15 کافی نیست؛ حداقل ۳۰ کندل لازم است." }
    require(m5.size >= 30) { "داده M5 کافی نیست؛ حداقل ۳۰ کندل لازم است." }

    val h1Closes = h1.map { it.close }
    val h1Ema6 = ema(h1Closes, EMA6_LENGTH)
    val h1Williams = calculateH1Williams(h1)
    val m15Closes = m15.map { it.close }
    val m15Ema6 = ema(m15Closes, EMA6_LENGTH)
    val m15Ema11 = ema(m15Closes, EMA11_LENGTH)
    val ema250 = ema(m15Closes, 250)
    val m5EventsByTime = calculateM5MsbEvents(m5, MSB_ZIGZAG_LENGTH, MSB_FIB_FACTOR)

    val rectangles = mutableListOf<StrategyRectangle>()
    val drawnMsbLines = mutableListOf<M5MsbEvent>()
    val bullBreak = BooleanArray(m15.size)
    val bearBreak = BooleanArray(m15.size)
    val ema6Workflow = BooleanArray(m15.size)

    var williamsGateReady = false
    var pendingH1Direction = 0
    var williamsGateStartIndex: Int? = null
    var lastWilliamsH1BarTime: Long? = null
    var lastM5MSBDirection = 0
    var h1WilliamsEvents = 0
    var h1Cursor = -1
    var previousHourBucket = Long.MIN_VALUE
    var latestSignal: StrategySignal? = null

    for (i in m15.indices) {
        val bar = m15[i]
        while (h1Cursor + 1 < h1.size && h1[h1Cursor + 1].timeMillis + H1_MS <= bar.timeMillis) {
            h1Cursor++
        }

        val hourBucket = bar.timeMillis / H1_MS
        val newH1Period = hourBucket != previousHourBucket
        previousHourBucket = hourBucket

        var completedH1Index = h1Cursor
        if (completedH1Index !in h1.indices) completedH1Index = -1

        var justHadWilliamsEvent = false
        if (newH1Period && completedH1Index >= 0) {
            val wi = h1Williams[completedH1Index]
            justHadWilliamsEvent = wi.greenStart || wi.redStart
            if (justHadWilliamsEvent) h1WilliamsEvents++

            if (pendingH1Direction == 0) {
                if (wi.active) {
                    if (!williamsGateReady) {
                        williamsGateStartIndex = i
                        lastM5MSBDirection = 0
                    }
                    williamsGateReady = true
                    lastWilliamsH1BarTime = h1[completedH1Index].timeMillis
                } else if (williamsGateReady && lastWilliamsH1BarTime != null) {
                    val elapsed = h1[completedH1Index].timeMillis - lastWilliamsH1BarTime!!
                    if (elapsed > H1_MS) {
                        williamsGateReady = false
                        williamsGateStartIndex = null
                        lastWilliamsH1BarTime = null
                        lastM5MSBDirection = 0
                    }
                }
            }
        }

        // In the Pine script, workflow activity is evaluated before the H1 break
        // is consumed on this M15 bar. Preserve that ordering.
        val workflowActiveThisBar = williamsGateReady || pendingH1Direction != 0
        ema6Workflow[i] = workflowActiveThisBar

        var h1BuySetupEvent = false
        var h1SellSetupEvent = false
        if (newH1Period && completedH1Index >= 1 && williamsGateReady && pendingH1Direction == 0) {
            val k = completedH1Index
            val previousEma = h1Ema6[k - 1]
            val currentEma = h1Ema6[k]
            val hb = h1[k]
            h1BuySetupEvent = hb.open < previousEma && hb.close > currentEma && hb.close > hb.open
            h1SellSetupEvent = hb.open > previousEma && hb.close < currentEma && hb.close < hb.open
            when {
                h1BuySetupEvent -> {
                    pendingH1Direction = 1
                    williamsGateReady = false
                }
                h1SellSetupEvent -> {
                    pendingH1Direction = -1
                    williamsGateReady = false
                }
            }
        }

        // Confirmed M15 body must cross both EMA 6 and EMA 11 in the same direction.
        if (workflowActiveThisBar && i >= 1) {
            val current = m15[i]
            bullBreak[i] = current.open < m15Ema6[i - 1] &&
                current.close > m15Ema6[i] &&
                current.open < m15Ema11[i - 1] &&
                current.close > m15Ema11[i] &&
                current.close > current.open

            bearBreak[i] = current.open > m15Ema6[i - 1] &&
                current.close < m15Ema6[i] &&
                current.open > m15Ema11[i - 1] &&
                current.close < m15Ema11[i] &&
                current.close < current.open
        }

        if (workflowActiveThisBar) {
            val m15Start = bar.timeMillis
            val m15End = m15Start + M15_MS
            m5EventsByTime.forEach { (eventTime, events) ->
                if (eventTime >= m15Start && eventTime < m15End) {
                    for (event in events) {
                        lastM5MSBDirection = event.direction
                        drawnMsbLines.add(event)
                    }
                }
            }
        }

        val directionsMatch = pendingH1Direction != 0 &&
            lastM5MSBDirection != 0 &&
            pendingH1Direction == lastM5MSBDirection

        if (directionsMatch) {
            val direction = pendingH1Direction
            val gateIndex = williamsGateStartIndex ?: i
            val searchLimit = min(M15_SEARCH_BACK_BARS, max(i - gateIndex, 0))
            var foundIndex: Int? = null
            var offset = 0
            while (offset <= searchLimit && foundIndex == null) {
                val candidate = i - offset
                if (candidate >= 0) {
                    val matches = if (direction == 1) bullBreak[candidate] else bearBreak[candidate]
                    if (matches) foundIndex = candidate
                }
                offset++
            }

            if (foundIndex != null) {
                val leftIndex = foundIndex
                val trigger = m15[leftIndex]
                val outsideAtActivation = bar.open > trigger.high || bar.open < trigger.low
                val rectangle = StrategyRectangle(
                    direction = direction,
                    leftIndex = leftIndex,
                    activationIndex = i,
                    rightIndex = i,
                    top = trigger.high,
                    bottom = trigger.low,
                    activationTime = bar.timeMillis,
                    armed = outsideAtActivation,
                    touched = false
                )
                rectangles.add(rectangle)
                latestSignal = StrategySignal(
                    direction = direction,
                    activationTime = bar.timeMillis,
                    leftTime = trigger.timeMillis,
                    top = trigger.high,
                    bottom = trigger.low
                )
                while (rectangles.size > PAST_SIGNALS_TO_SHOW) rectangles.removeAt(0)

                // One rectangle consumes this Williams-gated setup cycle.
                pendingH1Direction = 0
                williamsGateReady = false
                williamsGateStartIndex = null
                lastWilliamsH1BarTime = null
            }
        }

        // Pine section 12: extend each rectangle until its first retest after arming.
        for (rectangle in rectangles) {
            if (rectangle.touched) continue
            val overlaps = bar.high >= rectangle.bottom && bar.low <= rectangle.top
            if (i == rectangle.activationIndex) {
                if (rectangle.armed && overlaps) {
                    rectangle.rightIndex = i
                    rectangle.touched = true
                } else if (!rectangle.armed && (bar.close > rectangle.top || bar.close < rectangle.bottom)) {
                    rectangle.armed = true
                }
            } else if (i > rectangle.activationIndex) {
                rectangle.rightIndex = i
                if (!rectangle.armed) {
                    if (bar.close > rectangle.top || bar.close < rectangle.bottom) rectangle.armed = true
                } else if (overlaps) {
                    rectangle.touched = true
                }
            }
        }
    }

    val emaLines = listOf(
        ChartLine("EMA 6", 0xFF40E0D0, m15Ema6.mapIndexed { index, value -> if (ema6Workflow[index]) value else null }, 2.2f),
        ChartLine("EMA 250", 0xFFFF0000, ema250.map { it as Double? }, 2.0f)
    )

    val lastWilliamsTime = lastWilliamsH1BarTime
    val stateText = buildString {
        append("Williams Gate ")
        append(if (williamsGateReady) "فعال" else "غیرفعال")
        append("؛ H1 در انتظار: ${directionName(pendingH1Direction)}")
        append("؛ آخرین M5 MSB: ${directionName(lastM5MSBDirection)}")
        if (lastWilliamsTime != null) append("؛ آخرین H1 Williams: ${formatUtc(lastWilliamsTime)}")
        if (justNoRectangleHint(rectangles, latestSignal)) append("؛ هنوز مستطیل معتبر تازه‌ای در بازه دریافت‌شده ساخته نشده است")
    }

    return StrategyAnalysis(
        h1Candles = h1,
        m15Candles = m15,
        m5Candles = m5,
        rectangles = rectangles.toList(),
        msbLines = drawnMsbLines.takeLast(120),
        emaLines = emaLines,
        latestSignal = latestSignal,
        gateReady = williamsGateReady,
        pendingDirection = pendingH1Direction,
        latestM5Direction = lastM5MSBDirection,
        h1WilliamsEvents = h1WilliamsEvents,
        status = stateText
    )
}

private fun justNoRectangleHint(
    rectangles: List<StrategyRectangle>,
    latestSignal: StrategySignal?
): Boolean = rectangles.isEmpty() && latestSignal == null

private fun calculateH1Williams(candles: List<Candle>): List<H1WilliamsState> {
    val n = candles.size
    val bottom = DoubleArray(n) { Double.NaN }
    val top = DoubleArray(n) { Double.NaN }

    for (i in candles.indices) {
        val highClose = rollingExtreme(candles.map { it.close }, i, WVF_PD, true)
        val lowClose = rollingExtreme(candles.map { it.close }, i, WVF_PD, false)
        if (highClose != null && highClose != 0.0) bottom[i] = ((highClose - candles[i].low) / highClose) * 100.0
        if (lowClose != null && lowClose != 0.0) top[i] = ((lowClose - candles[i].high) / lowClose) * 100.0
    }

    val bottomActive = BooleanArray(n)
    val topActive = BooleanArray(n)
    for (i in 0 until n) {
        val bMean = rollingSma(bottom, i, WVF_BBL)
        val bStd = rollingStdev(bottom, i, WVF_BBL)
        val bRange = rollingArrayExtreme(bottom, i, WVF_LOOKBACK, true)
        val bUpper = if (bMean != null && bStd != null) bMean + WVF_MULT * bStd else null
        val b = (bUpper != null && bottom[i].isFinite() && bottom[i] >= bUpper) ||
            (bRange != null && bottom[i].isFinite() && bottom[i] >= bRange * WVF_PERCENTILE)

        val tMean = rollingSma(top, i, WVF_BBL)
        val tStd = rollingStdev(top, i, WVF_BBL)
        val tRange = rollingArrayExtreme(top, i, WVF_LOOKBACK, false)
        val tLower = if (tMean != null && tStd != null) tMean - WVF_MULT * tStd else null
        val t = (tLower != null && top[i].isFinite() && top[i] <= tLower) ||
            (tRange != null && top[i].isFinite() && top[i] <= tRange * WVF_PERCENTILE)

        bottomActive[i] = b
        topActive[i] = t
    }

    return candles.indices.map { i ->
        val greenStart = bottomActive[i] && (i == 0 || !bottomActive[i - 1])
        val redStart = topActive[i] && (i == 0 || !topActive[i - 1])
        H1WilliamsState(
            active = bottomActive[i] || topActive[i],
            greenStart = greenStart,
            redStart = redStart
        )
    }
}

private fun calculateM5MsbEvents(
    candles: List<Candle>,
    zigzagLength: Int,
    fibFactor: Double
): Map<Long, List<M5MsbEvent>> {
    if (candles.isEmpty()) return emptyMap()
    val n = candles.size
    val highs = candles.map { it.high }
    val lows = candles.map { it.low }
    val toUp = BooleanArray(n)
    val toDown = BooleanArray(n)
    val trend = IntArray(n) { 1 }
    val lowVal = DoubleArray(n) { Double.NaN }
    val highVal = DoubleArray(n) { Double.NaN }
    val lowIsRollingExtreme = BooleanArray(n)
    val highIsRollingExtreme = BooleanArray(n)
    val lastLowSource = IntArray(n) { -1 }
    val lastHighSource = IntArray(n) { -1 }

    for (i in 0 until n) {
        val from = max(0, i - zigzagLength + 1)
        var maxHigh = Double.NEGATIVE_INFINITY
        var minLow = Double.POSITIVE_INFINITY
        for (j in from..i) {
            maxHigh = max(maxHigh, highs[j])
            minLow = min(minLow, lows[j])
        }
        toUp[i] = highs[i] >= maxHigh
        toDown[i] = lows[i] <= minLow
        val previousTrend = if (i == 0) 1 else trend[i - 1]
        trend[i] = when {
            previousTrend == 1 && toDown[i] -> -1
            previousTrend == -1 && toUp[i] -> 1
            else -> previousTrend
        }

        // Pine ta.barssince(to_up[1]): number of bars since a to_up event
        // on the previous/currently shifted series, so current M5 is excluded.
        var lastUpBeforeCurrent = -1
        var lastDownBeforeCurrent = -1
        for (j in i - 1 downTo 0) {
            if (lastUpBeforeCurrent < 0 && toUp[j]) lastUpBeforeCurrent = j
            if (lastDownBeforeCurrent < 0 && toDown[j]) lastDownBeforeCurrent = j
            if (lastUpBeforeCurrent >= 0 && lastDownBeforeCurrent >= 0) break
        }
        val upSince = if (lastUpBeforeCurrent >= 0) (i - 1) - lastUpBeforeCurrent else 0
        val downSince = if (lastDownBeforeCurrent >= 0) (i - 1) - lastDownBeforeCurrent else 0
        val lowLength = if (upSince > 0) upSince else 1
        val highLength = if (downSince > 0) downSince else 1
        val lowFrom = max(0, i - lowLength + 1)
        val highFrom = max(0, i - highLength + 1)
        var localLow = Double.POSITIVE_INFINITY
        var localHigh = Double.NEGATIVE_INFINITY
        for (j in lowFrom..i) localLow = min(localLow, lows[j])
        for (j in highFrom..i) localHigh = max(localHigh, highs[j])
        lowVal[i] = localLow
        highVal[i] = localHigh
        lowIsRollingExtreme[i] = abs(localLow - lows[i]) < 1e-12
        highIsRollingExtreme[i] = abs(localHigh - highs[i]) < 1e-12
        lastLowSource[i] = if (lowIsRollingExtreme[i]) i else if (i > 0) lastLowSource[i - 1] else -1
        lastHighSource[i] = if (highIsRollingExtreme[i]) i else if (i > 0) lastHighSource[i - 1] else -1
    }

    val highPivotValues = mutableListOf<Double>()
    val highPivotTimes = mutableListOf<Long>()
    val lowPivotValues = mutableListOf<Double>()
    val lowPivotTimes = mutableListOf<Long>()
    val output = linkedMapOf<Long, MutableList<M5MsbEvent>>()
    var market = 1
    var lastLowAtMarketChange: Double? = null
    var lastHighAtMarketChange: Double? = null

    for (i in 0 until n) {
        val changedTrend = i > 0 && trend[i] != trend[i - 1]
        val lowSource = lastLowSource[i]
        val highSource = lastHighSource[i]
        val lowTime = if (lowSource >= 0) candles[lowSource].timeMillis else null
        val highTime = if (highSource >= 0) candles[highSource].timeMillis else null

        if (changedTrend) {
            if (trend[i] == 1 && lowTime != null && lowVal[i].isFinite()) {
                lowPivotValues.add(lowVal[i])
                lowPivotTimes.add(lowTime)
            }
            if (trend[i] == -1 && highTime != null && highVal[i].isFinite()) {
                highPivotValues.add(highVal[i])
                highPivotTimes.add(highTime)
            }
        }

        val hCount = highPivotValues.size
        val lCount = lowPivotValues.size
        val h0 = if (hCount >= 1) highPivotValues[hCount - 1] else null
        val h1 = if (hCount >= 2) highPivotValues[hCount - 2] else null
        val h0t = if (hCount >= 1) highPivotTimes[hCount - 1] else null
        val h1t = if (hCount >= 2) highPivotTimes[hCount - 2] else null
        val l0 = if (lCount >= 1) lowPivotValues[lCount - 1] else null
        val l1 = if (lCount >= 2) lowPivotValues[lCount - 2] else null
        val l0t = if (lCount >= 1) lowPivotTimes[lCount - 1] else null
        val l1t = if (lCount >= 2) lowPivotTimes[lCount - 2] else null

        val priorMarket = market
        val duplicateCurrentStructure = samePrice(lastLowAtMarketChange, l0) ||
            samePrice(lastHighAtMarketChange, h0)
        var nextMarket = priorMarket

        if (!duplicateCurrentStructure) {
            if (priorMarket == 1 && l0 != null && l1 != null && h0 != null &&
                l0 < l1 && l0 < l1 - abs(h0 - l1) * fibFactor
            ) {
                nextMarket = -1
            } else if (priorMarket == -1 && h0 != null && h1 != null && l0 != null &&
                h0 > h1 && h0 > h1 + abs(h1 - l0) * fibFactor
            ) {
                nextMarket = 1
            }
        }

        val changedMarket = nextMarket != priorMarket
        if (changedMarket) {
            if (nextMarket == 1 && h1 != null && h1t != null && h0t != null && l0t != null) {
                output.getOrPut(candles[i].timeMillis) { mutableListOf() }.add(
                    M5MsbEvent(
                        direction = 1,
                        level = h1,
                        startTime = h1t,
                        endTime = h0t,
                        labelTime = (h1t + l0t) / 2L,
                        eventTime = candles[i].timeMillis
                    )
                )
            } else if (nextMarket == -1 && l1 != null && l1t != null && l0t != null && h0t != null) {
                output.getOrPut(candles[i].timeMillis) { mutableListOf() }.add(
                    M5MsbEvent(
                        direction = -1,
                        level = l1,
                        startTime = l1t,
                        endTime = l0t,
                        labelTime = (l1t + h0t) / 2L,
                        eventTime = candles[i].timeMillis
                    )
                )
            }
            // ta.valuewhen(change(market), l0/h0, 0) becomes this bar's pivot
            // reference on the following bars.
            lastLowAtMarketChange = l0
            lastHighAtMarketChange = h0
        }
        market = nextMarket
    }

    return output
}

private fun ema(values: List<Double>, length: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val alpha = 2.0 / (length + 1.0)
    val result = ArrayList<Double>(values.size)
    var current = values[0]
    result.add(current)
    for (i in 1 until values.size) {
        current = alpha * values[i] + (1.0 - alpha) * current
        result.add(current)
    }
    return result
}

private fun rollingExtreme(values: List<Double>, index: Int, length: Int, highest: Boolean): Double? {
    if (index < 0 || values.isEmpty()) return null
    val start = max(0, index - length + 1)
    var result = if (highest) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    for (i in start..index) result = if (highest) max(result, values[i]) else min(result, values[i])
    return result
}

private fun rollingArrayExtreme(values: DoubleArray, index: Int, length: Int, highest: Boolean): Double? {
    if (index < 0 || values.isEmpty()) return null
    val start = max(0, index - length + 1)
    var result = if (highest) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    var found = false
    for (i in start..index) {
        val v = values[i]
        if (!v.isFinite()) continue
        result = if (highest) max(result, v) else min(result, v)
        found = true
    }
    return if (found) result else null
}

private fun rollingSma(values: DoubleArray, index: Int, length: Int): Double? {
    if (index < length - 1) return null
    var total = 0.0
    for (i in index - length + 1..index) {
        if (!values[i].isFinite()) return null
        total += values[i]
    }
    return total / length
}

private fun rollingStdev(values: DoubleArray, index: Int, length: Int): Double? {
    if (index < length - 1) return null
    val mean = rollingSma(values, index, length) ?: return null
    var sum = 0.0
    for (i in index - length + 1..index) {
        if (!values[i].isFinite()) return null
        val d = values[i] - mean
        sum += d * d
    }
    // Pine ta.stdev uses population deviation by default (biased = true).
    return sqrt(sum / length)
}

private fun samePrice(a: Double?, b: Double?): Boolean =
    a != null && b != null && a.isFinite() && b.isFinite() && a == b

private fun fetchCandles(
    apiKey: String,
    symbol: String,
    interval: String,
    requestedCount: Int
): CandleFetchResult {
    val intervalMs = when (interval) {
        "5min" -> M5_MS
        "15min" -> M15_MS
        else -> H1_MS
    }

    val query = listOf(
        "symbol" to symbol,
        "interval" to interval,
        "outputsize" to requestedCount.toString(),
        "timezone" to "UTC",
        "format" to "JSON",
        "order" to "ASC",
        "apikey" to apiKey
    ).joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

    val connection = (URL("https://api.twelvedata.com/time_series?$query").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 25_000
        setRequestProperty("Accept", "application/json")
    }

    try {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (body.isBlank()) throw IOException("پاسخ خالی از سرور دریافت شد (HTTP $responseCode).")

        val json = JSONObject(body)
        val values = json.optJSONArray("values")
        if (responseCode !in 200..299 || values == null || json.optString("status") == "error") {
            val message = json.optString("message").ifBlank { json.optString("msg") }.ifBlank { "خطای HTTP $responseCode یا پاسخ نامعتبر API" }
            throw IOException("$interval: $message")
        }

        val raw = mutableListOf<Candle>()
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val time = item.optString("datetime")
            val timeMillis = parseUtcMillis(time) ?: continue
            val open = item.optString("open").toDoubleOrNull() ?: continue
            val high = item.optString("high").toDoubleOrNull() ?: continue
            val low = item.optString("low").toDoubleOrNull() ?: continue
            val close = item.optString("close").toDoubleOrNull() ?: continue
            if (time.isNotBlank() && high >= low) raw.add(Candle(time, timeMillis, open, high, low, close))
        }

        val unique = raw.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val now = System.currentTimeMillis()
        val closed = unique.filter { it.timeMillis + intervalMs <= now }
        val inSession = closed.filter { isWithinUsualForexSession(it.timeMillis) }
        if (inSession.isEmpty()) {
            val sample = unique.takeLast(3).joinToString(", ") { it.time }
            throw IOException("$interval: کندل معتبر در ساعات معمول بازار پیدا نشد. آخرین زمان‌های پاسخ API: $sample")
        }

        return CandleFetchResult(
            candles = inSession,
            rawCount = unique.size,
            removedOutsideForexSession = closed.size - inSession.size,
            removedUnclosed = unique.size - closed.size,
            requestedCount = requestedCount
        )
    } finally {
        connection.disconnect()
    }
}

private fun parseUtcMillis(value: String): Long? {
    val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
    for (pattern in formats) {
        try {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = fmt.parse(value)
            if (parsed != null) return parsed.time
        } catch (_: Exception) { }
    }
    return null
}

private fun isWithinUsualForexSession(timeMillis: Long): Boolean {
    val newYork = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US).apply {
        timeInMillis = timeMillis
    }
    val day = newYork.get(Calendar.DAY_OF_WEEK)
    val minuteOfDay = newYork.get(Calendar.HOUR_OF_DAY) * 60 + newYork.get(Calendar.MINUTE)
    val sessionBoundary = 17 * 60
    return when (day) {
        Calendar.SATURDAY -> false
        Calendar.SUNDAY -> minuteOfDay >= sessionBoundary
        Calendar.FRIDAY -> minuteOfDay < sessionBoundary
        else -> true
    }
}

private fun loadSavedApiKey(context: Context): String {
    val encrypted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(API_KEY_PREF, null) ?: return ""
    return try {
        decryptApiKey(encrypted)
    } catch (_: Exception) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(API_KEY_PREF).apply()
        ""
    }
}

private fun saveApiKeySecurely(context: Context, apiKey: String) {
    if (apiKey.isBlank()) return
    val encrypted = encryptApiKey(apiKey)
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(API_KEY_PREF, encrypted).apply()
}

private fun getOrCreateApiKeySecret(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build()
    generator.init(spec)
    return generator.generateKey()
}

private fun encryptApiKey(apiKey: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateApiKeySecret())
    val iv = cipher.iv
    val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
    val buffer = ByteBuffer.allocate(4 + iv.size + encrypted.size)
    buffer.putInt(iv.size)
    buffer.put(iv)
    buffer.put(encrypted)
    return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
}

private fun decryptApiKey(encoded: String): String {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    val buffer = ByteBuffer.wrap(bytes)
    if (buffer.remaining() < 5) throw IOException("کلید ذخیره‌شده معتبر نیست.")
    val ivLength = buffer.int
    if (ivLength <= 0 || ivLength > buffer.remaining()) throw IOException("داده رمزگذاری‌شده نامعتبر است.")
    val iv = ByteArray(ivLength)
    buffer.get(iv)
    val ciphertext = ByteArray(buffer.remaining())
    buffer.get(ciphertext)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateApiKeySecret(), GCMParameterSpec(128, iv))
    return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
}

private fun loadSavedNotificationSoundUri(context: Context): String? {
    return context.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        .getString(NOTIFICATION_SOUND_URI_KEY, null)
}

private fun saveNotificationSoundUri(context: Context, soundUri: String?) {
    val editor = context.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE).edit()
    if (soundUri.isNullOrBlank()) {
        editor.remove(NOTIFICATION_SOUND_URI_KEY)
    } else {
        editor.putString(NOTIFICATION_SOUND_URI_KEY, soundUri)
    }
    editor.apply()
}

private fun notificationChannelId(soundUriString: String?): String {
    val suffix = if (soundUriString.isNullOrBlank()) {
        "default"
    } else {
        Integer.toHexString(soundUriString.hashCode())
    }
    return "${NOTIFICATION_CHANNEL_PREFIX}_$suffix"
}

private fun resolveNotificationSoundLabel(context: Context, soundUriString: String?): String {
    if (soundUriString.isNullOrBlank()) return "صدای پیش‌فرض اندروید"
    return try {
        val uri = Uri.parse(soundUriString)
        RingtoneManager.getRingtone(context, uri)?.getTitle(context)
            ?: "صدای انتخاب‌شده"
    } catch (_: Exception) {
        "صدای انتخاب‌شده"
    }
}

private fun playNotificationSound(context: Context, soundUriString: String?) {
    try {
        val uri = soundUriString?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, uri)?.play()
    } catch (_: Exception) {
        // A sound preview must not crash the app if the chosen URI is no longer available.
    }
}

private fun createNotificationChannel(context: Context, soundUriString: String? = loadSavedNotificationSoundUri(context)) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = notificationChannelId(soundUriString)
        if (manager.getNotificationChannel(channelId) != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val soundUri = soundUriString?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val chosenSoundName = resolveNotificationSoundLabel(context, soundUriString)
        val channel = NotificationChannel(
            channelId,
            "Trading Alarm · $chosenSoundName",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "BUY/SELL rectangle signals from Trading Alarm"
            setSound(soundUri, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 150L, 350L)
        }
        manager.createNotificationChannel(channel)
    }
}

private fun registerNewSignalIfNeeded(
    context: Context,
    symbol: String,
    signal: StrategySignal?,
    latestClosedM15Time: Long
): Boolean {
    val prefs = context.getSharedPreferences(SIGNAL_PREFS, Context.MODE_PRIVATE)
    val safeSymbol = symbol.replace("/", "_").replace(" ", "")
    val baselineKey = "${SIGNAL_BASELINE_SET_KEY}_$safeSymbol"
    val lastTimeKey = "${LAST_SIGNAL_TIME_KEY}_$safeSymbol"
    val baselineSet = prefs.getBoolean(baselineKey, false)
    val previousTime = prefs.getLong(lastTimeKey, Long.MIN_VALUE)
    if (!baselineSet) {
        // Baseline uses the latest closed M15 candle time, not wall-clock time,
        // so a new signal on the immediately following M15 bar is not skipped.
        prefs.edit()
            .putBoolean(baselineKey, true)
            .putLong(lastTimeKey, signal?.activationTime ?: latestClosedM15Time)
            .apply()
        return false
    }
    if (signal != null && signal.activationTime > previousTime) {
        prefs.edit().putLong(lastTimeKey, signal.activationTime).apply()
        return true
    }
    return false
}

private fun sendSignalNotification(context: Context, signal: StrategySignal, symbol: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val soundUriString = loadSavedNotificationSoundUri(context)
    createNotificationChannel(context, soundUriString)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = notificationChannelId(soundUriString)
    val soundUri = soundUriString?.let { Uri.parse(it) }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context).setSound(soundUri)
    }

    // Rectangle levels define the limit entry and stop loss:
    // BUY LIMIT enters at the rectangle top with SL at its bottom.
    // SELL LIMIT enters at the rectangle bottom with SL at its top.
    val pair = symbol.replace("/", "").replace(" ", "").uppercase(Locale.US)
    val isBuy = signal.direction == 1
    val orderType = if (isBuy) "BUY LIMIT" else "SELL LIMIT"
    val entryPrice = if (isBuy) signal.top else signal.bottom
    val stopLossPrice = if (isBuy) signal.bottom else signal.top
    val alertText = "$orderType $pair AT >>>> ${formatPrice(entryPrice)}\nSL >>>> ${formatPrice(stopLossPrice)}"
    val firstLine = "$orderType $pair AT >>>> ${formatPrice(entryPrice)}"

    val notification = builder
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Trading Alarm")
        .setContentText(firstLine)
        .setStyle(Notification.BigTextStyle().bigText(alertText))
        .setCategory(Notification.CATEGORY_ALARM)
        .setAutoCancel(true)
        .build()

    val notificationId = if (symbol == "AUD/USD") 4102 else 4103
    manager.notify(notificationId, notification)
}

private fun sendTestNotification(context: Context, soundUriString: String?) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    createNotificationChannel(context, soundUriString)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = notificationChannelId(soundUriString)
    val soundUri = soundUriString?.let { Uri.parse(it) }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context).setSound(soundUri)
    }

    val firstLine = "BUY LIMIT NZDUSD AT >>>> 0.65523"
    val sampleText = "$firstLine\nSL >>>> 0.65322"

    val notification = builder
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Trading Alarm")
        .setContentText(firstLine)
        .setStyle(Notification.BigTextStyle().bigText(sampleText))
        .setAutoCancel(true)
        .build()

    manager.notify(4199, notification)
}

private fun directionName(direction: Int): String = when (direction) {
    1 -> "BUY"
    -1 -> "SELL"
    else -> "هیچ‌کدام"
}

private fun formatPrice(value: Double): String = String.format(Locale.US, "%.5f", value)

private fun formatUtc(timeMillis: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return format.format(Date(timeMillis)) + " UTC"
}

@Composable
private fun CandleChart(
    candles: List<Candle>,
    emaLines: List<ChartLine>,
    rectangles: List<StrategyRectangle>,
    msbLines: List<M5MsbEvent>,
    modifier: Modifier = Modifier
) {
    var visibleCandleCount by rememberSaveable { mutableStateOf(60) }
    var viewEndOffset by rememberSaveable { mutableStateOf(0) }

    val firstTime = candles.firstOrNull()?.timeMillis
    val lastTime = candles.lastOrNull()?.timeMillis

    // Each successful refresh returns the chart to the latest candles.
    // Manual panning/zoom remains unchanged during ordinary recompositions.
    LaunchedEffect(candles.size, firstTime, lastTime) {
        visibleCandleCount = min(60, candles.size).coerceAtLeast(1)
        viewEndOffset = 0
    }

    if (candles.isEmpty()) {
        Box(
            modifier = modifier.background(Color(0xFF101821)),
            contentAlignment = Alignment.Center
        ) {
            Text("هنوز داده‌ای دریافت نشده", color = Color(0xFFB8C4D2))
        }
        return
    }

    val effectiveCount = visibleCandleCount.coerceIn(1, candles.size)
    val maxEndOffset = (candles.size - effectiveCount).coerceAtLeast(0)
    val effectiveEndOffset = viewEndOffset.coerceIn(0, maxEndOffset)
    val visibleEndIndex = (candles.lastIndex - effectiveEndOffset).coerceIn(0, candles.lastIndex)
    val visibleStart = max(visibleEndIndex - effectiveCount + 1, 0)
    val visible = candles.subList(visibleStart, visibleEndIndex + 1)

    val gridColor = Color(0xFF344150)
    val risingColor = Color(0xFF26A69A)
    val fallingColor = Color(0xFFEF5350)
    val minCandleCount = min(20, candles.size).coerceAtLeast(1)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    visibleCandleCount = (visibleCandleCount - 20).coerceAtLeast(minCandleCount)
                    viewEndOffset = viewEndOffset.coerceAtMost(
                        (candles.size - visibleCandleCount).coerceAtLeast(0)
                    )
                },
                enabled = visibleCandleCount > minCandleCount,
                modifier = Modifier.weight(1f)
            ) {
                Text("کندل کمتر")
            }

            Button(
                onClick = {
                    visibleCandleCount = (visibleCandleCount + 20).coerceAtMost(candles.size)
                    viewEndOffset = viewEndOffset.coerceAtMost(
                        (candles.size - visibleCandleCount).coerceAtLeast(0)
                    )
                },
                enabled = visibleCandleCount < candles.size,
                modifier = Modifier.weight(1f)
            ) {
                Text("کندل بیشتر")
            }

            Button(
                onClick = { viewEndOffset = 0 },
                enabled = viewEndOffset > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("آخرین")
            }
        }

        Text(
            text = "نمایش ${visible.size} از ${candles.size} کندل M15  ·  انگشت را به چپ بکش تا به کندل‌های جدیدتر برگردی؛ به راست بکش تا به گذشته بروی",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101821))
                .pointerInput(candles.size, effectiveCount) {
                    var accumulatedDragPx = 0f
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val chartWidthPx = (
                            size.width.toFloat() - 8.dp.toPx() - 76.dp.toPx()
                        ).coerceAtLeast(1f)
                        val candleStepPx = chartWidthPx / effectiveCount.toFloat()
                        accumulatedDragPx += dragAmount
                        val barsMoved = (accumulatedDragPx / candleStepPx).toInt()
                        if (barsMoved != 0) {
                            val allowedOffset = (candles.size - effectiveCount).coerceAtLeast(0)
                            // Finger left (negative drag) moves toward newer candles;
                            // finger right moves toward older candles.
                            viewEndOffset = (viewEndOffset + barsMoved).coerceIn(0, allowedOffset)
                            accumulatedDragPx -= barsMoved * candleStepPx
                        }
                    }
                }
        ) {
            val left = 8.dp.toPx()
            val rightInset = 76.dp.toPx()
            val topInset = 12.dp.toPx()
            val bottomInset = 28.dp.toPx()
            val plotRight = size.width - rightInset
            val plotBottom = size.height - bottomInset
            val plotWidth = (plotRight - left).coerceAtLeast(1f)
            val plotHeight = (plotBottom - topInset).coerceAtLeast(1f)
            val stepWidth = plotWidth / visible.size.toFloat()

            fun xForIndex(index: Int): Float =
                left + (index - visibleStart + 0.5f) * stepWidth

            fun xForTime(time: Long): Float {
                var lo = 0
                var hi = candles.lastIndex
                var best = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (candles[mid].timeMillis <= time) {
                        best = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                if (best < 0) return left
                val offsetFraction = (
                    (time - candles[best].timeMillis).toDouble() / M15_MS.toDouble()
                ).coerceIn(0.0, 1.0).toFloat()
                return left + (best - visibleStart + offsetFraction) * stepWidth
            }

            fun priceVisible(rect: StrategyRectangle): Boolean {
                val rectRight = rect.rightIndex
                    .coerceAtLeast(rect.activationIndex)
                    .coerceAtMost(candles.lastIndex)
                return rectRight >= visibleStart && rect.leftIndex <= visibleEndIndex
            }

            val visibleEmaValues = emaLines.flatMap { line ->
                val from = visibleStart.coerceIn(0, line.values.size)
                val until = (visibleEndIndex + 1).coerceIn(from, line.values.size)
                line.values.subList(from, until).filterNotNull().filter { it.isFinite() }
            }

            val visibleRectPrices = rectangles
                .filter { priceVisible(it) }
                .flatMap { listOf(it.top, it.bottom) }

            val visibleMsbPrices = msbLines
                .filter {
                    it.endTime >= visible.first().timeMillis - M15_MS &&
                        it.startTime <= visible.last().timeMillis + M15_MS
                }
                .map { it.level }

            val maxPrice = max(
                visible.maxOf { it.high },
                (visibleEmaValues + visibleRectPrices + visibleMsbPrices).maxOrNull()
                    ?: visible.maxOf { it.high }
            )
            val minPrice = min(
                visible.minOf { it.low },
                (visibleEmaValues + visibleRectPrices + visibleMsbPrices).minOrNull()
                    ?: visible.minOf { it.low }
            )

            val range = (maxPrice - minPrice).coerceAtLeast(0.00001)
            val paddedHigh = maxPrice + range * 0.04
            val paddedLow = minPrice - range * 0.04
            val paddedRange = (paddedHigh - paddedLow).coerceAtLeast(0.00001)
            fun y(price: Double): Float =
                topInset + ((paddedHigh - price) / paddedRange).toFloat() * plotHeight

            for (step in 0..4) {
                val fraction = step / 4f
                val yy = topInset + fraction * plotHeight
                drawLine(
                    color = gridColor,
                    start = Offset(left, yy),
                    end = Offset(plotRight, yy),
                    strokeWidth = 1.dp.toPx()
                )

                val labelPrice = paddedHigh - fraction * paddedRange
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, "%.5f", labelPrice),
                    plotRight + 5.dp.toPx(),
                    yy + 3.dp.toPx(),
                    AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 10.dp.toPx()
                    }
                )
            }

            // Draw all stored historical rectangles that overlap this viewport.
            for (rect in rectangles) {
                if (!priceVisible(rect)) continue

                val rectStartIndex = rect.leftIndex.coerceIn(0, candles.lastIndex)
                val x1 = if (rectStartIndex < visibleStart) {
                    left
                } else {
                    xForIndex(rectStartIndex)
                }.coerceIn(left, plotRight)

                val rectEndIndex = rect.rightIndex
                    .coerceAtLeast(rect.activationIndex)
                    .coerceIn(0, candles.lastIndex)

                val x2 = if (rectEndIndex >= visibleEndIndex) {
                    plotRight
                } else {
                    (xForIndex(rectEndIndex) + stepWidth * 0.5f).coerceIn(left, plotRight)
                }

                if (x2 < left || x1 > plotRight || x2 <= x1) continue

                val color = if (rect.direction == 1) {
                    Color(0xFF00E676)
                } else {
                    Color(0xFFFF1744)
                }
                val yTop = y(rect.top)
                val yBottom = y(rect.bottom)

                drawRect(
                    color = color.copy(alpha = 0.13f),
                    topLeft = Offset(x1, min(yTop, yBottom)),
                    size = Size(
                        (x2 - x1).coerceAtLeast(0f),
                        abs(yBottom - yTop)
                    )
                )
                drawLine(color, Offset(x1, yTop), Offset(x2, yTop), 1.5.dp.toPx())
                drawLine(color, Offset(x1, yBottom), Offset(x2, yBottom), 1.5.dp.toPx())
                drawLine(color, Offset(x1, yTop), Offset(x1, yBottom), 1.5.dp.toPx())

                val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    this.color = if (rect.direction == 1) {
                        android.graphics.Color.GREEN
                    } else {
                        android.graphics.Color.RED
                    }
                    textSize = 10.dp.toPx()
                    isFakeBoldText = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    directionName(rect.direction),
                    max(x1 + 3.dp.toPx(), left),
                    min(yTop, yBottom) + 12.dp.toPx(),
                    labelPaint
                )
            }

            // M5 MSB levels intersecting the visible M15 window.
            for (msb in msbLines) {
                if (
                    msb.endTime < visible.first().timeMillis - M15_MS ||
                    msb.startTime > visible.last().timeMillis + M15_MS
                ) continue

                val rawX1 = xForTime(msb.startTime)
                val rawX2 = xForTime(msb.endTime)
                if (max(rawX1, rawX2) < left || min(rawX1, rawX2) > plotRight) continue

                val color = if (msb.direction == 1) {
                    Color(0xFF00C853)
                } else {
                    Color(0xFFFF1744)
                }
                drawLine(
                    color,
                    Offset(rawX1.coerceIn(left, plotRight), y(msb.level)),
                    Offset(rawX2.coerceIn(left, plotRight), y(msb.level)),
                    1.6.dp.toPx()
                )
            }

            // Candles for only the selected historical window.
            val bodyWidth = (stepWidth * 0.62f).coerceIn(1.5f, 8.dp.toPx())
            visible.forEachIndexed { visibleIndex, candle ->
                val x = left + (visibleIndex + 0.5f) * stepWidth
                val highY = y(candle.high)
                val lowY = y(candle.low)
                val openY = y(candle.open)
                val closeY = y(candle.close)
                val candleColor = if (candle.close >= candle.open) risingColor else fallingColor

                drawLine(
                    candleColor,
                    Offset(x, highY),
                    Offset(x, lowY),
                    1.dp.toPx()
                )

                val bodyTop = min(openY, closeY)
                val bodyHeight =
                    (max(openY, closeY) - bodyTop).coerceAtLeast(2.dp.toPx())

                drawRect(
                    candleColor,
                    Offset(x - bodyWidth / 2f, bodyTop),
                    Size(bodyWidth, bodyHeight)
                )
            }

            // Moving averages are clipped to the selected historical window.
            for (line in emaLines) {
                val color = Color(line.colorArgb)
                val fromIndex = max(visibleStart, 1)
                for (index in fromIndex..visibleEndIndex) {
                    val previous = line.values.getOrNull(index - 1) ?: continue
                    val current = line.values.getOrNull(index) ?: continue
                    if (!previous.isFinite() || !current.isFinite()) continue

                    drawLine(
                        color = color,
                        start = Offset(xForIndex(index - 1), y(previous)),
                        end = Offset(xForIndex(index), y(current)),
                        strokeWidth = line.widthDp.dp.toPx()
                    )
                }
            }

            val timePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.GRAY
                textSize = 9.dp.toPx()
            }

            drawContext.canvas.nativeCanvas.drawText(
                visible.first().time.takeLast(8),
                left,
                size.height - 6.dp.toPx(),
                timePaint
            )
            val lastTimeLabel = visible.last().time.takeLast(8)
            val labelWidth = timePaint.measureText(lastTimeLabel)
            drawContext.canvas.nativeCanvas.drawText(
                lastTimeLabel,
                plotRight - labelWidth,
                size.height - 6.dp.toPx(),
                timePaint
            )
        }
    }
}

private fun requestedOutputSize(interval: String): Int = when (interval) {
    "5min" -> 3000
    "15min" -> 1000
    else -> 300
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
private const val MONITOR_CHANNEL_ID = "trading_alarm_monitoring"
private const val MONITOR_NOTIFICATION_ID = 4001
private const val MONITOR_INTERVAL_MS = 15L * 60L * 1000L

class TradingAlarmService : Service() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        createMonitoringChannel()

        // Android requires a foreground notification promptly.
        startForeground(
            MONITOR_NOTIFICATION_ID,
            buildMonitoringNotification(
                "در حال آماده‌سازی مانیتورینگ بازار..."
            )
        )

        serviceScope.launch {
            monitoringLoop()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createMonitoringChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            if (manager.getNotificationChannel(
                    MONITOR_CHANNEL_ID
                ) == null
            ) {
                val channel = NotificationChannel(
                    MONITOR_CHANNEL_ID,
                    "Trading Alarm Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "وضعیت مانیتورینگ پس‌زمینه بازار"
                    setShowBadge(false)
                    enableVibration(false)
                }

                manager.createNotificationChannel(channel)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildMonitoringNotification(
        message: String
    ): Notification {

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, MONITOR_CHANNEL_ID)
            } else {
                Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW)
            }

        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Trading Alarm · مانیتورینگ")
            .setContentText(message)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(message)
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateMonitoringNotification(
        message: String
    ) {
        startForeground(
            MONITOR_NOTIFICATION_ID,
            buildMonitoringNotification(message)
        )
    }

    private suspend fun monitoringLoop() {

        while (currentCoroutineContext().isActive) {

            try {

                // Avoid spending API credits outside forex hours.
                if (!isWithinUsualForexSession(
                        System.currentTimeMillis()
                    )
                ) {
                    updateMonitoringNotification(
                        "مانیتورینگ فعال است؛ بازار فارکس بسته است."
                    )

                } else {

                    val apiKey = loadSavedApiKey(
                        applicationContext
                    )

                    if (apiKey.isBlank()) {

                        updateMonitoringNotification(
                            "کلید API ذخیره نشده است. اپ را باز کن و یک بررسی موفق انجام بده."
                        )

                    } else {

                        updateMonitoringNotification(
                            "در حال دریافت و پردازش داده‌های AUD/USD و NZD/USD..."
                        )

                        // Reuse the existing analysis engine.
                        val pairResults = listOf(
                            analyzePairForSymbol(
                                apiKey,
                                "AUD/USD"
                            ),
                            analyzePairForSymbol(
                                apiKey,
                                "NZD/USD"
                            )
                        )

                        val successfulPairs =
                            pairResults.filter {
                                it.analysis != null
                            }

                        if (successfulPairs.isEmpty()) {

                            val errors = pairResults.joinToString(
                                "\n"
                            ) {
                                "${it.symbol}: ${
                                    it.error ?: "خطای نامشخص"
                                }"
                            }

                            updateMonitoringNotification(
                                "دریافت داده ناموفق بود.\n$errors"
                            )

                        } else {

                            val newAlerts =
                                mutableListOf<String>()

                            for (pairResult in successfulPairs) {

                                val analysis =
                                    pairResult.analysis ?: continue

                                val signal =
                                    analysis.latestSignal

                                val latestClosedM15Time =
                                    analysis.m15Candles
                                        .lastOrNull()
                                        ?.timeMillis
                                        ?: System.currentTimeMillis()

                                val isNewSignal =
                                    registerNewSignalIfNeeded(
                                        applicationContext,
                                        pairResult.symbol,
                                        signal,
                                        latestClosedM15Time
                                    )

                                if (isNewSignal && signal != null) {

                                    sendSignalNotification(
                                        applicationContext,
                                        signal,
                                        pairResult.symbol
                                    )

                                    newAlerts.add(
                                        "${pairResult.symbol}: ${
                                            directionName(
                                                signal.direction
                                            )
                                        }"
                                    )
                                }
                            }

                            val statusText = buildString {

                                append("مانیتورینگ فعال است")
                                append("\nآخرین بررسی: ")
                                append(
                                    formatUtc(
                                        System.currentTimeMillis()
                                    )
                                )

                                for (result in successfulPairs) {
                                    append("\n")
                                    append(result.symbol)
                                    append(": ")
                                    append(
                                        result.analysis?.status
                                            ?: "آماده"
                                    )
                                }

                                if (newAlerts.isNotEmpty()) {
                                    append("\nآلارم جدید: ")
                                    append(
                                        newAlerts.joinToString("، ")
                                    )
                                }

                                val failedPairs =
                                    pairResults.filter {
                                        it.analysis == null
                                    }

                                for (failed in failedPairs) {
                                    append("\nخطای ")
                                    append(failed.symbol)
                                    append(": ")
                                    append(
                                        failed.error
                                            ?: "خطای نامشخص"
                                    )
                                }
                            }

                            updateMonitoringNotification(
                                statusText
                            )
                        }
                    }
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                updateMonitoringNotification(
                    "خطای مانیتورینگ: ${
                        e.message ?: "خطای نامشخص"
                    }"
                )
            }

            delay(MONITOR_INTERVAL_MS)
        }
    }
}
