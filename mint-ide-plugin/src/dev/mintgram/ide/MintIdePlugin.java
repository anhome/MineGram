package dev.mintgram.ide;

import java.util.Arrays;
import java.util.List;

import desu.mintgram.plugins.jvm.JvmPluginAdapter;
import desu.mintgram.plugins.jvm.JvmPluginContext;
import desu.mintgram.plugins.jvm.JvmSettings;
import desu.mintgram.plugins.models.SettingItem;

import org.telegram.ui.ActionBar.BaseFragment;

public final class MintIdePlugin extends JvmPluginAdapter {
    @Override
    public void onLoad(JvmPluginContext context) {
        super.onLoad(context);
        context.log("Mint IDE готова");
    }

    @Override
    public List<SettingItem> createSettings() {
        return Arrays.asList(
            JvmSettings.header("Mint IDE"),
            JvmSettings.action(
                "Открыть редактор",
                "Java-код, файлы, проверка, сборка и установка",
                "menu_feature_code",
                this::openEditor
            ),
            JvmSettings.divider(
                "Проекты сохраняются автоматически. Собранный .plugin можно сразу " +
                "запустить или отправить другому пользователю."
            )
        );
    }

    private void openEditor() {
        pluginContext.runOnUiThread(() -> {
            try {
                Object value = Class.forName("org.telegram.ui.LaunchActivity")
                    .getMethod("getSafeLastFragment")
                    .invoke(null);
                if (value instanceof BaseFragment) {
                    ((BaseFragment) value).presentFragment(new MintIdeFragment(pluginContext));
                }
            } catch (Throwable error) {
                pluginContext.log(error);
            }
        });
    }
}
