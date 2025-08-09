package de.murmelmeister.library.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.murmelmeister.library.exceptions.DatabaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * This class provides a thread-safe interface for managing database connections and executing SQL queries.
 * It uses HikariCP for connection pooling and supports both synchronous and asynchronous operations.
 * The class ensures that all database operations are performed within a transaction context, allowing for
 * rollback in case of errors.
 */
public final class Database {
    private final Logger logger = LoggerFactory.getLogger(Database.class);
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private volatile HikariDataSource dataSource;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    // Threshold in milliseconds for slow query logging
    private static final long SLOW_QUERY_THRESHOLD_MS = 500;

    /**
     * Establishes a connection to the database using the provided configuration parameters.
     * This method sets up a connection pool using HikariCP and ensures thread-safe configuration
     * using a write lock. It throws a {@link DatabaseException} if the connection cannot be established
     * or if the lock cannot be acquired in a timely manner.
     *
     * @param driverClassName The fully qualified class name of the database driver
     * @param url             The database URL to which the connection should be made
     * @param user            The username for database authentication
     * @param password        The password for database authentication
     * @throws DatabaseException If a failure occurs during the connection process or if the lock acquisition fails
     */
    public void connect(String driverClassName, String url, String user, String password) {
        try {
            if (!lock.writeLock().tryLock(10, TimeUnit.SECONDS))
                throw new DatabaseException("Failed to acquire write lock for database connection setup. Another thread is currently configuring the database connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while waiting for write lock to connect to the database", e);
        }

        try {
            if (dataSource != null && !dataSource.isClosed())
                dataSource.close();

            HikariConfig config = getHikariConfig(driverClassName, url, user, password);
            dataSource = new HikariDataSource(config);
            logger.info("Database connection established successfully. Driver: {}, URL: {}", driverClassName, url);
        } catch (Exception e) {
            throw new DatabaseException("Failed to connect to the database", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Safely disconnects from the database by performing the necessary cleanup and resource deallocation.
     * <p>
     * This method acquires a write lock to ensure thread-safe operations during the disconnection process.
     * It ensures that the database connection is closed if it is open, and the associated resources,
     * such as the executor for managing asynchronous operations, are properly shut down.
     * <p>
     * If the write lock cannot be acquired within a defined timeout, or if the thread is interrupted
     * while waiting for the lock, an exception will be thrown.
     * <p>
     * If there is an error while closing the database connection or shutting down the executor,
     * a DatabaseException will be thrown.
     * <p>
     * In the event of an interrupt during any wait operation, the corresponding thread will be re-interrupted.
     */
    public void disconnect() {
        try {
            if (!lock.writeLock().tryLock(10, TimeUnit.SECONDS))
                throw new DatabaseException("Failed to acquire write lock for database disconnection. Another thread is currently using the database connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while waiting for write lock to disconnect from the database", e);
        }

        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                dataSource = null;
                logger.info("Database connection closed successfully.");
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to close the database connection", e);
        } finally {
            lock.writeLock().unlock();
        }

        if (!executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                    executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Executes a SQL query and processes the first result using the provided {@link ResultSetProcessor}.
     * If no results are found, returns the specified fallback value.
     *
     * @param <T>        The type of the result object expected from the query.
     * @param sql        The SQL query to execute.
     * @param fallback   The fallback value to return if no results are found.
     * @param processor  The {@link ResultSetProcessor} to process the {@link ResultSet} and extract the desired result.
     * @param parameters The parameters to set in the prepared SQL query.
     * @return The processed result from the query if available, or the fallback value if no results are found.
     * @throws DatabaseException If a database access error occurs during query execution.
     */
    public <T> T query(String sql, T fallback, ResultSetProcessor<T> processor, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next())
                return processor.process(resultSet);
            return fallback;
        } catch (SQLException e) {
            logger.error("Error executing query: {} with parameters {}", sql, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute query.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Query statement [{}] took {} ms", sql, durationMs);
        }
    }

    /**
     * Executes a callable statement with the specified procedure name and parameters, processes the result set using
     * the provided {@link ResultSetProcessor}, and returns a result of type {@code T}.
     *
     * @param <T>           The type of the result to be returned.
     * @param procedureName The name of the stored procedure to be executed.
     * @param fallback      A fallback value to return if no results are found.
     * @param processor     A processor responsible for converting the result set into an instance of type {@code T}.
     * @param parameters    The parameters to be passed to the callable statement.
     * @return The processed result of type {@code T} or the fallback value if no results are found.
     * @throws DatabaseException If there is an error during execution of the callable statement.
     */
    public <T> T queryCallable(String procedureName, T fallback, ResultSetProcessor<T> processor, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = getCallableStatement(connection, procedureName, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next())
                return processor.process(resultSet);
            return fallback;
        } catch (SQLException e) {
            logger.error("Error executing callable: {} with parameters {}", procedureName, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute callable.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Callable statement [{}] took {} ms", procedureName, durationMs);
        }
    }

    /**
     * Asynchronously executes a query with the provided SQL and parameters, processes the result set using the provided
     * {@link ResultSetProcessor}, and returns a CompletableFuture containing the result of type {@code T}.
     *
     * @param <T>        The type of the result to be returned.
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param fallback   A fallback value to return if no results are found.
     * @param processor  A processor responsible for converting the result set into an instance of type {@code T}.
     * @param parameters The parameters to be passed to the prepared statement.
     * @return A CompletableFuture containing the processed result of type {@code T} or the fallback value if no results are found.
     */
    public <T> CompletableFuture<T> queryAsync(String sql, T fallback, ResultSetProcessor<T> processor, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> query(sql, fallback, processor, parameters), executor);
    }

    /**
     * Asynchronously executes a callable statement with the specified procedure name and parameters,
     * processes the result set using the provided {@link ResultSetProcessor}, and returns a CompletableFuture
     * containing the result of type {@code T}.
     *
     * @param <T>           The type of the result to be returned.
     * @param procedureName The name of the stored procedure to be executed.
     * @param fallback      A fallback value to return if no results are found.
     * @param processor     A processor responsible for converting the result set into an instance of type {@code T}.
     * @param parameters    The parameters to be passed to the callable statement.
     * @return A CompletableFuture containing the processed result of type {@code T} or the fallback value if no results are found.
     */
    public <T> CompletableFuture<T> queryCallableAsync(String procedureName, T fallback, ResultSetProcessor<T> processor, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> queryCallable(procedureName, fallback, processor, parameters), executor);
    }

    /**
     * Executes a query with the provided SQL and parameters, processes the result set using the provided
     * {@link ResultSetProcessor}, and returns a list of results of type {@code T}.
     *
     * @param <T>        The type of the elements to be returned in the result list.
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param processor  A processor responsible for converting each row in the result set into an instance of type {@code T}.
     * @param parameters The parameters to be passed to the prepared statement.
     * @return A list of results of type {@code T} obtained by processing the result set returned by the query.
     * @throws DatabaseException If there is an error during execution of the query.
     */
    public <T> List<T> queryList(String sql, ResultSetProcessor<T> processor, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            List<T> results = new java.util.ArrayList<>();
            while (resultSet.next())
                results.add(processor.process(resultSet));
            return results;
        } catch (SQLException e) {
            logger.error("Error executing query: {} with parameters {}", sql, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute query.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Query statement [{}] took {} ms", sql, durationMs);
        }
    }

    /**
     * Executes a callable statement with the specified procedure name and parameters, processes the result set using
     * the provided {@link ResultSetProcessor}, and returns a list of results of type {@code T}.
     *
     * @param <T>           The type of the elements to be returned in the result list.
     * @param procedureName The name of the stored procedure to be executed.
     * @param processor     A processor responsible for converting each row in the result set into an instance of type {@code T}.
     * @param parameters    The parameters to be passed to the callable statement.
     * @return A list of results of type {@code T} obtained by processing the result set returned by the callable statement.
     * @throws DatabaseException If there is an error during execution of the callable statement.
     */
    public <T> List<T> queryListCallable(String procedureName, ResultSetProcessor<T> processor, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = getCallableStatement(connection, procedureName, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (resultSet.next())
                results.add(processor.process(resultSet));
            return results;
        } catch (SQLException e) {
            logger.error("Error executing callable: {} with parameters {}", procedureName, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute callable.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Callable statement [{}] took {} ms", procedureName, durationMs);
        }
    }

    /**
     * Asynchronously executes a query with the provided SQL and parameters, processes the result set using the provided
     * {@link ResultSetProcessor}, and returns a CompletableFuture containing a list of results of type {@code T}.
     *
     * @param <T>        The type of the elements to be returned in the result list.
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param processor  A processor responsible for converting each row in the result set into an instance of type {@code T}.
     * @param parameters The parameters to be passed to the prepared statement.
     * @return A CompletableFuture containing a list of results of type {@code T} obtained by processing the result set returned by the query.
     */
    public <T> CompletableFuture<List<T>> queryListAsync(String sql, ResultSetProcessor<T> processor, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> queryList(sql, processor, parameters), executor);
    }

    /**
     * Asynchronously executes a callable statement with the specified procedure name and parameters,
     * processes the result set using the provided {@link ResultSetProcessor}, and returns a CompletableFuture
     * containing a list of results of type {@code T}.
     *
     * @param <T>           The type of the elements to be returned in the result list.
     * @param procedureName The name of the stored procedure to be executed.
     * @param processor     A processor responsible for converting each row in the result set into an instance of type {@code T}.
     * @param parameters    The parameters to be passed to the callable statement.
     * @return A CompletableFuture containing a list of results of type {@code T} obtained by processing the result set returned by the callable statement.
     */
    public <T> CompletableFuture<List<T>> queryListCallableAsync(String procedureName, ResultSetProcessor<T> processor, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> queryListCallable(procedureName, processor, parameters), executor);
    }

    /**
     * Checks if a record exists in the database by executing a query with the provided SQL and parameters.
     * This method is useful for verifying the existence of a record without returning any data.
     *
     * @param sql        The SQL query to check for existence; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return true if at least one record exists that matches the query, false otherwise.
     */
    public boolean exists(String sql, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (SQLException e) {
            logger.error("Error executing exists query: {} with parameters {}", sql, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute exists query.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Exists query [{}] took {} ms", sql, durationMs);
        }
    }

    /**
     * Checks if a stored procedure exists in the database by executing it with the provided parameters.
     * This method is useful for verifying the existence of a procedure without returning any data.
     *
     * @param procedureName The name of the stored procedure to check; must not be null or empty.
     * @param parameters    A variable-length array of parameter values to be set in the callable statement.
     *                      Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return true if the procedure exists and can be executed, false otherwise.
     */
    public boolean existsCallable(String procedureName, Object... parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = getCallableStatement(connection, procedureName, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (SQLException e) {
            logger.error("Error executing exists callable: {} with parameters {}", procedureName, Arrays.toString(parameters), e);
            throw new DatabaseException("Failed to execute exists callable.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Exists callable [{}] took {} ms", procedureName, durationMs);
        }
    }

    /**
     * Asynchronously checks if a record exists in the database by executing a query with the provided SQL and parameters.
     * This method is useful for verifying the existence of a record without returning any data.
     *
     * @param sql        The SQL query to check for existence; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return A CompletableFuture containing true if at least one record exists that matches the query, false otherwise.
     */
    public CompletableFuture<Boolean> existsAsync(String sql, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> exists(sql, parameters), executor);
    }

    /**
     * Asynchronously checks if a stored procedure exists in the database by executing it with the provided parameters.
     * This method is useful for verifying the existence of a procedure without returning any data.
     *
     * @param procedureName The name of the stored procedure to check; must not be null or empty.
     * @param parameters    A variable-length array of parameter values to be set in the callable statement.
     *                      Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return A CompletableFuture containing true if the procedure exists and can be executed, false otherwise.
     */
    public CompletableFuture<Boolean> existsCallableAsync(String procedureName, Object... parameters) {
        return CompletableFuture.supplyAsync(() -> existsCallable(procedureName, parameters), executor);
    }

    /**
     * Retrieves the auto-increment value for the specified table.
     * The table name must consist of letters, digits, and underscores only.
     *
     * @param tableName The name of the table to retrieve the auto-increment value for; must not be null or empty.
     * @return A CompletableFuture containing the auto-increment value, or null if the table does not exist.
     * @throws DatabaseException If the table name is invalid, or if an error occurs during the operation.
     */
    public CompletableFuture<Long> getAutoIncrement(String tableName) {
        // Validate table name: Only letters, digits, and underscores allowed.
        if (!NAME_PATTERN.matcher(tableName).matches())
            throw new DatabaseException("Invalid table name: " + tableName);

        String sql = "SHOW TABLE STATUS LIKE ?";
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = getPreparedStatement(connection, sql, tableName);
                 ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next())
                    return resultSet.getLong("Auto_increment");
                else {
                    logger.error("Table not found: {}", tableName);
                    return null;
                }
            } catch (SQLException e) {
                logger.error("Error retrieving auto-increment value for table: {}", tableName, e);
                throw new DatabaseException("Database retrieval error", e);
            }
        }, executor);
    }

    /**
     * Executes an update operation with the provided SQL and parameters.
     * The method prepares a statement, sets the parameters, and executes the update.
     *
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The number of rows affected by the update operation.
     */
    public int update(String sql, Object... parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getPreparedStatement(connection, sql, parameters)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Executes an update operation with the provided SQL and parameters, returning the generated keys.
     * The method prepares a statement that supports generated keys, executes the update, and retrieves the keys.
     *
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The generated key as a long value, or 0 if no keys were generated.
     */
    public long updateWithGeneratedKeys(String sql, Object... parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getKeyedStatement(connection, sql, parameters)) {
                int affectedRows = statement.executeUpdate();
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                        if (generatedKeys.next())
                            return generatedKeys.getLong(1);
                    }
                }
                return 0L;
            }
        });
    }

    /**
     * Executes a stored procedure with the specified name and input parameters.
     * The method prepares a callable statement, sets the parameters, and executes the update.
     *
     * @param procedureName The name of the stored procedure to call; must not be null or empty.
     * @param parameters    A variable-length array of parameter values to be set in the callable statement.
     *                      Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The number of rows affected by the update operation.
     */
    public int updateCallable(String procedureName, Object... parameters) {
        return executeInTransaction(connection -> {
            try (CallableStatement statement = getCallableStatement(connection, procedureName, parameters)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Executes a batch update with the provided SQL and parameters.
     * Each set of parameters is executed as a separate batch operation.
     *
     * @param sql        The SQL query to be executed in batch; must not be null or empty.
     * @param parameters A two-dimensional array of parameter values for each batch execution.
     *                   Each inner array represents the parameters for a single batch execution.
     * @return An array of update counts for each command in the batch; each element represents the number of rows affected by the corresponding command.
     */
    public int[] updateBatch(String sql, Object[]... parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getBatchStatement(connection, sql, parameters)) {
                return statement.executeBatch();
            }
        });
    }

    /**
     * Creates a new table in the database with the specified name and column definitions.
     * The table name must consist of letters, digits, and underscores only.
     *
     * @param tableName         The name of the table to be created; must not be null or empty.
     * @param columnsDefinition The SQL definition of the columns in the table; must not be null or empty.
     * @return The number of rows affected by the create operation (should be 0 for CREATE TABLE).
     * @throws DatabaseException If the table name is invalid, or if an error occurs during the operation.
     */
    public int createTable(String tableName, String columnsDefinition) {
        // Validate table name: Only letters, digits, and underscores allowed.
        if (!NAME_PATTERN.matcher(tableName).matches())
            throw new DatabaseException("Invalid table name: " + tableName);

        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + columnsDefinition + ")";
        return update(sql);
    }

    /**
     * Executes a database operation within a transaction, ensuring that the connection is properly managed.
     * The operation is executed with the connection set to non-auto-commit mode and serializable isolation level.
     *
     * @param operation The operation to execute, encapsulated in a {@link ConnectionOperation} functional interface.
     * @param <T>       The type of the result returned by the operation.
     * @return The result of the executed operation.
     * @throws DatabaseException If an error occurs during the transaction execution or rollback.
     */
    private <T> T executeInTransaction(ConnectionOperation<T> operation) {
        long startTime = System.nanoTime();
        Connection connection = null;
        boolean previousAutoCommit = true;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        try {
            connection = dataSource.getConnection();

            previousAutoCommit = connection.getAutoCommit();
            previousIsolation = connection.getTransactionIsolation();

            if (previousAutoCommit) connection.setAutoCommit(false);
            int desiredIsolation = Connection.TRANSACTION_SERIALIZABLE;
            if (previousIsolation != desiredIsolation)
                connection.setTransactionIsolation(desiredIsolation);

            connection.setReadOnly(false);
            connection.setNetworkTimeout(executor, 30_000); // Set network timeout to 30 seconds

            T result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            if (connection != null) {
                try {
                    if (!connection.getAutoCommit())
                        connection.rollback();
                } catch (SQLException rollbackException) {
                    logger.error("Failed to roll back transaction", rollbackException);
                }
            }
            throw new DatabaseException("Failed to execute transaction.", e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > SLOW_QUERY_THRESHOLD_MS)
                logger.warn("Slow query detected: {} ms", durationMs);

            if (connection != null)
                try {
                    if (connection.getAutoCommit() != previousAutoCommit)
                        connection.setAutoCommit(previousAutoCommit);

                    if (connection.getTransactionIsolation() != previousIsolation)
                        connection.setTransactionIsolation(previousIsolation);

                    connection.setReadOnly(false);
                    connection.setNetworkTimeout(executor, 0);
                } catch (SQLException e) {
                    logger.error("Failed to restore connection state", e);
                } finally {
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        logger.error("Failed to close connection", e);
                    }
                }

        }
    }

    /**
     * Creates a {@link CallableStatement} for executing a stored procedure with the specified name and input parameters.
     * The method prepares the statement, sets the parameters, and returns the configured {@link CallableStatement}.
     *
     * @param connection    The {@link Connection} to be used for creating the {@link CallableStatement}; must not be null.
     * @param procedureName The name of the stored procedure to call; must not be null or empty.
     * @param parameters    A variable-length array of parameter values to be set in the callable statement.
     *                      Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The prepared {@link CallableStatement} object, configured with the provided procedure name and parameters.
     * @throws SQLException If an error occurs while creating or configuring the {@link CallableStatement}.
     */
    private CallableStatement getCallableStatement(Connection connection, String procedureName, Object... parameters) throws SQLException {
        String placeholder = (parameters.length > 0) ? String.join(",", Collections.nCopies(parameters.length, "?")) : "";
        String sql = "{CALL " + procedureName + "(" + placeholder + ")}";
        CallableStatement statement = connection.prepareCall(sql);
        setParameters(statement, parameters);
        return statement;
    }

    /**
     * Creates a {@link PreparedStatement} that is configured for batch execution with the specified SQL query and input parameters.
     * The method prepares the statement, sets the parameters for each batch execution, and adds them to the batch.
     *
     * @param connection The {@link Connection} to be used for creating the {@link PreparedStatement}; must not be null.
     * @param sql        The SQL query string to be prepared; must not be null or empty.
     * @param parameters A two-dimensional array of parameter values to be set in the prepared statement.
     *                   Each inner array represents the parameters for a single batch execution.
     * @return The {@link PreparedStatement} object configured with the provided SQL and batch parameters, ready for execution.
     * @throws SQLException If a database access error occurs while creating, configuring, or setting the parameters of the {@link PreparedStatement}.
     */
    private PreparedStatement getBatchStatement(Connection connection, String sql, Object[]... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (Object[] paramSet : parameters) {
            setParameters(statement, paramSet); // In MariaDB or MySQL, it is possible to get an "SQLFeatureNotSupportedException"
            statement.addBatch();
        }
        return statement;
    }

    /**
     * Creates a {@link PreparedStatement} with support for generated keys based on the provided database connection,
     * SQL query, and input parameters.
     *
     * @param connection The {@link Connection} to be used for creating the {@link PreparedStatement}; must not be null.
     * @param sql        The SQL query string to be prepared; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The prepared {@link PreparedStatement} object, configured to retrieve generated keys and with
     * parameters set as specified.
     * @throws SQLException If an error occurs while creating or configuring the {@link PreparedStatement}.
     */
    private PreparedStatement getKeyedStatement(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        setParameters(statement, parameters);
        return statement;
    }

    /**
     * Creates a {@link PreparedStatement} from the provided database connection and SQL query,
     * and sets the parameters based on the given input values.
     *
     * @param connection The {@link Connection} to be used for creating the {@link PreparedStatement}; must not be null.
     * @param sql        The SQL query string to be prepared; must not be null or empty.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement.
     *                   Each value's type will determine the method used to set it (e.g., setString, setInt).
     * @return The prepared {@link PreparedStatement} object, with parameters set as specified.
     * @throws SQLException If an error occurs while creating or configuring the {@link PreparedStatement}.
     */
    private PreparedStatement getPreparedStatement(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        setParameters(statement, parameters);
        return statement;
    }

    /**
     * Sets the parameters for the provided {@link PreparedStatement} based on the given input values.
     * Supports a variety of parameter types, including primitives, object types, arrays, and SQL types.
     *
     * @param statement  The {@link PreparedStatement} instance where the parameters will be set; must not be null.
     * @param parameters A variable-length array of parameter values to be set in the prepared statement. Each value's
     *                   type will determine the method used to set it (e.g., setString, setInt). Null values are handled
     *                   appropriately as SQL NULL.
     * @throws SQLException If a database access error occurs, or if there are issues with the provided parameters.
     */
    private void setParameters(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            int parameterIndex = i + 1;
            Object parameter = parameters[i];

            if (parameter == null) {
                statement.setNull(parameterIndex, Types.NULL);
                continue;
            }

            switch (parameter) {
                // Common types
                case Boolean value -> statement.setBoolean(parameterIndex, value);
                case Byte value -> statement.setByte(parameterIndex, value);
                case Short value -> statement.setShort(parameterIndex, value);
                case Integer value -> statement.setInt(parameterIndex, value);
                case Long value -> statement.setLong(parameterIndex, value);
                case Float value -> statement.setFloat(parameterIndex, value);
                case Double value -> statement.setDouble(parameterIndex, value);
                case BigDecimal value -> statement.setBigDecimal(parameterIndex, value);
                case String value -> statement.setString(parameterIndex, value);
                case byte[] value -> statement.setBytes(parameterIndex, value);
                case Date value -> statement.setDate(parameterIndex, value);
                case Time value -> statement.setTime(parameterIndex, value);
                case Timestamp value -> statement.setTimestamp(parameterIndex, value);
                case Array value -> statement.setArray(parameterIndex, value);
                case URL value -> statement.setURL(parameterIndex, value);
                case UUID value -> statement.setString(parameterIndex, value.toString());

                // Reference Arrays
                case String[] value -> setSqlArray(statement, parameterIndex, "VARCHAR", value);
                case Integer[] value -> setSqlArray(statement, parameterIndex, "INTEGER", value);
                case Long[] value -> setSqlArray(statement, parameterIndex, "BIGINT", value);
                case Double[] value -> setSqlArray(statement, parameterIndex, "DOUBLE", value);
                case Float[] value -> setSqlArray(statement, parameterIndex, "FLOAT", value);
                case Boolean[] value -> setSqlArray(statement, parameterIndex, "BOOLEAN", value);
                case Byte[] value -> setSqlArray(statement, parameterIndex, "TINYINT", value);
                case Short[] value -> setSqlArray(statement, parameterIndex, "SMALLINT", value);
                case BigDecimal[] value -> setSqlArray(statement, parameterIndex, "DECIMAL", value);
                case Date[] value -> setSqlArray(statement, parameterIndex, "DATE", value);
                case Time[] value -> setSqlArray(statement, parameterIndex, "TIME", value);
                case Timestamp[] value -> setSqlArray(statement, parameterIndex, "TIMESTAMP", value);
                case Array[] value -> setSqlArray(statement, parameterIndex, "ARRAY", value);

                // Primitives Arrays
                case int[] value -> setSqlArray(statement, parameterIndex, "INTEGER", box(value));
                case long[] value -> setSqlArray(statement, parameterIndex, "BIGINT", box(value));
                case double[] value -> setSqlArray(statement, parameterIndex, "DOUBLE", box(value));
                case float[] value -> setSqlArray(statement, parameterIndex, "FLOAT", box(value));
                case short[] value -> setSqlArray(statement, parameterIndex, "SMALLINT", box(value));
                case boolean[] value -> setSqlArray(statement, parameterIndex, "BOOLEAN", box(value));
                case char[] value -> statement.setObject(parameterIndex, new String(value));
                default -> statement.setObject(parameterIndex, parameter);
            }
        }
    }

    /**
     * Sets an SQL array parameter in the provided {@link PreparedStatement}.
     *
     * @param statement The {@link PreparedStatement} where the SQL array will be set; must not be null.
     * @param index     The index of the parameter to be set in the {@link PreparedStatement}.
     * @param typeName  The SQL type name of the array elements (e.g., "VARCHAR", "INTEGER"); must not be null.
     * @param elements  The elements to include in the SQL array; must not be null.
     * @throws SQLException If a database access error occurs while creating or setting the array.
     */
    private void setSqlArray(PreparedStatement statement, int index, String typeName, Object[] elements) throws SQLException {
        Array sqlArray = statement.getConnection().createArrayOf(typeName, elements);
        statement.setArray(index, sqlArray);
    }

    /**
     * Converts a long array into an Object array, boxing each long value.
     *
     * @param array The long array to be converted; must not be null.
     * @return An Object array containing boxed Long values corresponding to the input array.
     */
    private Object[] box(long[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Converts an integer array into an Object array, boxing each int value.
     *
     * @param array The integer array to be converted; must not be null.
     * @return An Object array containing boxed Integer values corresponding to the input array.
     */
    private Object[] box(int[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Converts a double array into an Object array, boxing each double value.
     *
     * @param array The double array to be converted; must not be null.
     * @return An Object array containing boxed Double values corresponding to the input array.
     */
    private Object[] box(double[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Converts a float array into an Object array, boxing each float value.
     *
     * @param array The float array to be converted; must not be null.
     * @return An Object array containing boxed Float values corresponding to the input array.
     */
    private Object[] box(float[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Converts a short array into an Object array, boxing each short value.
     *
     * @param array The short array to be converted; must not be null.
     * @return An Object array containing boxed Short values corresponding to the input array.
     */
    private Object[] box(short[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Converts a boolean array into an Object array, boxing each boolean value.
     *
     * @param array The boolean array to be converted; must not be null.
     * @return An Object array containing boxed Boolean values corresponding to the input array.
     */
    private Object[] box(boolean[] array) {
        Object[] result = new Object[array.length];
        for (int i = 0; i < array.length; i++)
            result[i] = array[i];
        return result;
    }

    /**
     * Creates and configures a {@link HikariConfig} instance with the specified database connection parameters.
     * The resulting configuration object is set up with connection pooling and other performance-related settings.
     *
     * @param driverClassName The fully qualified name of the JDBC driver class to be used.
     * @param url             The JDBC URL for the database connection.
     * @param user            The username for the database connection.
     * @param password        The password for the database connection.
     * @return A configured {@link HikariConfig} instance ready to be used with a {@link HikariDataSource}.
     */
    private HikariConfig getHikariConfig(String driverClassName, String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return config;
    }

    /**
     * Retrieves the {@link ExecutorService} associated with this instance.
     * The returned executor can be used to manage asynchronous tasks.
     *
     * @return The {@link ExecutorService} instance used for managing task execution.
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Generates a SQL query to create a stored procedure with the specified name, input parameters, and body.
     * The procedure name is validated to ensure it only contains valid characters (letters, digits, and underscores).
     *
     * @param procedureName   The name of the procedure to create. Must only contain letters, digits, and underscores.
     * @param inputParameters A string representing the input parameters for the procedure in SQL syntax.
     * @param procedureBody   The body of the procedure containing the SQL statements to execute.
     * @return A string containing the SQL query to create the specified stored procedure.
     * @throws DatabaseException If the procedure name is invalid.
     */
    public static String getProcedureQuery(String procedureName, String inputParameters, String procedureBody) {
        // Validate procedure name: Only letters, digits, and underscores allowed.
        if (!NAME_PATTERN.matcher(procedureName).matches())
            throw new DatabaseException("Invalid procedure name: " + procedureName);

        return "CREATE PROCEDURE IF NOT EXISTS `" + procedureName + "`(" + inputParameters + ")\n" +
               "BEGIN\n    " + procedureBody + "\nEND;";
    }

    /**
     * Functional interface for processing ResultSet objects.
     *
     * @param <T> the type of the result
     */
    @FunctionalInterface
    private interface ConnectionOperation<T> {
        /**
         * Executes the operation using the provided database connection.
         *
         * @param connection The database connection to use
         * @return The result of the operation
         * @throws SQLException If a database access error occurs
         */
        T execute(Connection connection) throws SQLException;
    }
}
