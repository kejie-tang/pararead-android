package org.pararead.core

/** Zero selects automatic sizing. Bound manual settings to the CPUs available to this process. */
object CpuThreads {
    fun maximum(available: Int = Runtime.getRuntime().availableProcessors()): Int = available.coerceAtLeast(1)
    fun resolve(requested: Int, available: Int = Runtime.getRuntime().availableProcessors()): Int {
        require(requested >= 0) { "CPU_THREADS_MUST_BE_NON_NEGATIVE" }
        val cores = maximum(available)
        return if (requested == 0) {
            if (cores <= 4) cores else (cores.toLong() * 4 / 5).toInt()
        } else requested.coerceIn(1, cores)
    }
}
