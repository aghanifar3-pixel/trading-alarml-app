package com.tradingalarm.app

import android.graphics.Paint as AndroidPaint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.util.Locale

private data class Candle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TradingAlarmScreen()
        }
    }
}

@Composable
private fun TradingAlarmScreen() {
    var apiKey by rememberSaveable { mutableStateOf("") }
    var selectedInterval by rememberSaveable { mutableStateOf("15min") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("کلید API را وارد کن و دریافت چارت را بزن.") }
    var lastRequestSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Trading Alarm",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "چارت آزمایشی فارکس · AUD/USD",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "این نسخه فقط داده قیمت را دریافت و چارت کندلی را نمایش می‌دهد؛ هنوز سیگنال معاملاتی تولید نمی‌کند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("کلید API از Twelve Data") },
                        placeholder = { Text("کلید را اینجا وارد کن") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text("کلید در کد GitHub قرار نمی‌گیرد و در این نسخه ذخیره دائمی نمی‌شود.")
                        }
                    )

                    Text("تایم‌فریم", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedInterval == "5min",
                            onClick = { selectedInterval = "5min" },
                            label = { Text("M5") }
                        )
                        FilterChip(
                            selected = selectedInterval == "15min",
                            onClick = { selectedInterval = "15min" },
                            label = { Text("M15") }
                        )
                        FilterChip(
                            selected = selectedInterval == "1h",
                            onClick = { selectedInterval = "1h" },
                            label = { Text("H1") }
                        )
                    }

                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                status = "ابتدا یک API Key معتبر وارد کن. کلید را در چت یا GitHub عمومی نفرست."
                                lastRequestSucceeded = false
                            } else {
                                val keyForRequest = apiKey.trim()
                                val intervalForRequest = selectedInterval
                                isLoading = true
                                status = "در حال دریافت کندل‌های AUD/USD از سرور..."
                                lastRequestSucceeded = false
                                scope.launch {
                                    try {
                                        val freshCandles = withContext(Dispatchers.IO) {
                                            fetchCandles(keyForRequest, intervalForRequest)
                                        }
                                        candles = freshCandles
                                        status = "دریافت موفق بود؛ ${freshCandles.size} کندل دریافت شد."
                                        lastRequestSucceeded = true
                                    } catch (e: Exception) {
                                        status = "دریافت ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                                        lastRequestSucceeded = false
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("در حال دریافت...")
                        } else {
                            Text("دریافت / به‌روزرسانی چارت")
                        }
                    }

                    Text(
                        text = status,
                        color = when {
                            isLoading -> MaterialTheme.colorScheme.secondary
                            lastRequestSucceeded -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("AUD/USD", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = when (selectedInterval) {
                                        "5min" -> "M5"
                                        "15min" -> "M15"
                                        else -> "H1"
                                    },
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (candles.isNotEmpty()) {
                                val latest = candles.last()
                                Text(
                                    text = "آخرین Close: ${formatPrice(latest.close)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (latest.close >= latest.open) Color(0xFF80CBC4) else Color(0xFFEF9A9A)
                                )
                                Text(
                                    text = "زمان آخرین کندل (UTC): ${latest.time}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            CandleChart(
                                candles = candles.takeLast(60),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(310.dp)
                            )
                            Text(
                                text = if (candles.isEmpty()) {
                                    "پس از دریافت داده، ۶۰ کندل آخر اینجا نمایش داده می‌شوند."
                                } else {
                                    "نمایش ${candles.takeLast(60).size} کندل از ${candles.size} کندل دریافتی"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun fetchCandles(apiKey: String, interval: String): List<Candle> {
    val query = listOf(
        "symbol" to "AUD/USD",
        "interval" to interval,
        "outputsize" to "100",
        "timezone" to "UTC",
        "format" to "JSON",
        "order" to "ASC",
        "apikey" to apiKey
    ).joinToString("&") { (name, value) ->
        "${urlEncode(name)}=${urlEncode(value)}"
    }

    val connection = (URL("https://api.twelvedata.com/time_series?$query").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
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
            val message = json.optString("message")
                .ifBlank { json.optString("msg") }
                .ifBlank { "خطای HTTP $responseCode یا پاسخ نامعتبر API" }
            throw IOException(message)
        }

        val result = mutableListOf<Candle>()
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val time = item.optString("datetime")
            val open = item.optString("open").toDoubleOrNull() ?: continue
            val high = item.optString("high").toDoubleOrNull() ?: continue
            val low = item.optString("low").toDoubleOrNull() ?: continue
            val close = item.optString("close").toDoubleOrNull() ?: continue
            if (time.isNotBlank()) result.add(Candle(time, open, high, low, close))
        }

        val sorted = result.sortedBy { it.time }
        if (sorted.isEmpty()) throw IOException("API هیچ کندل معتبری برنگرداند؛ نماد، تایم‌فریم یا API Key را بررسی کن.")
        return sorted.takeLast(100)
    } finally {
        connection.disconnect()
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun formatPrice(value: Double): String = String.format(Locale.US, "%.5f", value)

@Composable
private fun CandleChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    if (candles.isEmpty()) {
        Box(
            modifier = modifier.background(Color(0xFF101821)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "هنوز داده‌ای دریافت نشده",
                color = Color(0xFFB8C4D2),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val gridColor = Color(0xFF344150)
    val risingColor = Color(0xFF26A69A)
    val fallingColor = Color(0xFFEF5350)

    Canvas(modifier = modifier.background(Color(0xFF101821))) {
        val left = 8.dp.toPx()
        val rightInset = 76.dp.toPx()
        val top = 12.dp.toPx()
        val bottomInset = 28.dp.toPx()
        val plotRight = size.width - rightInset
        val plotBottom = size.height - bottomInset
        val plotWidth = (plotRight - left).coerceAtLeast(1f)
        val plotHeight = (plotBottom - top).coerceAtLeast(1f)

        val rawHigh = candles.maxOf { it.high }
        val rawLow = candles.minOf { it.low }
        val rawRange = (rawHigh - rawLow).coerceAtLeast(0.00001)
        val padding = rawRange * 0.04
        val maxPrice = rawHigh + padding
        val minPrice = rawLow - padding
        val priceRange = (maxPrice - minPrice).coerceAtLeast(0.00001)

        fun y(price: Double): Float = top + (((maxPrice - price) / priceRange).toFloat() * plotHeight)

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val pricePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10.dp.toPx()
            textAlign = AndroidPaint.Align.LEFT
        }
        val timePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.GRAY
            textSize = 9.dp.toPx()
            textAlign = AndroidPaint.Align.LEFT
        }

        for (step in 0..4) {
            val fraction = step / 4f
            val yy = top + fraction * plotHeight
            drawLine(
                color = gridColor,
                start = Offset(left, yy),
                end = Offset(plotRight, yy),
                strokeWidth = 1.dp.toPx()
            )
            val labelPrice = maxPrice - fraction * priceRange
            nativeCanvas.drawText(
                String.format(Locale.US, "%.5f", labelPrice),
                plotRight + 5.dp.toPx(),
                yy + 3.dp.toPx(),
                pricePaint
            )
        }

        val stepWidth = plotWidth / candles.size.toFloat()
        val bodyWidth = (stepWidth * 0.62f).coerceIn(1.5f, 8.dp.toPx())
        candles.forEachIndexed { index, candle ->
            val x = left + (index + 0.5f) * stepWidth
            val highY = y(candle.high)
            val lowY = y(candle.low)
            val openY = y(candle.open)
            val closeY = y(candle.close)
            val rising = candle.close >= candle.open
            val candleColor = if (rising) risingColor else fallingColor

            drawLine(
                color = candleColor,
                start = Offset(x, highY),
                end = Offset(x, lowY),
                strokeWidth = 1.dp.toPx()
            )
            val bodyTop = minOf(openY, closeY)
            val bodyHeight = (maxOf(openY, closeY) - bodyTop).coerceAtLeast(2.dp.toPx())
            drawRect(
                color = candleColor,
                topLeft = Offset(x - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, bodyHeight)
            )
        }

        nativeCanvas.drawText(candles.first().time.takeLast(8), left, size.height - 6.dp.toPx(), timePaint)
        val finalLabel = candles.last().time.takeLast(8)
        val labelWidth = timePaint.measureText(finalLabel)
        nativeCanvas.drawText(finalLabel, (plotRight - labelWidth).coerceAtLeast(left), size.height - 6.dp.toPx(), timePaint)
    }
}
