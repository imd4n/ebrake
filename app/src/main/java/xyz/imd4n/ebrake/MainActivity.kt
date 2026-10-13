package xyz.imd4n.ebrake

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.EbrakeApp
import xyz.imd4n.ebrake.ui.theme.EbrakeTheme

class MainActivity : ComponentActivity() {
    private val vm: TranscodeViewModel by viewModels()

    // Нужен для уведомления о ходе кодирования. Само кодирование запустится и без
    // него (сервис — foreground), но пользователь не увидит прогресс.
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            EbrakeTheme {
                EbrakeApp(vm)
            }
        }
    }
}
