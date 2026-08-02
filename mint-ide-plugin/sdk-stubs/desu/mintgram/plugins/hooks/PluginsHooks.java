package desu.mintgram.plugins.hooks;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public interface PluginsHooks {
    class PostRequestResult {
        public TLObject response;
        public TLRPC.TL_error error;

        public PostRequestResult(TLObject response, TLRPC.TL_error error) {
            this.response = response;
            this.error = error;
        }
    }
}
