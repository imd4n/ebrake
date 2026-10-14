package xyz.imd4n.ebrake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.EbrakeApp
import xyz.imd4n.ebrake.ui.onboarding.OnboardingPrefs
import xyz.imd4n.ebrake.ui.onboarding.OnboardingScreen
import xyz.imd4n.ebrake.ui.theme.EbrakeTheme

class MainActivity : ComponentActivity() {
    private val vm: TranscodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Разрешения (медиа, уведомления) спрашивает онбординг — третьим
        // экраном. Автозапрос здесь убран: двойной диалог подряд выглядел
        // как баг, а онбординг можно пропустить и дать позже с «Ввода».
        setContent {
            EbrakeTheme {
                var showOnboarding by remember {
                    mutableStateOf(!OnboardingPrefs.isDone(applicationContext))
                }
                if (showOnboarding) {
                    OnboardingScreen(
                        vm = vm,
                        onDone = {
                            OnboardingPrefs.setDone(applicationContext)
                            showOnboarding = false
                        },
                    )
                } else {
                    EbrakeApp(vm)
                }
            }
        }
    }
}
