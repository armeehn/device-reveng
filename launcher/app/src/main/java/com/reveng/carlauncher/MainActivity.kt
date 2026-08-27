package com.reveng.carlauncher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.media.NowPlayingRepository
import com.reveng.carlauncher.ui.HomeScreen
import com.reveng.carlauncher.ui.theme.CarLauncherTheme

/**
 * MainActivity — the companion HOME. See CAR_API §6.
 *
 * Registered with MAIN + HOME + DEFAULT + LAUNCHER (AndroidManifest.xml); the user still
 * selects the default home from the system chooser. singleTask + landscape are fixed for
 * the 1920x720 head unit.
 */
class MainActivity : ComponentActivity() {

    private lateinit var carEvents: CarEvents
    private lateinit var carService: CarService
    private lateinit var appRepository: AppRepository
    private lateinit var nowPlaying: NowPlayingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the head-unit display awake while the launcher is foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        carEvents = CarEvents(applicationContext).also { it.register() }
        carService = CarService(applicationContext).also { it.bind() }
        appRepository = AppRepository(this)
        nowPlaying = NowPlayingRepository(applicationContext).also { it.start(lifecycleScope) }

        setContent {
            // Day/night from the vendor illumination broadcast (CAR_API §1.3).
            val dayNight by carEvents.dayNight.collectAsStateWithLifecycle()
            CarLauncherTheme(night = dayNight == CarEvents.DayNight.NIGHT) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        carEvents = carEvents,
                        carService = carService,
                        appRepository = appRepository,
                        nowPlaying = nowPlaying,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        carEvents.unregister()
        carService.unbind()
        nowPlaying.stop()
    }
}
