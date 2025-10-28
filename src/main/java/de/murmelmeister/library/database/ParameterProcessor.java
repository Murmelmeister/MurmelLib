package de.murmelmeister.library.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Represents a functional interface intended to handle parameter mapping for a
 * {@link PreparedStatement}. Implementations of this interface should provide the
 * necessary logic to configure parameter values on a prepared statement before execution.
 */
@FunctionalInterface
public interface ParameterProcessor {
    /**
     * Executes a parameter setting operation on the provided {@link PreparedStatement}.
     * Implementations should define the logic for mapping parameters to the prepared statement.
     *
     * @param statement The {@link PreparedStatement} on which parameter mapping and execution logic is applied
     * @throws SQLException If a database access error occurs
     */
    void execute(PreparedStatement statement) throws SQLException;
}
