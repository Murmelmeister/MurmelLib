package de.murmelmeister.library.utils;

/**
 * Utility class for string operations.
 */
public final class StringUtil {
    /**
     * Checks if a {@link String} starts with another {@link String} ignoring case sensitivity.
     *
     * @param str    the input {@link String} to check
     * @param prefix the prefix to check against
     * @return {@code true} if the input {@link String} starts with the specified prefix, ignoring case sensitivity,
     * or {@code false} otherwise. Returns {@code false} if either the input {@link String} or the prefix is null.
     */
    public static boolean startsWithIgnoreCase(final String str, final String prefix) {
        return str != null &&
                prefix != null &&
                str.length() >= prefix.length() &&
                str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Normalizes the provided {@link String} by stripping leading and trailing whitespace
     * and returning {@code null} when the result is empty or the input is {@code null}.
     *
     * @param input the {@link String} to normalize
     * @return the stripped {@link String}, or {@code null} if the input is {@code null} or blank
     */
    public static String normalize(String input) {
        if (input == null) return null;
        input = input.strip();
        if (input.isEmpty()) return null;
        return input;
    }
}
