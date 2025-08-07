package de.murmelmeister.library.exceptions;

/**
 * This class represents an exception thrown when there is an error related to YAML operations in the Murmel library.
 * It extends {@link RuntimeException} to allow for unchecked exceptions that can be thrown during runtime.
 */
public class YamlMurmelException extends RuntimeException {
    /**
     * Constructs a new YamlMurmelException with the specified detail message.
     *
     * @param message The detail message for this exception
     */
    public YamlMurmelException(String message) {
        super(message);
    }

    /**
     * Constructs a new YamlMurmelException with the specified detail message and cause.
     *
     * @param message The detail message for this exception.
     * @param cause   The cause of the exception, which can be retrieved later via the {@link Throwable#getCause()} method.
     */
    public YamlMurmelException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new YamlMurmelException with the specified cause.
     *
     * @param cause The cause of the exception, which can be retrieved later via the {@link Throwable#getCause()} method.
     */
    public YamlMurmelException(Throwable cause) {
        super(cause);
    }
}
