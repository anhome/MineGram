package desu.mintgram.plugins;

public class PluginsController {
    public static class HookResult<T> {
        public boolean cancel;
        public boolean isFinal;
        public T result;

        public HookResult(T result, boolean cancel, boolean isFinal) {
        }

        public static <T> HookResult<T> pass(T value) {
            return null;
        }

        public static <T> HookResult<T> modify(T value) {
            return null;
        }

        public static <T> HookResult<T> finalResult(T value) {
            return null;
        }

        public static <T> HookResult<T> cancel() {
            return null;
        }
    }
}
