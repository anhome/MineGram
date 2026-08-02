package desu.mintgram.plugins.jvm;

public abstract class JvmPluginAdapter implements JvmPlugin {
    protected JvmPluginContext pluginContext;

    @Override
    public void onLoad(JvmPluginContext context) {
        pluginContext = context;
    }
}
