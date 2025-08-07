package de.murmelmeister.library.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Utility class for handling URL-related operations.
 * Provides methods to fetch content from a URL as a string.
 */
// Thanks Minestom: https://github.com/Minestom/Minestom/blob/master/src/main/java/net/minestom/server/utils/url/URLUtils.java
public final class URLUtils {
    /**
     * Fetches the content of the resource located at the specified URL as a string.
     * Opens an HTTP connection to the given URL, retrieves the response content,
     * and returns it as a string. In the case of an error response, the error
     * stream content is returned.
     *
     * @param url The URL of the resource to fetch content from
     * @return The content of the resource as a string
     * @throws IOException        If an I/O exception occurs while opening the connection or reading from it
     * @throws URISyntaxException If the provided URL is not a valid URI
     */
    public static String getText(String url) throws IOException, URISyntaxException {
        HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();

        final int responseCode = connection.getResponseCode();
        final InputStream inputStream;
        if (200 <= responseCode && responseCode <= 299)
            inputStream = connection.getInputStream();
        else inputStream = connection.getErrorStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder response = new StringBuilder();
        String currentLine;
        while ((currentLine = reader.readLine()) != null) response.append(currentLine);
        reader.close();
        return response.toString();
    }
}
