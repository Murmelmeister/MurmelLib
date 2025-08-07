package de.murmelmeister.library.configuration;

import de.murmelmeister.library.exceptions.YamlMurmelException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.representer.Representer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * YamlMurmel is a utility class for reading and writing YAML configuration files.
 * It provides methods to get and set values in a YAML structure, supporting nested keys using dot notation.
 * The class is thread-safe, allowing concurrent read and write operations.
 */
public class YamlMurmel {
    private final Path path;
    private final Yaml yaml;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructs a new instance of YamlMurmel, a utility class for managing YAML configuration files.
     * This constructor initializes the YAML processor with the specified configuration options
     * provided by the builder.
     *
     * @param builder The builder containing all configuration options for initializing the
     *                YamlMurmel instance. The `Builder` includes the file name, formatting options,
     *                and settings related to processing YAML.
     */
    public YamlMurmel(Builder builder) {
        this.path = Path.of(builder.fileName);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(builder.indent);
        dumperOptions.setPrettyFlow(builder.prettyFlow);
        dumperOptions.setWidth(builder.width);
        dumperOptions.setProcessComments(builder.processComments);
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(builder.allowDuplicateKeys);
        loaderOptions.setMaxAliasesForCollections(builder.maxAliasesForCollections);
        loaderOptions.setWrappedToRootException(builder.wrappedToRootException);
        loaderOptions.setProcessComments(builder.processComments);

        SafeConstructor safeConstructor = new SafeConstructor(loaderOptions);
        Representer representer = new Representer(dumperOptions);
        this.yaml = new Yaml(safeConstructor, representer, dumperOptions, loaderOptions);
    }

    /**
     * Retrieves a value from the YAML configuration file for the specified dot-delimited key.
     * This method navigates the YAML structure based on the provided key and returns the
     * corresponding value if it exists and matches the expected type.
     *
     * @param <T>  The type of the value to be retrieved.
     * @param key  The dot-delimited key path where the value should be retrieved
     *             (e.g., "parent.child.grandchild").
     * @param type The expected type of the value to be retrieved.
     * @return The value associated with the specified key if it exists and matches the expected
     * type, or null if the key does not exist or is not a match.
     * @throws YamlMurmelException If an error occurs while reading the YAML file.
     */
    public <T> T getValue(String key, Class<T> type) {
        lock.readLock().lock();
        try {
            if (Files.notExists(path))
                return null;

            try (InputStream inputStream = Files.newInputStream(path)) {
                Object data = yaml.load(inputStream);

                if (data instanceof Map<?, ?> map) {
                    // Split the key into parts and navigate to the correct location in the YAML
                    String[] keys = key.split("\\.");
                    Object current = map;

                    for (String k : keys) {
                        if (!(current instanceof Map<?, ?> currentMap))
                            return null; // Key not found
                        current = currentMap.get(k);
                    }

                    if (current == null)
                        return null; // Key does not exist

                    if (!type.isInstance(current))
                        throw new ClassCastException("Object " + key + " is not an instance of " + type.getName());

                    return type.cast(current);
                }

                return null; // Not a map
            } catch (IOException e) {
                throw new YamlMurmelException("Error reading file: " + path, e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieves a value from the YAML configuration file for the specified dot-delimited key.
     * If the value does not exist, the provided fallback value is used, set in the configuration,
     * and returned.
     *
     * @param <T>      The type of the value to be retrieved.
     * @param key      The dot-delimited key path where the value should be retrieved
     *                 (e.g., "parent.child.grandchild").
     * @param type     The expected type of the value to be retrieved.
     * @param fallback The fallback value to return and set in the YAML file if the key does not exist.
     * @return The value retrieved for the specified key, or the fallback value if the key does not exist.
     * @throws YamlMurmelException If an error occurs while reading or writing the YAML file.
     */
    public <T> T getValue(String key, Class<T> type, T fallback) {
        T value = getValue(key, type);
        if (value != null)
            return value;
        // If the value is null, set it to the fallback value in the YAML file
        setValue(key, fallback);
        return fallback;
    }

    /**
     * Sets a value in the YAML configuration file for the specified dot-delimited key.
     * If the key path does not exist, it will be created along with any necessary parent keys.
     * The updated configuration is saved back to the YAML file.
     *
     * @param key   The dot-delimited key path where the value should be set
     *              (e.g., "parent.child.grandchild").
     * @param value The value to be set at the specified key.
     * @throws YamlMurmelException If an error occurs while reading or writing the YAML file.
     */
    public <T> void setValue(String key, T value) {
        lock.writeLock().lock();
        try {
            Map<String, Object> root;
            // Read the existing YAML file or create an empty map if it doesn't exist
            if (Files.exists(path)) {
                try (InputStream inputStream = Files.newInputStream(path)) {
                    root = new LinkedHashMap<>(yaml.load(inputStream));
                } catch (IOException e) {
                    throw new YamlMurmelException("Error reading file: " + path, e);
                }
            } else root = new LinkedHashMap<>();

            // Split the key into parts and navigate to the correct location in the YAML
            String[] keys = key.split("\\.");
            Map<String, Object> current = root;

            for (int i = 0; i < keys.length - 1; i++) {
                String part = keys[i];
                Object next = current.get(part);

                if (next instanceof Map<?, ?> rawNext) {
                    Map<String, Object> nextMap = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : rawNext.entrySet())
                        nextMap.put(entry.getKey().toString(), entry.getValue());
                    current.put(part, nextMap);
                    current = nextMap;
                } else {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(part, newMap);
                    current = newMap;
                }
            }

            current.put(keys[keys.length - 1], value);

            // Write the updated map back to the YAML file
            try {
                if (path.getParent() != null)
                    Files.createDirectories(path.getParent());

                Files.writeString(path, yaml.dump(root));
            } catch (IOException e) {
                throw new YamlMurmelException("Error writing to file: " + path, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a specified key and its associated value from the YAML configuration file.
     * This method traverses the YAML structure using a dot-delimited key,
     * removes the specified key, and cleans up any empty parent nodes if necessary.
     * Updates are saved back to the YAML file.
     *
     * @param key The dot-delimited key path to be removed from the YAML configuration
     *            (e.g., "parent.child.grandchild").
     * @throws YamlMurmelException If an error occurs while reading or writing the YAML file.
     */
    public void removeKey(String key) {
        lock.writeLock().lock();
        try {
            Map<String, Object> root;
            // Read the existing YAML file or return if it doesn't exist
            if (Files.exists(path)) {
                try (InputStream inputStream = Files.newInputStream(path)) {
                    root = new LinkedHashMap<>(yaml.load(inputStream));
                } catch (IOException e) {
                    throw new YamlMurmelException("Error reading file: " + path, e);
                }
            } else return; // Nothing to remove if the file doesn't exist


            // Split the key into parts and navigate to the correct location in the YAML
            String[] keys = key.split("\\.");
            int depth = keys.length;

            List<Map<String, Object>> nodeStack = new LinkedList<>(Collections.nCopies(depth, null));
            List<String> keyStack = new ArrayList<>(Collections.nCopies(depth, null));

            Map<String, Object> current = root;
            for (int i = 0; i < depth - 1; i++) {
                String part = keys[i];
                Object next = current.get(part);

                if (!(next instanceof Map<?, ?> rawNext)) {
                    return; // Key not found
                }
                Map<String, Object> nextMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawNext.entrySet())
                    nextMap.put(entry.getKey().toString(), entry.getValue());

                // Update the stacks with the current node and key
                nodeStack.set(i, current);
                keyStack.set(i, part);
                current.put(part, nextMap);
                current = nextMap;
            }

            // Set the current node and key for the last part
            nodeStack.set(depth - 1, current);
            keyStack.set(depth - 1, keys[keys.length - 1]);

            // Remove the last key from the current map
            String lastKey = keys[depth - 1];
            if (current.containsKey(lastKey)) {
                current.remove(lastKey);

                // Clean up empty parent nodes
                for (int i = depth - 1; i > 0; i--) {
                    Map<String, Object> removed = nodeStack.get(i);
                    if (!removed.isEmpty()) break;
                    Map<String, Object> parent = nodeStack.get(i - 1);
                    String keyToRemove = keyStack.get(i - 1);
                    parent.remove(keyToRemove);
                }

                // Write the updated map back to the YAML file
                try {
                    Files.writeString(path, yaml.dump(root));
                } catch (IOException e) {
                    throw new YamlMurmelException("Error writing to file: " + path, e);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds a comment to a specific key in the YAML configuration file. The comment can be added
     * either as an inline comment or a block comment depending on the specified parameter. If the
     * key or file does not exist, the operation is skipped. The updated YAML configuration is
     * saved back to the file.
     *
     * @param key     The dot-delimited key path where the comment is to be added
     *                (e.g., "parent.child.key").
     * @param comment The content of the comment to add.
     * @param inLine  Specifies whether the comment should be added as an inline comment or a
     *                block comment. If true, the comment will be added inline; otherwise, it will
     *                be added as a block comment.
     * @throws YamlMurmelException If an error occurs while reading or writing the YAML file.
     */
    public void addComment(String key, String comment, boolean inLine) {
        lock.writeLock().lock();
        try {
            Node rootNode;
            // Read the existing YAML file or return if it doesn't exist
            if (Files.exists(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path)) {
                    rootNode = yaml.compose(reader);
                } catch (IOException e) {
                    throw new YamlMurmelException("Error reading file: " + path, e);
                }
            } else return; // Nothing to remove if the file doesn't exist

            if (!(rootNode instanceof MappingNode))
                return; // Not a map, nothing to comment

            String[] keys = key.split("\\.");
            NodeTuple tuple = findNodeTuple(rootNode, keys);

            if (tuple == null)
                return; // Key not found, nothing to comment

            if (inLine) setCommentInLine(tuple.getValueNode(), comment);
            else setCommentBlock(tuple.getKeyNode(), comment);

            // Write the updated YAML back to the file
            try {
                if (path.getParent() != null)
                    Files.createDirectories(path.getParent());

                StringWriter writer = new StringWriter();
                yaml.serialize(rootNode, writer);
                Files.writeString(path, writer.toString());
            } catch (IOException e) {
                throw new YamlMurmelException("Error writing to file: " + path, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Sets an inline comment for a given node in the YAML structure.
     * This method appends the specified comment to the list of existing inline comments
     * associated with the node.
     *
     * @param node    The node for which the inline comment is to be set.
     * @param comment The content of the inline comment to add.
     */
    private void setCommentInLine(Node node, String comment) {
        CommentLine line = new CommentLine(null, null, " " + comment, CommentType.IN_LINE);
        List<CommentLine> comments = node.getInLineComments();
        comments.add(line);
        node.setInLineComments(comments);
    }

    /**
     * Sets a block comment for a given node in the YAML structure.
     * This method adds the specified comment to the list of block comments
     * associated with the node.
     *
     * @param node    The node for which the block comment is to be set.
     * @param comment The content of the block comments to add.
     */
    private void setCommentBlock(Node node, String comment) {
        CommentLine line = new CommentLine(null, null, " " + comment, CommentType.BLOCK);
        List<CommentLine> comments = node.getBlockComments();
        comments.add(line);
        node.setBlockComments(comments);
    }

    /**
     * Recursively searches for a specific node tuple within a YAML structure based on a given path.
     * This method traverses a tree of nodes, navigating through mappings and matching keys to elements
     * provided in the path array to locate the corresponding node tuple.
     *
     * @param root The root node of the YAML structure to search from.
     * @param path An array of strings representing the hierarchical path to the desired node.
     *             Each element in the array corresponds to a key in the YAML structure.
     * @return The {@code NodeTuple} corresponding to the final key in the path if it exists, or {@code null}
     * if the key is not found in the YAML structure.
     */
    private NodeTuple findNodeTuple(Node root, String[] path) {
        if (!(root instanceof MappingNode mappingNode))
            return null;

        String currentKey = path[0];
        for (NodeTuple tuple : mappingNode.getValue()) {
            Node keyNode = tuple.getKeyNode();
            Node valueNode = tuple.getValueNode();

            if (keyNode instanceof ScalarNode scalarNode
                && scalarNode.getValue().equals(currentKey)) {
                if (path.length == 1)
                    return tuple; // Found the key, return the tuple
                String[] remainingPath = Arrays.copyOfRange(path, 1, path.length);
                return findNodeTuple(valueNode, remainingPath); // Recursive for the next part of the path
            }
        }
        return null; // Key not found
    }

    /**
     * Creates and returns a new instance of the {@code Builder} class for configuring and
     * constructing a {@code YamlMurmel} instance.
     *
     * @param fileName The name of the YAML file to be managed by the {@code YamlMurmel} instance.
     *                 This is a required parameter and sets the context for the YAML operations.
     * @return A new {@code Builder} instance initialized with the specified file name.
     */
    public static Builder builder(String fileName) {
        return new Builder(fileName);
    }

    /**
     * A Builder class for constructing instances of YamlMurmel with customizable options.
     * This class allows setting various parameters such as indentation, flow style, width,
     * comment processing, duplicate key handling, and more.
     */
    public static final class Builder {
        private final String fileName;
        private int indent = 2;
        private boolean prettyFlow = true;
        private int width = 80;
        private boolean processComments = true;
        private boolean allowDuplicateKeys = false;
        private int maxAliasesForCollections = 100;
        private boolean wrappedToRootException = true;

        /**
         * Constructs a new Builder instance with the specified file name.
         *
         * @param fileName The name of the file to be associated with this Builder
         */
        private Builder(String fileName) {
            this.fileName = fileName;
        }

        /**
         * Sets the indent size to be used and returns the Builder instance.
         *
         * @param indent The number of spaces to use for indentation.
         * @return The Builder instance with the updated indent setting.
         */
        public Builder indent(int indent) {
            this.indent = indent;
            return this;
        }

        /**
         * Configures whether the flow presentation should be formatted in a "pretty" style.
         *
         * @param prettyFlow A boolean indicating whether to enable or disable pretty flow formatting.
         * @return The Builder instance with the updated pretty flow setting.
         */
        public Builder prettyFlow(boolean prettyFlow) {
            this.prettyFlow = prettyFlow;
            return this;
        }

        /**
         * Sets the maximum width for formatting and returns the Builder instance.
         *
         * @param width The maximum width to be used for formatting.
         * @return The Builder instance with the updated width setting.
         */
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /**
         * Configures whether comments should be processed and included during the building process.
         *
         * @param processComments A boolean indicating whether to enable or disable processing of comments.
         * @return The Builder instance with the updated process comments setting.
         */
        public Builder processComments(boolean processComments) {
            this.processComments = processComments;
            return this;
        }

        /**
         * Configures whether duplicate keys are allowed in the data structure being processed.
         *
         * @param allowDuplicateKeys A boolean indicating whether to allow or disallow duplicate keys.
         * @return The Builder instance with the updated allows duplicate keys setting.
         */
        public Builder allowDuplicateKeys(boolean allowDuplicateKeys) {
            this.allowDuplicateKeys = allowDuplicateKeys;
            return this;
        }

        /**
         * Sets the maximum allowed aliases for collections and returns the Builder instance.
         *
         * @param maxAliasesForCollections The maximum number of aliases permitted for collections.
         * @return The Builder instance with the updated maximum aliases for collection setting.
         */
        public Builder maxAliasesForCollections(int maxAliasesForCollections) {
            this.maxAliasesForCollections = maxAliasesForCollections;
            return this;
        }

        /**
         * Configures whether exceptions should be wrapped to root exceptions during processing.
         *
         * @param wrappedToRootException A boolean indicating whether to enable or disable wrapping to root exceptions.
         * @return The Builder instance with the updated wrapped to root exception setting.
         */
        public Builder wrappedToRootException(boolean wrappedToRootException) {
            this.wrappedToRootException = wrappedToRootException;
            return this;
        }

        /**
         * Builds and returns a new instance of YamlMurmel configured with the current state
         * of the Builder instance.
         *
         * @return A new YamlMurmel instance configured with the settings defined in the builder.
         */
        public YamlMurmel build() {
            return new YamlMurmel(this);
        }
    }
}
