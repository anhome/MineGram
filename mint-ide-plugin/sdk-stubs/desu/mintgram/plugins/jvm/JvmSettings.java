package desu.mintgram.plugins.jvm;

import desu.mintgram.plugins.models.DividerSetting;
import desu.mintgram.plugins.models.EditTextSetting;
import desu.mintgram.plugins.models.HeaderSetting;
import desu.mintgram.plugins.models.InputSetting;
import desu.mintgram.plugins.models.SelectorSetting;
import desu.mintgram.plugins.models.SwitchSetting;

public final class JvmSettings {
    public static HeaderSetting header(String text) {
        return null;
    }

    public static DividerSetting divider(String text) {
        return null;
    }

    public static SwitchSetting toggle(
        String key, String text, boolean defaultValue, String subtext, String icon
    ) {
        return null;
    }

    public static InputSetting input(
        String key, String text, String defaultValue, String subtext, String icon
    ) {
        return null;
    }

    public static EditTextSetting editor(
        String key, String hint, String defaultValue, boolean multiline, int maxLength, String mask
    ) {
        return null;
    }

    public static SelectorSetting selector(
        String key, String text, int defaultValue, String[] items, String icon
    ) {
        return null;
    }

    public static JvmActionSetting action(
        String text, String subtext, String icon, Runnable action
    ) {
        return null;
    }
}
