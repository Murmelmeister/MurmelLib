package de.murmelmeister.library.utils;

import java.util.List;

/**
 * Utility class for animating strings with effects such as bouncing and color cycling.
 * This class provides methods to create animated string effects based on input strings,
 * color codes, and a floating-point speed multiplier that influences the animation state.
 */
public final class AnimationUtils {
    private static final double NANOS_PER_TICK = 50_000_000D; // 1 / 20 seconds

    private AnimationUtils() {
    }

    /**
     * Converts the supplied speed multiplier into a phase using the current system time expressed
     * in Minecraft ticks as the base. The phase increases (or decreases) continuously over time,
     * ensuring deterministic animation steps driven by the supplied speed factor alone.
     *
     * @param speed The speed multiplier; positive values move the animation forward over time,
     *              negative values move it in reverse. A value of {@code 1.0f} corresponds to
     *              the base Minecraft tick rate (20 ticks per second).
     * @return The current phase value for the bounce animation
     */
    private static double bouncePhase(float speed) {
        double ticks = System.nanoTime() / NANOS_PER_TICK;
        return ticks * speed;
    }

    /**
     * Converts the supplied speed multiplier into a phase for color animations. The value represents
     * how many color steps have elapsed with {@code 1.0f} equating to 20 ticks (one second) per step.
     *
     * @param speed The speed multiplier. Values greater than 1 speed up the cycle (fewer ticks per step),
     *              values between 0 and 1 slow it down (more ticks per step), and negative values reverse it.
     * @return The current phase value for color animations
     */
    private static double colorPhase(float speed) {
        double ticks = System.nanoTime() / NANOS_PER_TICK;
        if (speed == 0F) return 0D;
        double absSpeed = Math.abs(speed);
        double ticksPerStep = 20D / absSpeed; // 1.0f -> 20 ticks, 2.0f -> 10 ticks, 0.5f -> 40 ticks
        double phase = ticks / ticksPerStep;
        return speed > 0 ? phase : -phase;
    }

    /**
     * Animates a "bounce" effect on the input string by dynamically adjusting its length
     * based on time-derived position. The length modification occurs cyclically and can be
     * accelerated or slowed down via the {@code speed} multiplier.
     *
     * @param input The input strings to be animated; if null, the method returns null
     * @param speed The speed multiplier measured against the game tick rate; values greater than 1 speed up
     *              the animation, values between 0 and 1 slow it down, and negative values reverse it
     * @return A substring of the input, adjusted according to the bounce animation logic;
     * if the input is null or its length is zero, the method returns the input as is
     */
    public static String animateBounce(String input, float speed) {
        if (input == null) return null;
        int length = input.length();
        if (length == 0) return input;

        double cycle = length * 2.0;
        double index = bouncePhase(speed) % cycle;
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
     * @param speed  A speed multiplier measured against the game tick rate; {@code 1.0f} advances the color
     *               once every 20 ticks (one second), {@code 2.0f} every 10 ticks, {@code 0.5f} every 40 ticks,
     *               and negative values reverse the direction
     * @return The input string prefixed with the selected color code, or the input as is
     * if colors are null, empty, or input is null
     */
    public static String animateColor(List<String> colors, String input, float speed) {
        if (colors == null || colors.isEmpty() || input == null) return input;

        int size = colors.size();
        double phase = colorPhase(speed);
        long stepIndex = (long) Math.floor(phase);
        int idx = Math.floorMod(stepIndex, size);

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
     * @param speed  A speed multiplier measured against the game tick rate; {@code 1.0f} advances one step
     *               every 20 ticks, {@code 2.0f} every 10 ticks, {@code 0.5f} every 40 ticks, and negative
     *               values reverse the direction
     * @return The input string with each character prefixed by a cyclically assigned color code
     * from the list. If colors are null, empty, or input is null, the method returns the
     * input string unmodified.
     */
    public static String animatePerColorCycle(List<String> colors, String input, float speed) {
        if (colors == null || colors.isEmpty() || input == null) return input;
        StringBuilder sb = new StringBuilder();
        int size = colors.size();
        double basePhase = colorPhase(speed);

        for (int i = 0; i < input.length(); i++) {
            double phase = basePhase + i;
            long stepIndex = (long) Math.floor(phase);
            int colorIndex = Math.floorMod(stepIndex, size);
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
     * @param speed  A speed multiplier measured against the game tick rate, influencing
     *               the results of both the bounce and color effects; {@code 1.0f} advances once every
     *               20 ticks, {@code 2.0f} every 10 ticks, {@code 0.5f} every 40 ticks, and negative values
     *               reverse the direction
     * @return An animated string with a "bounce" effect followed by cyclically applied color codes.
     * If the input is null, the method returns null. If the color list is null or empty,
     * the method returns the string with only the bounce effect applied.
     */
    public static String animateFull(List<String> colors, String input, float speed) {
        String bounced = animateBounce(input, speed);
        return animatePerColorCycle(colors, bounced, speed);
    }
}
