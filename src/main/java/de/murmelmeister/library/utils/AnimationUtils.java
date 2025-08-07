package de.murmelmeister.library.utils;

import java.util.List;

/**
 * Utility class for animating strings with effects such as bouncing and color cycling.
 * This class provides methods to create animated string effects based on input strings,
 * color codes, and a raw numeric value that influences the animation state.
 */
public final class AnimationUtils {
    /**
     * Animates a "bounce" effect on the input string by dynamically adjusting its length
     * based on the provided raw position. The length modification occurs cyclically.
     *
     * @param input The input strings to be animated; if null, the method returns null
     * @param raw   The numeric value used to calculate the current state of the animation cycle
     * @return A substring of the input, adjusted according to the bounce animation logic;
     * if the input is null or its length is zero, the method returns the input as is
     */
    public static String animateBounce(String input, long raw) {
        if (input == null) return null;
        int length = input.length();
        long cycle = length * 2L;
        if (cycle == 0) return input;

        long index = raw % cycle;
        if (index < 0) index += cycle;

        int subLength = (index <= length)
                ? (int) (length - index)
                : (int) (index - length);
        subLength = Math.max(0, Math.min(subLength, length));
        return input.substring(0, subLength);
    }

    /**
     * Animates a given input string by prefixing it with a color code from a list,
     * chosen based on a cyclic calculation using the provided raw value.
     *
     * @param colors A list of color codes to select from; must not be null or empty
     * @param input  The input strings to be animated; if null, the method returns null
     * @param raw    A numeric value used to calculate the index of the color code in the cycle
     * @return The input string prefixed with the selected color code, or the input as is
     * if colors are null, empty, or input is null
     */
    public static String animateColor(List<String> colors, String input, long raw) {
        if (colors == null || colors.isEmpty() || input == null) return input;

        int size = colors.size();
        int idx = (int) (raw % size);
        if (idx < 0) idx += size;

        return colors.get(idx) + input;
    }

    /**
     * Animates a given input string by interleaving it with color codes from a list,
     * based on a cyclic calculation using the provided raw value. Each character in the input
     * string is prefixed with a color code selected in a round-robin fashion from the provided list.
     *
     * @param colors A list of color codes to select from; must not be null or empty. Each color
     *               is assigned cyclically to the characters in the input string.
     * @param input  The input strings to be animated; if null, the method returns null.
     *               If the color list is null or empty, the input string is returned as is.
     * @param raw    A numeric value used to calculate the starting index in the cycle of color codes.
     * @return The input string with each character prefixed by a cyclically assigned color code
     * from the list. If colors are null, empty, or input is null, the method returns the
     * input string unmodified.
     */
    public static String animatePerColorCycle(List<String> colors, String input, long raw) {
        if (colors == null || colors.isEmpty() || input == null) return input;
        StringBuilder sb = new StringBuilder();
        int size = colors.size();

        for (int i = 0; i < input.length(); i++) {
            long pos = raw + i;
            int colorIndex = (int) (pos % size);
            if (colorIndex < 0) colorIndex += size;
            String color = colors.get(colorIndex);
            sb.append(color).append(input.charAt(i));
        }

        return sb.toString();
    }

    /**
     * Animates an input string by first applying a "bounce" effect and then animating
     * it with color codes from a list in a cyclic fashion. The method combines the
     * results of {@code animateBounce} and {@code animatePerColorCycle} to produce
     * the final animated string.
     *
     * @param colors A list of color codes to select from; must not be null or empty.
     *               Color codes are applied cyclically to the characters in the input string.
     * @param input  The input strings to be animated; if null, the method returns null.
     *               If the color list is null or empty, the input string is returned after
     *               the bounce effect is applied.
     * @param raw    A numeric value used to calculate the animation cycles, influencing
     *               the results of both the bounce and color effects.
     * @return An animated string with a "bounce" effect followed by cyclically applied color codes.
     * If the input is null, the method returns null. If the color list is null or empty,
     * the method returns the string with only the bounce effect applied.
     */
    public static String animateFull(List<String> colors, String input, long raw) {
        String bounced = animateBounce(input, raw);
        return animatePerColorCycle(colors, bounced, raw);
    }
}
