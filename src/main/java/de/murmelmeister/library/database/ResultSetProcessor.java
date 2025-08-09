package de.murmelmeister.library.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A functional interface for processing a {@link ResultSet}.
 * This interface allows for custom processing of database query results.
 *
 * @param <T> The type of the result produced by processing the {@link ResultSet}.
 */
@FunctionalInterface
public interface ResultSetProcessor<T> {
    /**
     * Processes the provided {@link ResultSet} and extracts or manipulates the data as defined by the implementation.
     *
     * @param resultSet The {@link ResultSet} containing the data to process; must not be null
     * @return The result of processing the {@link ResultSet}, as defined by the implementation
     * @throws SQLException If a database access error occurs or the {@link ResultSet} is invalid
     */
    T process(ResultSet resultSet) throws SQLException;
}
