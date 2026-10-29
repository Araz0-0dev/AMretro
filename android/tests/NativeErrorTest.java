package com.amretro.mobile;

public final class NativeErrorTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String mismatch = NativeError.persian("Save state belongs to another game or core version");
        check(mismatch.contains("معماری") && mismatch.contains("بازی"), "explain game and architecture mismatch");
        check(NativeError.persian("No save state exists in this slot").contains("جایگاه"), "missing slot");
        check(NativeError.persian("Save state is damaged").contains("آسیب"), "damaged state");
        check(NativeError.persian("Core rejected save state; current game was restored").contains("برگشت"), "restored current game");
        check(NativeError.persian("Libretro core is missing retro_run").contains("ناقص"), "missing core symbol");
        check(NativeError.persian("secret native diagnostic").indexOf("secret") < 0, "unknown native details are not exposed");
        check(!NativeError.persian(null).isEmpty(), "null fallback");
        System.out.println("NATIVE_ERROR_TEST_OK translated mismatch, corruption, rollback, missing core and fallback");
    }
}
