package pl.trikimusic.controller.ui.screen

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private val pages = listOf(
    OnboardingPage("Triki Music", "Obracaj kapsel w miejscu, aby precyzyjnie regulować głośność, i używaj przycisku do sterowania odtwarzaniem.", Icons.Default.MusicNote),
    OnboardingPage("Połącz Triki", "Bezpieczne połączenie Bluetooth LE odbiera dane czujników bez wysyłania ich poza telefon.", Icons.AutoMirrored.Filled.BluetoothSearching),
    OnboardingPage("Sterowanie muzyką", "Żyroskop Z reguluje głośność tylko przy stabilnym odczycie akcelerometru. Kliknięcia przycisku obsługują odtwarzanie i zmianę utworu.", Icons.Default.GraphicEq),
    OnboardingPage("Kalibracja", "Krótka kalibracja dopasuje czujniki do Twojego egzemplarza Triki i ograniczy przypadkowe zmiany głośności.", Icons.Default.Tune),
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = pages::size)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f), MaterialTheme.colorScheme.background),
                ),
            )
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIndex ->
                val page = pages[pageIndex]
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(132.dp),
                        shape = RoundedCornerShape(42.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 12.dp,
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(34.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.height(42.dp))
                    Text(page.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        page.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                pages.indices.forEach { index ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(if (pagerState.currentPage == index) 22.dp else 8.dp, 8.dp)
                            .background(
                                if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) onComplete()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (pagerState.currentPage == pages.lastIndex) "Rozpocznij" else "Dalej", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
