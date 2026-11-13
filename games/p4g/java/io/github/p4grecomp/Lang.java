package io.github.p4grecomp;

import java.util.Locale;

/** The app interface is English. This does not change the game's language. */
final class Lang {
    private Lang() {}
    static String device() { return "en"; }
    static String t(String spanish, String english) { return english; }
    static String f(String spanish, String english, Object... args) {
        return String.format(Locale.ENGLISH, english, args);
    }
}
