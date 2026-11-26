import com.rudnam.wallet.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
            add("testImplementation", libs.findLibrary("hilt-android-testing").get())
            add("kspTest", libs.findLibrary("hilt-compiler").get())
        }
        // Never reuse a cached KSP output: a stale one (left by KSP's incremental processing)
        // took Hilt's modules for a whole feature out of the release build, 2.1.43 to 2.1.47,
        // and its pages crashed opening. CI starts from a fresh checkout, so without the cache
        // KSP processes every module in full. It's quick next to the rest of the build.
        tasks.matching { it.name.startsWith("ksp") }.configureEach {
            outputs.doNotCacheIf("KSP output once left Hilt modules out") { true }
        }
    }
}
