@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.crossbowffs.quotelock.app.detail

import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.crossbowffs.quotelock.consts.PREF_QUOTE_SOURCE_PREFIX
import com.crossbowffs.quotelock.ui.components.DetailAppBar
import com.crossbowffs.quotelock.ui.theme.QuoteLockTheme


@Composable
fun QuoteDetailRoute(
    modifier: Modifier = Modifier,
    quote: String,
    source: String?,
    viewModel: QuoteDetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState: QuoteDetailUiState by viewModel.uiState.collectAsState()
    QuoteDetailScreen(modifier,
        quote,
        source,
        uiState.quoteTypeface,
        uiState.sourceTypeface) { onBack() }
}

@Composable
fun QuoteDetailScreen(
    modifier: Modifier = Modifier,
    quote: String,
    source: String?,
    quoteTypeface: Typeface? = Typeface.DEFAULT,
    sourceTypeface: Typeface? = Typeface.DEFAULT,
    onBackPressed: () -> Unit,
) {
    var containerHeight by remember {
        mutableStateOf(0)
    }
    var contentHeight by remember {
        mutableStateOf(0)
    }
    Scaffold(
        topBar = { DetailAppBar(onBackPressed = onBackPressed) }
    ) { internalPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(internalPadding)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .consumedWindowInsets(internalPadding)
                .onGloballyPositioned { containerHeight = it.size.height }
                .verticalScroll(state = rememberScrollState(),
                    enabled = contentHeight > containerHeight)
        ) {
            Spacer(
                modifier = Modifier.height(
                    if (!LocalInspectionMode.current) {
                        with(LocalDensity.current) {
                            (containerHeight * 0.382F - contentHeight / 2).toDp()
                        }.coerceAtLeast(0.dp)
                    } else {
                        100.dp
                    }
                )
            )
            QuoteCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .onSizeChanged { contentHeight = it.height },
                quote = quote,
                source = source,
                quoteTypeface,
                sourceTypeface,
                minHeight = if (!LocalInspectionMode.current) {
                    with(LocalDensity.current) { (containerHeight * 0.6F).toDp() }
                } else 360.dp,
            )
        }
    }
}

@Composable
fun QuoteCard(
    modifier: Modifier = Modifier,
    quote: String,
    source: String?,
    quoteTypeface: Typeface? = Typeface.DEFAULT,
    sourceTypeface: Typeface? = Typeface.DEFAULT,
    minHeight: Dp = 0.dp,
) {
    var cardHeight by remember {
        mutableStateOf(0)
    }
    ElevatedCard(
        modifier = modifier
            .padding(4.dp)
            .heightIn(min = minHeight)
            .onGloballyPositioned { coordinates ->
                cardHeight = coordinates.size.height
            },
        colors = CardDefaults.elevatedCardColors(
            containerColor = QuoteLockTheme.quotelockColors.quoteCardSurface,
            contentColor = QuoteLockTheme.quotelockColors.quoteCardOnSurface,
        ),
        shape = RoundedCornerShape(2.dp),
    ) {
        Box(
            if (!LocalInspectionMode.current) {
                Modifier.heightIn(min = with(LocalDensity.current) { cardHeight.toDp() })
            } else Modifier.height(minHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(24.dp)
                    .align(alignment = Alignment.Center),
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = quote,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 36.sp,
                    fontFamily = FontFamily(quoteTypeface ?: Typeface.DEFAULT),
                    lineHeight = 1.3F.em,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth())
                if (!source.isNullOrBlank()) {
                    Text(text = source,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily(sourceTypeface ?: Typeface.DEFAULT),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(top = 24.dp))
                }
            }
        }
    }
}

class QuotePreviewParameterProvider : PreviewParameterProvider<Pair<String, String>> {
    override val values: Sequence<Pair<String, String>> = sequenceOf(
        Pair("落霞与孤鹜齐飞，秋水共长天一色", "${PREF_QUOTE_SOURCE_PREFIX}王勃 《滕王阁序》"),
        Pair("Knowledge is power.", "${PREF_QUOTE_SOURCE_PREFIX}Francis Bacon"),
    )
}

@Preview(name = "Quote Card Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Quote Card Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuoteCardPreview(
    @PreviewParameter(QuotePreviewParameterProvider::class) quote: Pair<String, String>,
) {
    QuoteLockTheme {
        Surface {
            QuoteCard(quote = quote.first, source = quote.second, minHeight = 240.dp)
        }
    }
}

@Preview(name = "Detail Screen Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Detail Screen Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DetailScreenPreview(
    @PreviewParameter(QuotePreviewParameterProvider::class) quote: Pair<String, String>,
) {
    QuoteLockTheme {
        Surface {
            QuoteDetailScreen(quote = quote.first, source = quote.second) {}
        }
    }
}