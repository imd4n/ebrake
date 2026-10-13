package xyz.imd4n.ebrake.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.screens.EditScreen
import xyz.imd4n.ebrake.ui.screens.InputScreen
import xyz.imd4n.ebrake.ui.screens.OutputScreen

private val Tabs = listOf(
    DockTab("Ввод", Icons.Filled.VideoLibrary),
    DockTab("Правка", Icons.Filled.Tune),
    DockTab("Вывод", Icons.Filled.Terminal),
)

@Composable
fun EbrakeApp(vm: TranscodeViewModel) {
    val state by vm.ui.collectAsState()
    val pagerState = rememberPagerState(pageCount = { Tabs.size })
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Свайп обновляет подсветку дока.
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        // Scaffold отдаёт инсеты (статус-бар сверху, навигация снизу), поэтому док
        // НЕ добавляет navigationBarsPadding() — иначе отступ удвоится.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // Контент должен заканчиваться выше парящего дока.
                    .padding(bottom = DockMetrics.ContentBottom),
            ) { page ->
                when (page) {
                    0 -> InputScreen(vm, state)
                    1 -> EditScreen(vm, state)
                    else -> OutputScreen(vm, state)
                }
            }
            FloatingDock(
                tabs = Tabs,
                selectedIndex = selectedTab,
                onSelect = { index ->
                    selectedTab = index
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = DockMetrics.BottomMargin)
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 380.dp),
            )
        }
    }
}
