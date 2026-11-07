import com.rudnam.wallet.configureKotlinJvm
import com.rudnam.wallet.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A plain Kotlin module, with no Android in it (core:model). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}
