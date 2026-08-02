package org.telegram.tgnet;

public class TLRPC {
    public static class TL_error extends TLObject {
        public int code;
        public String text;
    }

    public static class Update extends TLObject {
    }

    public static class Updates extends TLObject {
    }
}
