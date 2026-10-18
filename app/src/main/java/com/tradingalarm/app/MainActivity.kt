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
