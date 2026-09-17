package com.ripostelabs.carlauncher.tuner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ripostelabs.carlauncher.MainActivity
import com.ripostelabs.carlauncher.carlib.AndroidOwnerGate
import com.ripostelabs.carlauncher.carlib.CarService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RAV4-97 on the farm: bind ITuner the way the suite radio will, seek and tune, and watch the
 * callback carry carsim's reply. Run with carsim's `radio` scenario on the instance
 * (`headunit carsim N radio`); the wire side is asserted from the simulator log outside.
 *
 * Skipped where nothing owns the port: the CI AVD has no carsim, so a claim there is never
 * acked and the case would read as a tuner bug instead of a missing simulator.
 *
 * FM 96.3 → carsim answers seek up with 96.5 and a direct tune with the frequency asked.
 */
@RunWith(AndroidJUnit4::class)
class TunerServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seekAndTuneRoundTrip() {
        assumeTrue("no carsim: ${AndroidOwnerGate.PROP_CAR_OWNER} unset", AndroidOwnerGate(context).ownerEnabled())

        // HOME attaches the car link to the hub; without it every answer is idle.
        ActivityScenario.launch(MainActivity::class.java)

        val bound = LinkedBlockingQueue<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { bound.put(service) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(TunerService.ACTION).setPackage(context.packageName)
        assertTrue("bindService refused", context.bindService(intent, conn, Context.BIND_AUTO_CREATE))
        val tuner = ITuner.Stub.asInterface(bound.poll(BIND_S, TimeUnit.SECONDS))
        assertNotNull("no binder within ${BIND_S}s", tuner)

        val seen = LinkedBlockingQueue<TunerState>()
        tuner.registerCallback(object : ITunerCallback.Stub() {
            override fun onState(state: TunerState) { seen.put(state) }
            override fun onSourceLost() {}
            override fun onReclaim() {}
        })

        assertTrue("tuner did not claim SRC_RADIO", tuner.claim())
        awaitFreq(seen, FM_START)

        tuner.sendKey(CarService.RADIO_KEY_SEEK_UP)
        awaitFreq(seen, FM_AFTER_SEEK)

        tuner.tune(FM_DIRECT, true)
        awaitFreq(seen, FM_DIRECT)

        tuner.release()
        context.unbindService(conn)
    }

    private fun awaitFreq(seen: LinkedBlockingQueue<TunerState>, freq: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REPLY_S)
        while (System.nanoTime() < deadline) {
            val s = seen.poll(1, TimeUnit.SECONDS) ?: continue
            if (s.freq == freq) {
                return
            }
        }
        throw AssertionError("no onState with freq=$freq within ${REPLY_S}s")
    }

    private companion object {
        const val BIND_S = 20L
        const val REPLY_S = 15L
        const val FM_START = 9630        // carsim radio scenario, tuner units (10 kHz)
        const val FM_AFTER_SEEK = 9650
        const val FM_DIRECT = 10170
    }
}
