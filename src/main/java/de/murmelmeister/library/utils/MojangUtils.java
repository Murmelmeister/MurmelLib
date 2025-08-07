package de.murmelmeister.library.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.UUID;

/**
 * MojangUtils is a utility class for interacting with the Mojang API to retrieve Minecraft player profiles.
 * It provides methods to fetch player UUIDs based on usernames and vice versa, as well as to retrieve
 * player profiles in JSON format.
 */
// Thanks Minestom: https://github.com/Minestom/Minestom/blob/master/src/main/java/net/minestom/server/utils/mojang/MojangUtils.java
public final class MojangUtils {
    private static final String FROM_UUID_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final String FROM_USERNAME_URL = "https://api.mojang.com/users/profiles/minecraft/%s";

    /**
     * Retrieves the UUID of a Minecraft player based on their username.
     * This method makes a request to the Mojang API to fetch the corresponding UUID.
     *
     * @param username The Minecraft player's username whose UUID is to be retrieved.
     * @return The {@link UUID} of the specified Minecraft player.
     * @throws IOException        If an I/O error occurs during the API request.
     * @throws URISyntaxException If the constructed URL for the API request is invalid.
     */
    public static UUID getUUID(String username) throws IOException, URISyntaxException {
        return UUID.fromString(retrieve(FROM_USERNAME_URL.replace("%s", username)).get("id")
                .getAsString()
                .replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5"
                ));
    }

    /**
     * Retrieves the username associated with the provided Minecraft player UUID.
     * This method makes a request to the Mojang API to fetch the corresponding username.
     *
     * @param playerUUID The {@link UUID} of the Minecraft player whose username is to be retrieved.
     * @return The username associated with the specified UUID.
     * @throws IOException        If an I/O error occurs during the API request.
     * @throws URISyntaxException If the constructed URL for the API request is invalid.
     */
    public static String getUsername(UUID playerUUID) throws IOException, URISyntaxException {
        return retrieve(FROM_UUID_URL.replace("%s", playerUUID.toString())).get("name").getAsString();
    }

    /**
     * Converts a {@link UUID} to its corresponding {@link JsonObject} representation.
     * This method delegates the UUID processing to its String-based counterpart.
     *
     * @param uuid The {@link UUID} of the Minecraft player whose profile is to be retrieved.
     * @return A {@link JsonObject} containing the profile data associated with the specified UUID,
     * or {@code null} in case of an error during the retrieval process.
     */
    public static JsonObject fromUuid(UUID uuid) {
        return fromUuid(uuid.toString());
    }

    /**
     * Retrieves a JSON object representing a Minecraft user profile based on the provided UUID.
     * The method makes a request to the Mojang API to fetch the profile information associated with the UUID.
     *
     * @param uuid The UUID of the Minecraft player whose profile is to be retrieved.
     * @return A {@link JsonObject} containing the profile data of the specified UUID, or {@code null}
     * if an error occurs during the retrieval process.
     */
    public static JsonObject fromUuid(String uuid) {
        try {
            return retrieve(FROM_UUID_URL.replace("%s", uuid));
        } catch (IOException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * Retrieves a JSON object representing a Minecraft user profile based on the provided username.
     * Makes a request to the Mojang API to retrieve the profile information.
     *
     * @param username The username of the Minecraft player whose profile is to be retrieved.
     * @return A {@link JsonObject} containing the profile data of the specified username, or {@code null} if an
     * error occurs during the retrieval process.
     */
    public static JsonObject fromUsername(String username) {
        try {
            return retrieve(FROM_USERNAME_URL.replace("%s", username));
        } catch (IOException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * Retrieves a JSON object from a given URL by making an HTTP request and parsing the response.
     *
     * @param url The URL to retrieve the JSON object from.
     * @return A {@link JsonObject} representing the parsed JSON response from the given URL.
     * @throws IOException        If an I/O exception occurs during the HTTP request or if the response is empty.
     * @throws URISyntaxException If the provided URL is not a valid URI.
     */
    private static JsonObject retrieve(String url) throws IOException, URISyntaxException {
        final String response = URLUtils.getText(url);
        if (response.isEmpty()) throw new IOException("The Mojang API is down :(");
        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
        if (jsonObject.has("errorMessage")) throw new IOException(jsonObject.get("errorMessage").getAsString());
        return jsonObject;
    }
}
