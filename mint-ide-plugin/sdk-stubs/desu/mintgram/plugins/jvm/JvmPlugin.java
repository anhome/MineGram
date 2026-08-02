package desu.mintgram.plugins.jvm;

import java.util.Collections;
import java.util.List;
import desu.mintgram.plugins.PluginsController;
import desu.mintgram.plugins.hooks.PluginsHooks;
import desu.mintgram.plugins.models.SettingItem;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public interface JvmPlugin {
    default void onLoad(JvmPluginContext context) throws Exception {
    }

    default void onUnload() throws Exception {
    }

    default void onAppEvent(String event) throws Exception {
    }

    default void onSettingChanged(String key, Object value) throws Exception {
    }

    default List<SettingItem> createSettings() throws Exception {
        return Collections.emptyList();
    }

    default PluginsController.HookResult<TLObject> onPreRequest(
        String requestName, int account, TLObject request
    ) throws Exception {
        return PluginsController.HookResult.pass(request);
    }

    default PluginsController.HookResult<PluginsHooks.PostRequestResult> onPostRequest(
        String requestName, int account, TLObject response, TLRPC.TL_error error
    ) throws Exception {
        return PluginsController.HookResult.pass(
            new PluginsHooks.PostRequestResult(response, error)
        );
    }

    default PluginsController.HookResult<TLRPC.Update> onUpdate(
        String updateName, int account, TLRPC.Update update
    ) throws Exception {
        return PluginsController.HookResult.pass(update);
    }

    default PluginsController.HookResult<TLRPC.Updates> onUpdates(
        String updatesName, int account, TLRPC.Updates updates
    ) throws Exception {
        return PluginsController.HookResult.pass(updates);
    }

    default PluginsController.HookResult<SendMessagesHelper.SendMessageParams> onSendMessage(
        int account, SendMessagesHelper.SendMessageParams params
    ) throws Exception {
        return PluginsController.HookResult.pass(params);
    }
}
