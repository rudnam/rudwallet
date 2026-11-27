package com.rudnam.wallet

import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** A lock: KSP tasks hold it while they run, so only one runs at a time in the whole build. */
abstract class KspLock : BuildService<BuildServiceParameters.None>

/**
 * KSP tasks one at a time, and never from the build cache.
 *
 * KSP 2 analyses each module inside the Gradle process, without isolation, and with parallel
 * builds several modules' runs overlapped there. Now and then one couldn't resolve types it
 * needed (even android.content.Context), and Hilt then generated nothing for that module,
 * without an error. The build cache kept those outputs, so the release build (2.1.43 to 2.1.47)
 * left out whole features' Hilt modules, and opening their pages crashed ("Cannot create an
 * instance of class …"). check-viewmodels.sh catches a build like that.
 */
internal fun Project.configureKsp() {
    val lock = gradle.sharedServices.registerIfAbsent("kspLock", KspLock::class.java) {
        maxParallelUsages.set(1)
    }
    tasks.matching { it.name.startsWith("ksp") }.configureEach {
        usesService(lock)
        outputs.doNotCacheIf("KSP outputs made while runs overlapped left Hilt modules out") { true }
    }
}
