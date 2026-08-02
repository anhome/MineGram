package desu.mintgram.plugins.jvm

/**
 * Kotlin-oriented entrypoint with a retained, non-null runtime context.
 *
 * A plugin may simply extend this class and override [onPluginLoad], while all hook methods from
 * [JvmPlugin] remain available for typed overrides.
 */
abstract class KotlinPlugin : JvmPluginAdapter() {
    protected lateinit var pluginContext: JvmPluginContext
        private set

    final override fun onLoad(context: JvmPluginContext) {
        pluginContext = context
        onPluginLoad()
    }

    protected open fun onPluginLoad() = Unit
}
