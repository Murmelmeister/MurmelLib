package de.murmelmeister.library.configuration;

import com.google.gson.*;
import de.murmelmeister.library.exceptions.JsonMurmelException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JsonMurmel is a utility class for reading and writing JSON data to a file.
 * It provides methods to get and set values in a JSON structure, supporting nested keys using dot notation.
 * The class is thread-safe, allowing concurrent read and write operations.
 */
public class JsonMurmel {
    private final Path path;
    private final Gson gson;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructs a new JsonMurmel instance for handling JSON operations.
     *
     * @param fileName The name of the file path to the JSON file that this instance will operate on.
     */
    public JsonMurmel(String fileName) {
        this.path = Path.of(fileName);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Constructs a new JsonMurmel instance for handling JSON operations.
     *
     * @param path The path to the JSON file that this instance will operate on.
     */
    public JsonMurmel(Path path) {
        this.path = path;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Retrieves the value associated with the specified key from the JSON file.
     * If the key does not exist or contains a null value, this method returns null.
     *
     * @param <T>  The type of the value to retrieve.
     * @param key  The key whose associated value is to be returned, using dot notation for nested keys (e.g., "parent.child.key").
     * @param type The expected class type of the value to be retrieved.
     * @return The value associated with the specified key, or null if the key does not exist or contains a null value.
     * @throws JsonMurmelException If an error occurs while reading the JSON file.
     */
    public <T> T getValue(String key, Class<T> type) {
        lock.readLock().lock();
        try {
            if (Files.notExists(path))
                return null;

            try {
                String content = Files.readString(path); // The max file size is 2GB
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();

                // Split the key into parts and navigate to the correct location in the JSON object
                String[] keys = key.split("\\.");
                JsonElement current = root;
                for (String part : keys) {
                    if (current == null || current.isJsonNull() || !current.isJsonObject())
                        return null; // Key isn't found or not an object
                    current = current.getAsJsonObject().get(part);
                }

                JsonElement element = current;
                if (element == null || element.isJsonNull())
                    return null; // Key isn't found
                return gson.fromJson(element, type);
            } catch (IOException e) {
                throw new JsonMurmelException("Error reading file: " + path, e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }


    /**
     * Retrieves the value associated with the specified key from the JSON file.
     * If the key does not exist or contains a null value, the fallback value will be returned.
     * Additionally, the fallback value will be set to the specified key in the JSON file.
     *
     * @param <T>      The type of the value to retrieve.
     * @param key      The key whose associated value is to be returned, using dot notation for nested keys (e.g., "parent.child.key").
     * @param type     The expected class type of the value to be retrieved.
     * @param fallback The fallback value to return and set if the key is not found or is null.
     * @return The value associated with the specified key, or the fallback value if the key does not exist or contains a null value.
     * @throws JsonMurmelException If an error occurs while reading or writing the JSON file.
     */
    public <T> T getValue(String key, Class<T> type, T fallback) {
        T value = getValue(key, type);
        if (value != null)
            return value;
        setValue(key, fallback);
        return fallback;
    }


    /**
     * Sets the value associated with the specified key in the JSON file.
     * If the key does not exist, it will be created. Nested keys can be specified
     * using dot notation (e.g., "parent.child.key"). If the file or parent directories
     * do not exist, they will be created.
     *
     * @param key   The key to associate the value with, using dot notation for nested keys.
     * @param value The value to associate with the specified key.
     * @throws JsonMurmelException If an error occurs while reading or writing the JSON file.
     */
    public <T> void setValue(String key, T value) {
        lock.writeLock().lock();
        try {
            JsonObject root;
            // Read the existing JSON file or create an object if it doesn't exist
            if (Files.exists(path)) {
                try {
                    String content = Files.readString(path); // The max file size is 2GB
                    root = JsonParser.parseString(content).getAsJsonObject();
                } catch (IOException e) {
                    throw new JsonMurmelException("Error reading file: " + path, e);
                }
            } else root = new JsonObject();

            // Split the key into parts and navigate to the correct location in the JSON object
            String[] keys = key.split("\\.");
            JsonObject current = root;
            for (int i = 0; i < keys.length - 1; i++) {
                String part = keys[i];
                if (!current.has(part) || !current.get(part).isJsonObject())
                    current.add(part, new JsonObject());
                current = current.getAsJsonObject(part);
            }

            String lastKey = keys[keys.length - 1];
            current.add(lastKey, gson.toJsonTree(value));

            // Write the updated JSON back to the file
            try {
                if (path.getParent() != null)
                    Files.createDirectories(path.getParent());

                Files.write(path, gson.toJson(root).getBytes());
            } catch (IOException e) {
                throw new JsonMurmelException("Error writing to file: " + path, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a key from the JSON file.
     * If the key is not found, no action is taken.
     * If the parent object becomes empty after removing the key, it will also be removed.
     *
     * @param key The key to remove, using dot notation for nested keys (e.g., "parent.child.key").
     * @throws JsonMurmelException If an error occurs while reading or writing the JSON file.
     */
    public void removeKey(String key) {
        lock.writeLock().lock();
        try {
            JsonObject root;
            if (Files.exists(path)) {
                try {
                    String content = Files.readString(path); // The max file size is 2GB
                    root = JsonParser.parseString(content).getAsJsonObject();
                } catch (IOException e) {
                    throw new JsonMurmelException("Error reading file: " + path, e);
                }
            } else return;

            // Split the key into parts and navigate to the correct location in the JSON object
            String[] keys = key.split("\\.");
            int depth = keys.length;

            JsonObject[] nodeStack = new JsonObject[depth];
            String[] keyStack = new String[depth];

            JsonObject current = root;
            for (int i = 0; i < depth - 1; i++) {
                String part = keys[i];
                if (!current.has(part) || !current.get(part).isJsonObject())
                    return; // Key isn't found or not an object
                nodeStack[i] = current;
                keyStack[i] = part;
                current = current.getAsJsonObject(part);
            }

            nodeStack[depth - 1] = current;
            keyStack[depth - 1] = keys[depth - 1];

            String lastKey = keys[depth - 1];
            if (current.has(lastKey)) {
                current.remove(lastKey);

                // If the current node is empty after removing the key, remove it from its parent
                for (int level = depth - 1; level > 0; level--) {
                    JsonObject removed = nodeStack[level];
                    if (!removed.isEmpty()) break;
                    JsonObject parent = nodeStack[level - 1];
                    String parentKey = keyStack[level - 1];
                    parent.remove(parentKey);
                }

                // Write the updated JSON back to the file
                try {
                    Files.write(path, gson.toJson(root).getBytes());
                } catch (IOException e) {
                    throw new JsonMurmelException("Error writing to file: " + path, e);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
