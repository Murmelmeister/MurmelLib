package de.murmelmeister.library.utils;

import java.util.List;

/**
 * Utility class for animating strings with effects such as bouncing and color cycling.
 * This class provides methods to create animated string effects based on input strings,
 * color codes, and a floating-point speed multiplier that influences the animation state.
 */
public final class AnimationUtils {
    private static final double NANOS_PER_SECOND = 1_000_000_000D;

    private AnimationUtils() {
    }

    /**
     * Converts the supplied speed multiplier into a phase using the current system time as the base.
     * The phase increases continuously over time, ensuring deterministic animation steps driven by
     * the supplied speed factor alone.
     *
     * @param speed The speed multiplier; positive values move the animation forward over time,
     *              negative values move it in reverse. The value is interpreted as cycles per second.
     * @return The current phase value for the animation
     */
    private static double currentPhase(float speed) {
        double seconds = System.nanoTime() / NANOS_PER_SECOND;
        return seconds * speed;
    }

    /**
     * Animates a "bounce" effect on the input string by dynamically adjusting its length
     * based on time-derived position. The length modification occurs cyclically and can be
     * accelerated or slowed down via the {@code speed} multiplier.
     *
     * @param input The input strings to be animated; if null, the method returns null
     * @param speed The speed multiplier measured in cycles per second; values greater than 1 speed up
     *              the animation, values between 0 and 1 slow it down, and negative values reverse it
     * @return A substring of the input, adjusted according to the bounce animation logic;
     * if the input is null or its length is zero, the method returns the input as is
     */
    public static String animateBounce(String input, float speed) {
        if (input == null) return null;
        int length = input.length();
        if (length == 0) return input;

        double cycle = length * 2.0;
        double index = currentPhase(speed) % cycle;
        if (index < 0) index += cycle;

        double distance = (index <= length)
                ? (length - index)
                : (index - length);

        int subLength = (int) Math.round(distance);
        subLength = Math.max(0, Math.min(subLength, length));
        return input.substring(0, subLength);
    }

    /**
     * Animates a given input string by prefixing it with a color code from a list,
     * chosen based on a cyclic calculation using the provided speed value. The current system time
     * is used to derive the animation phase, meaning the animation continues to progress even if the
     * method is invoked at irregular intervals.
     *
     * @param colors A list of color codes to select from; must not be null or empty
     * @param input  The input strings to be animated; if null, the method returns null
     * @param speed  A speed multiplier measured in cycles per second; values greater than 1 advance
     *               through the colors faster, while values between 0 and 1 slow it down
     * @return The input string prefixed with the selected color code, or the input as is
     * if colors are null, empty, or input is null
     */
    public static String animateColor(List<String> colors, String input, float speed) {
        if (colors == null || colors.isEmpty() || input == null) return input;

        int size = colors.size();
        double phase = currentPhase(speed);
        long scaled = (long) Math.floor(phase);
        int idx = Math.floorMod(scaled, size);

        return colors.get(idx) + input;
    }

    /**
     * Animates a given input string by interleaving it with color codes from a list,
     * based on a cyclic calculation using the provided speed value and the current system time. Each character in the input
     * string is prefixed with a color code selected in a round-robin fashion from the provided list.
     *
     * @param colors A list of color codes to select from; must not be null or empty. Each color
     *               is assigned cyclically to the characters in the input string.
     * @param input  The input strings to be animated; if null, the method returns null.
     *               If the color list is null or empty, the input string is returned as is.
     * @param speed  A speed multiplier measured in cycles per second; values greater than 1 advance
     *               through the colors faster, while values between 0 and 1 slow it down
     * @return The input string with each character prefixed by a cyclically assigned color code
     * from the list. If colors are null, empty, or input is null, the method returns the
     * input string unmodified.
     */
    public static String animatePerColorCycle(List<String> colors, String input, float speed) {
        if (colors == null || colors.isEmpty() || input == null) return input;
        StringBuilder sb = new StringBuilder();
        int size = colors.size();
        double basePhase = currentPhase(speed);

        for (int i = 0; i < input.length(); i++) {
            double phase = basePhase + i;
            long scaled = (long) Math.floor(phase);
            int colorIndex = Math.floorMod(scaled, size);
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
     * @param speed  A speed multiplier measured in cycles per second, influencing
     *               the results of both the bounce and color effects; values greater than 1 speed up
     *               the combined animation, while values between 0 and 1 slow it down
     * @return An animated string with a "bounce" effect followed by cyclically applied color codes.
     * If the input is null, the method returns null. If the color list is null or empty,
     * the method returns the string with only the bounce effect applied.
     */
    public static String animateFull(List<String> colors, String input, float speed) {
        String bounced = animateBounce(input, speed);
        return animatePerColorCycle(colors, bounced, speed);
    }
}
