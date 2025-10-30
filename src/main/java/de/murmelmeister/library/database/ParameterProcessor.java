package de.murmelmeister.library.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

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

    ParameterProcessor NO_OP = statement -> {};

    /**
     * Returns a ParameterProcessor that does nothing when executed.
     *
     * @return A no-op ParameterProcessor
     */
    static ParameterProcessor noop() {
        return NO_OP;
    }

    /**
     * Returns the provided processor or a no-op processor if the provided value is {@code null}.
     *
     * @param processor The processor to use
     * @return The supplied processor or a no-op processor if {@code null}
     */
    static ParameterProcessor of(ParameterProcessor processor) {
        return processor == null ? noop() : processor;
    }

    /**
     * Returns a new processor that first executes this processor and then invokes the provided {@code after} processor.
     *
     * @param after The processor to execute after this processor completes
     * @return A composed processor that performs the chained operations
     */
    default ParameterProcessor andThen(ParameterProcessor after) {
        ParameterProcessor next = of(after);
        return statement -> {
            execute(statement);
            next.execute(statement);
        };
    }

    /**
     * Returns a new processor that first executes the provided {@code before} processor and then invokes this processor.
     *
     * @param before The processor to execute before this processor
     * @return A composed processor that performs the chained operations
     */
    default ParameterProcessor compose(ParameterProcessor before) {
        ParameterProcessor previous = of(before);
        return statement -> {
            previous.execute(statement);
            execute(statement);
        };
    }

    /**
     * Combines all provided processors into a single processor executed in order.
     *
     * @param processors The processors to compose
     * @return A composite processor executing each processor in sequence
     */
    static ParameterProcessor combine(ParameterProcessor... processors) {
        Objects.requireNonNull(processors, "processors");
        ParameterProcessor result = noop();
        for (ParameterProcessor processor : processors)
            result = result.andThen(processor);
        return result;
    }
}
