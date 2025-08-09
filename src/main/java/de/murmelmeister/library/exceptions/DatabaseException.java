package de.murmelmeister.library.exceptions;

/**
 * This class represents an exception thrown when there is an error related to database operations in the Murmel library.
 * It extends {@link RuntimeException} to allow for unchecked exceptions that can be thrown during runtime.
 */
public class DatabaseException extends RuntimeException {
    /**
     * Constructs a new DatabaseException with the specified detail message.
     *
     * @param message The detail message for this exception
     */
    public DatabaseException(String message) {
        super(message);
    }

    /**
     * Constructs a new DatabaseException with the specified detail message and cause.
     *
     * @param message The detail message for this exception
     * @param cause   The cause of the exception, which can be retrieved later via the {@link Throwable#getCause()} method
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new DatabaseException with the specified cause.
     *
     * @param cause The cause of the exception, which can be retrieved later via the {@link Throwable#getCause()} method
     */
    public DatabaseException(Throwable cause) {
        super(cause);
    }
}
