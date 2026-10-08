package ua.edu.ukma.springers.voltstore.authservice.utils;

import lombok.experimental.UtilityClass;

import java.util.Locale;

@UtilityClass
public class EmailNormalizer {

    /** Emails are case-insensitive: the same normalization must be used wherever an email is stored or looked up. */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
