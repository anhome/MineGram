package desu.mintgram.plugins.jvm;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.telegram.messenger.MessagesController;

public final class JvmPluginContext {
    public String getPluginId() {
        return null;
    }

    public Context getApplicationContext() {
        return null;
    }

    public int getCurrentAccount() {
        return 0;
    }

    public MessagesController getMessagesController(int account) {
        return null;
    }

    public File getDataDirectory() {
        return null;
    }

    public Object getSetting(String key, Object defaultValue) {
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        return defaultValue;
    }

    public String getString(String key, String defaultValue) {
        return defaultValue;
    }

    public void setSetting(String key, Object value) {
    }

    public void registerHook(String hookName) {
    }

    public void registerHook(String hookName, boolean substring, int priority) {
    }

    public void unregisterHook(String hookName) {
    }

    public void registerXposedHook(XC_MethodHook.Unhook hook) {
    }

    public void runOnUiThread(Runnable runnable) {
    }

    public void runOnPluginThread(Runnable runnable) {
    }

    public void log(String message) {
    }

    public void log(Throwable error) {
    }

    public byte[] readAsset(String path) throws IOException {
        return null;
    }

    public void copyAssetToFile(String path, File target) throws IOException {
    }

    public Map<String, ?> getAllSettings() {
        return null;
    }
}
