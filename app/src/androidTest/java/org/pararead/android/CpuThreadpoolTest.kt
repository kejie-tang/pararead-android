package org.pararead.android

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.llama.NativeBridge
import org.pararead.llama.TokenCallback
import java.io.File

/** Real native workers: reuse, idle CPU, cancellation recovery and joining on free. */
@RunWith(AndroidJUnit4::class)
class CpuThreadpoolTest {
    private fun tasks() = File("/proc/self/task").listFiles().orEmpty().map { it.name.toInt() }.toSet()
    private fun comm(tid: Int) = File("/proc/self/task/$tid/comm").readText().trim()
    private fun ticks(tid: Int): Long {
        val fields = File("/proc/self/task/$tid/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
        return fields[11].toLong() + fields[12].toLong()
    }

    @Test fun workersAreReusedIdleAndReleased(): Unit = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("poolGgufPath")
        assumeTrue("Supply poolGgufPath for real native threadpool validation", path != null)
        val before = tasks()
        val callerName = comm(Process.myTid())
        val handle = NativeBridge.load(path!!, 6, false, false, false)
        var workers = emptySet<Int>()
        val callback = object : TokenCallback { override fun onBytes(bytes: ByteArray) = true }
        val report = JSONObject().put("threads", 6).put("systemInfo", NativeBridge.systemInfo())
        try {
            // pthread workers inherit the calling native thread's Linux name.
            workers = (tasks() - before).filter { comm(it) == callerName }.toSet()
            assertEquals("Expected five persistent workers in addition to the calling thread", 5, workers.size)
            repeat(2) {
                assertTrue(NativeBridge.benchmark(handle, 32, 8, callback).all { it.isFinite() && it > 0 })
                assertTrue("The same native workers must survive separate calls", tasks().containsAll(workers))
            }
            // Allow the normal hybrid polling window to expire before measuring idle.
            delay(300)
            val idleBefore = workers.sumOf(::ticks)
            delay(500)
            val idleTicks = workers.sumOf(::ticks) - idleBefore
            val hz = Os.sysconf(OsConstants._SC_CLK_TCK)
            assertTrue("Idle workers kept consuming CPU: $idleTicks ticks", idleTicks <= maxOf(2L, hz / 10))
            report.put("idleWindowMs", 500).put("idleCpuTicks", idleTicks).put("ticksPerSecond", hz)

            val entered = CompletableDeferred<Unit>()
            val running = async(Dispatchers.IO) {
                runCatching { NativeBridge.benchmark(handle, 512, 512, object : TokenCallback {
                    override fun onBytes(bytes: ByteArray): Boolean { entered.complete(Unit); return true }
                }) }
            }
            withTimeout(5_000) { entered.await() }
            delay(50)
            NativeBridge.cancel(handle)
            val cancelled = withTimeout(10_000) { running.await() }
            assertTrue("Cancellation should stop the native benchmark", cancelled.isFailure)
            assertTrue(cancelled.exceptionOrNull()!!.message.orEmpty().contains("CANCELLED"))
            assertTrue("The pool must work again after an aborted graph",
                NativeBridge.benchmark(handle, 32, 8, callback).all { it.isFinite() && it > 0 })
            assertTrue(tasks().containsAll(workers))
            report.put("workerCount", workers.size).put("reusedAcrossCalls", true).put("cancellationRecovered", true)
        } finally { NativeBridge.free(handle) }
        withTimeout(5_000) { while (workers.any { it in tasks() }) delay(20) }
        report.put("allWorkersJoinedOnFree", true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "cpu-pool-lifecycle.json").writeText(report.toString(2))
    }
}
