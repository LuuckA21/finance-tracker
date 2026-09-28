package me.luucka.finance.user;

import java.util.Locale;

/**
 * Interface language chosen by the user.
 */
public enum Language {
    IT(Locale.ITALIAN),
    EN(Locale.ENGLISH),
    DE(Locale.GERMAN),
    FR(Locale.FRENCH);

    private final Locale locale;

    Language(Locale locale) {
        this.locale = locale;
    }

    public Locale locale() {
        return locale;
    }
}
