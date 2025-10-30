package de.murmelmeister.library.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.murmelmeister.library.exceptions.DatabaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * This class provides a thread-safe interface for managing database connections and executing SQL queries.
 * It uses HikariCP for connection pooling and supports both synchronous and asynchronous operations.
 * The class ensures that all database operations are performed within a transaction context, allowing for
 * rollback in case of errors.
 */
public final class Database implements AutoCloseable {
    private static final long SLOW_QUERY_THRESHOLD_MS = 500;
    private static final int EXECUTOR_POOL_SIZE = 10;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final ThreadFactory WORKER_THREAD_FACTORY = new DaemonThreadFactory("database-worker-");
    private static final ThreadFactory NETWORK_TIMEOUT_THREAD_FACTORY = new DaemonThreadFactory("database-network-timeout-");

    private final Logger logger = LoggerFactory.getLogger(Database.class);
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile ExecutorService executor;
    private volatile ExecutorService networkTimeoutExecutor;
    private volatile HikariDataSource dataSource;
    private volatile Integer transactionIsolationLevel;

    /**
     * Establishes a database connection using the provided HikariConfig. Configures and initializes
     * the data source and executor as needed. Ensures thread-safe connection setup by acquiring
     * a write lock during the configuration process. Logs a success message upon establishing the
     * connection or throws an exception in case of failure.
     *
     * @param config The configuration object for setting up the HikariCP data source
     * @throws DatabaseException If the connection setup fails, the lock cannot be acquired, or the thread is interrupted
     */
    private void connect(HikariConfig config) {
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

            if (executor == null || executor.isShutdown())
                executor = createExecutor();

            if (networkTimeoutExecutor == null || networkTimeoutExecutor.isShutdown())
                networkTimeoutExecutor = createNetworkTimeoutExecutor();

            dataSource = new HikariDataSource(config);
            logger.info("Database connection established successfully. URL: {}", dataSource.getJdbcUrl());
        } catch (Exception e) {
            shutdownExecutor(executor);
            executor = null;
            shutdownExecutor(networkTimeoutExecutor);
            networkTimeoutExecutor = null;
            closeDataSourceQuietly(dataSource);
            dataSource = null;
            throw new DatabaseException("Failed to connect to the database", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Establishes a connection to the database using the properties defined in the specified file.
     * This method creates a HikariConfig object from the given file and delegates the connection
     * setup to another method for further processing.
     *
     * @param propertyFileName The name of the file containing database configuration properties.
     *                         Must not be null or empty. The file should include necessary
     *                         configurations such as JDBC URL, username, and password.
     */
    public void connect(String propertyFileName) {
        HikariConfig config = new HikariConfig(propertyFileName);
        connect(config);
    }

    /**
     * Establishes a connection to the database using the provided properties.
     * Creates a HikariConfig object from the properties and delegates the connection setup
     * to another method.
     *
     * @param properties The property object containing database configuration parameters.
     *                   Must not be null and should include the necessary information such
     *                   as JDBC URL, username, and password.
     */
    public void connect(Properties properties) {
        HikariConfig config = new HikariConfig(properties);
        connect(config);
    }

    /**
     * Establishes a connection to the database using the specified URL, username, and password.
     * Creates a HikariConfig object configured with the provided parameters and delegates
     * the connection setup to another method.
     *
     * @param url      The JDBC URL of the database. Must not be null or empty.
     * @param username The username for the database connection. Must not be null or empty.
     * @param password The password for the database connection. Must not be null or empty.
     */
    public void connect(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        connect(config);
    }

    /**
     * Configures the transaction isolation level used for all managed transactions.
     * A {@code null} value restores the driver's default isolation level.
     *
     * @param isolationLevel The desired isolation level, typically one of the {@link Connection}
     *                       {@code TRANSACTION_*} constants, or {@code null} to use the default.
     */
    public void setTransactionIsolationLevel(Integer isolationLevel) {
        transactionIsolationLevel = isolationLevel;
    }

    /**
     * Closes the database connection and releases associated resources.
     * This method ensures proper cleanup of the database connection, thread pool,
     * and other associated components. It also manages concurrency with a write lock.
     * <p>
     * The method attempts to: <p>
     * 1. Acquire a write lock to ensure thread-safe closure of resources. <p>
     * 2. Close and nullify the data source if it is not already closed. <p>
     * 3. Shut down the thread pool executor, ensuring all tasks are completed or terminated.
     * <p>
     * Exceptions are handled to maintain the application's stability, logging,
     * and propagating issues where necessary.
     *
     * @throws DatabaseException If the write lock cannot be acquired within the timeout,
     *                           or if there are errors while closing the data source.
     */
    public void disconnect() {
        HikariDataSource dataSourceToClose;
        ExecutorService executorToShutdown;
        ExecutorService networkExecutorToShutdown;

        try {
            if (!lock.writeLock().tryLock(10, TimeUnit.SECONDS))
                throw new DatabaseException("Failed to acquire write lock for database disconnection. Another thread is currently using the database connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while waiting for write lock to disconnect from the database", e);
        }

        try {
            dataSourceToClose = dataSource;
            dataSource = null;
            executorToShutdown = executor;
            executor = null;
            networkExecutorToShutdown = networkTimeoutExecutor;
            networkTimeoutExecutor = null;
        } finally {
            lock.writeLock().unlock();
        }

        shutdownExecutor(executorToShutdown);
        shutdownExecutor(networkExecutorToShutdown);

        if (dataSourceToClose != null) {
            try {
                if (!dataSourceToClose.isClosed())
                    dataSourceToClose.close();
                logger.info("Database connection closed successfully");
            } catch (Exception e) {
                throw new DatabaseException("Failed to close the database connection", e);
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * Executes the provided SQL query and processes the resulting {@code ResultSet}
     * using the given {@link ResultSetProcessor}. If no result is found, a fallback value is returned.
     *
     * @param <T>        The type of the result returned by the processor.
     * @param sql        The SQL query string to be executed.
     * @param fallback   The value to return if no rows are found in the result set
     * @param processor  The {@link ResultSetProcessor} that processes the {@code ResultSet}
     *                   and transforms it into the desired result type.
     * @param parameters The {@link ParameterProcessor} used to set parameters in the prepared statement.
     * @return The processed result from the query if it yields a result, or the fallback value
     * if no results are found.
     * @throws DatabaseException If an SQL exception occurs during query execution.
     */
    public <T> T query(String sql, T fallback, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next())
                return processor.process(resultSet);
            return fallback;
        } catch (SQLException e) {
            logger.error("Error executing query: {}", sql, e);
            throw new DatabaseException("Failed to execute query.", e);
        } finally {
            logSlowQuery(startTime, sql);
        }
    }

    /**
     * Executes a callable SQL query processes the resulting {@code ResultSet}
     * using the given {@link ResultSetProcessor}. If no result is found, a fallback value is returned.
     *
     * @param <T>        The type of the result returned by the processor.
     * @param sql        The SQL query to execute as a callable statement.
     * @param fallback   The value to return if no rows are found in the result set.
     * @param processor  A {@link ResultSetProcessor} to process the {@code ResultSet} and convert it into the desired type
     * @param parameters A {@link ParameterProcessor} for setting parameters on the {@code CallableStatement}
     * @return An object of type {@code T} representing the processed result, or the fallback value if no rows were found
     * @throws DatabaseException If the query fails to execute due to an {@code SQLException}
     */
    public <T> T queryCallable(String sql, T fallback, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             CallableStatement statement = getCallableStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next())
                return processor.process(resultSet);
            return fallback;
        } catch (SQLException e) {
            logger.error("Error executing callable: {}", sql, e);
            throw new DatabaseException("Failed to execute callable.", e);
        } finally {
            logSlowQuery(startTime, sql);
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
     * @param parameters The parameter processor for managing query parameters.
     * @return A CompletableFuture containing the processed result of type {@code T} or the fallback value if no results are found.
     */
    public <T> CompletableFuture<T> queryAsync(String sql, T fallback, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> query(sql, fallback, processor, parameters), asyncExecutor);
    }

    /**
     * Asynchronously executes a callable statement with the specified procedure name and parameters,
     * processes the result set using the provided {@link ResultSetProcessor}, and returns a CompletableFuture
     * containing the result of type {@code T}.
     *
     * @param <T>        The type of the result to be returned.
     * @param sql        The SQL query to execute.
     * @param fallback   A fallback value to return if no results are found.
     * @param processor  A processor responsible for converting the result set into an instance of type {@code T}.
     * @param parameters The processor that manages SQL parameters for the query.
     * @return A CompletableFuture containing the processed result of type {@code T} or the fallback value if no results are found.
     */
    public <T> CompletableFuture<T> queryCallableAsync(String sql, T fallback, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> queryCallable(sql, fallback, processor, parameters), asyncExecutor);
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
    public <T> List<T> queryList(String sql, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (resultSet.next())
                results.add(processor.process(resultSet));
            return results;
        } catch (SQLException e) {
            logger.error("Error executing query: {}", sql, e);
            throw new DatabaseException("Failed to execute query.", e);
        } finally {
            logSlowQuery(startTime, sql);
        }
    }

    /**
     * Executes the given SQL callable statement, processes the result set, and returns a list of objects.
     *
     * @param <T>        The type of objects to be returned in the list
     * @param sql        The SQL callable statement to execute
     * @param processor  The result set processor used to map the result set rows to objects of type T
     * @param parameters The parameter processor used to bind parameters to the statement
     * @return A list of objects of type T created by processing the result set
     * @throws DatabaseException If an SQL exception occurs while executing the query
     */
    public <T> List<T> queryListCallable(String sql, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             CallableStatement statement = getCallableStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (resultSet.next())
                results.add(processor.process(resultSet));
            return results;
        } catch (SQLException e) {
            logger.error("Error executing callable: {}", sql, e);
            throw new DatabaseException("Failed to execute callable.", e);
        } finally {
            logSlowQuery(startTime, sql);
        }
    }

    /**
     * Asynchronously executes a query with the provided SQL and parameters, processes the result set using the provided
     * {@link ResultSetProcessor}, and returns a CompletableFuture containing a list of results of type {@code T}.
     *
     * @param <T>        The type of the elements to be returned in the result list.
     * @param sql        The SQL query to be executed; must not be null or empty.
     * @param processor  A processor responsible for converting each row in the result set into an instance of type {@code T}.
     * @param parameters A processor that handles the parameters for the SQL query.
     * @return A CompletableFuture containing a list of results of type {@code T} obtained by processing the result set returned by the query.
     */
    public <T> CompletableFuture<List<T>> queryListAsync(String sql, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> queryList(sql, processor, parameters), asyncExecutor);
    }

    /**
     * Executes a SQL query asynchronously and processes the returned result set into a list of objects.
     *
     * @param sql        The SQL query to execute
     * @param processor  The processor used to convert each row of the result set into an object of type T
     * @param parameters The processor used to set the parameters for the SQL query
     * @param <T>        The type of objects in the resulting list
     * @return A CompletableFuture containing a list of objects of type T resulting from the query
     */
    public <T> CompletableFuture<List<T>> queryListCallableAsync(String sql, ResultSetProcessor<T> processor, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> queryListCallable(sql, processor, parameters), asyncExecutor);
    }

    /**
     * Checks if a query, specified by the SQL string and processed parameters, returns any results.
     * Executes the given SQL query using a prepared statement and checks if the result set contains
     * at least one row.
     *
     * @param sql        The SQL query string to execute
     * @param parameters The {@code ParameterProcessor} used to process the parameters of the SQL query
     * @return {@code true} if the query result contains at least one row, {@code false} otherwise
     * @throws DatabaseException If a database access error occurs during the execution of the query
     */
    public boolean exists(String sql, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             PreparedStatement statement = getPreparedStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (SQLException e) {
            logger.error("Error executing exists query: {}", sql, e);
            throw new DatabaseException("Failed to execute exists query.", e);
        } finally {
            logSlowQuery(startTime, sql);
        }
    }

    /**
     * Executes a callable SQL query to check if any results exist.
     *
     * @param sql        The SQL query to execute, which must be a callable statement
     * @param parameters An object that processes and sets the parameters for the callable statement
     * @return {@code true} if the query result contains at least one row, {@code false} otherwise
     * @throws DatabaseException If a database access error occurs during the execution of the query
     */
    public boolean existsCallable(String sql, ParameterProcessor parameters) {
        long startTime = System.nanoTime();
        try (Connection connection = requireDataSource().getConnection();
             CallableStatement statement = getCallableStatement(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (SQLException e) {
            logger.error("Error executing exists callable: {}", sql, e);
            throw new DatabaseException("Failed to execute exists callable.", e);
        } finally {
            logSlowQuery(startTime, sql);
        }
    }

    /**
     * Asynchronously checks if a record exists in the database by executing a query with the provided SQL and parameters.
     * This method is useful for verifying the existence of a record without returning any data.
     *
     * @param sql        The SQL query to check the existence of a record
     * @param parameters The processor for the parameters to be applied in the SQL query
     * @return A CompletableFuture which resolves to a Boolean indicating whether a record exists (true) or not (false)
     */
    public CompletableFuture<Boolean> existsAsync(String sql, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> exists(sql, parameters), asyncExecutor);
    }

    /**
     * Asynchronously checks if a stored procedure exists in the database by executing it with the provided parameters.
     * This method is useful for verifying the existence of a procedure without returning any data.
     *
     * @param sql        The SQL query to evaluate for the callable statement
     * @param parameters The processor to handle the parameters for the callable query
     * @return A CompletableFuture containing a Boolean value indicating whether the callable
     * statement exists (true) or not (false)
     */
    public CompletableFuture<Boolean> existsCallableAsync(String sql, ParameterProcessor parameters) {
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> existsCallable(sql, parameters), asyncExecutor);
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
        ExecutorService asyncExecutor = requireExecutor();
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = requireDataSource().getConnection();
                 PreparedStatement statement = getPreparedStatement(connection, sql, stmt -> stmt.setString(1, tableName));
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
        }, asyncExecutor);
    }

    /**
     * Executes an update operation on the database using the provided SQL statement
     * and parameter processor within a transactional context.
     *
     * @param sql        The SQL update query to be executed
     * @param parameters The processor used to prepare the statement with necessary parameters
     * @return The number of rows affected by the update operation
     */
    public int update(String sql, ParameterProcessor parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getPreparedStatement(connection, sql, parameters)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Executes the given SQL update statement.
     *
     * @param sql The SQL statement to execute, typically an update, insert, or delete statement
     * @return The number of rows affected by the SQL statement
     */
    public int update(String sql) {
        return update(sql, ParameterProcessor.noop());
    }

    /**
     * Executes the given SQL update statement within a transaction and retrieves the generated key for the updated record.
     *
     * @param sql        The SQL update statement to be executed
     * @param parameters The parameter processor for preparing the SQL statement
     * @return The generated key as a long value, or 0 if no keys were generated.
     */
    public long updateAndGetGeneratedKeys(String sql, ParameterProcessor parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getGeneratedKeysStatement(connection, sql, parameters)) {
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
     * Executes a database update operation using a callable SQL statement within a transaction.
     *
     * @param sql        The SQL string for the callable statement to execute
     * @param parameters An object implementing ParameterProcessor to process and set parameters for the callable statement
     * @return The number of rows affected by the update operation
     */
    public int updateCallable(String sql, ParameterProcessor parameters) {
        return executeInTransaction(connection -> {
            try (CallableStatement statement = getCallableStatement(connection, sql, parameters)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Executes a batch update using the provided SQL query and parameter processor
     * and returns an array of update counts indicating the number of rows affected by each batch statement.
     *
     * @param sql        The SQL query to be executed in batch; must not be null or empty
     * @param parameters A processor that applies parameters to the SQL query for batch execution; must not be null
     * @return An array of update counts containing the number of rows affected for each statement in the batch
     */
    public int[] updateBatch(String sql, ParameterProcessor parameters) {
        return executeInTransaction(connection -> {
            try (PreparedStatement statement = getBatchStatement(connection, sql, parameters)) {
                return statement.executeBatch();
            }
        });
    }

    /**
     * Creates a new table in the database with the specified name and columns.
     *
     * @param name    The name of the table to be created. Must follow naming restrictions allowing only alphanumeric characters and underscores.
     * @param columns The column definitions for the table, formatted as a comma-separated list.
     * @return The number of rows affected by the execution of the SQL statement.
     * @throws DatabaseException If the table name is invalid.
     */
    public int createTable(String name, String columns) {
        // Validate table name: Only alphanumeric and underscore characters are allowed.
        if (!NAME_PATTERN.matcher(name).matches())
            throw new DatabaseException("Invalid table name: " + name);

        String sql = "CREATE TABLE IF NOT EXISTS " + name + "(" + columns + ")";
        return update(sql);
    }

    /**
     * Executes a database operation within a transactional context. This method manages the transaction lifecycle,
     * including beginning, committing, and rolling back the transaction if an exception occurs. It also ensures the
     * connection's state is properly restored after the operation.
     *
     * @param <T>       The type of result returned by the operation
     * @param operation The database operation to execute, represented as a {@code ConnectionOperation<T>}
     * @return The result of the database operation
     * @throws DatabaseException If the database operation fails or an error occurs while managing the transaction
     */
    private <T> T executeInTransaction(ConnectionOperation<T> operation) {
        long startTime = System.nanoTime();
        Connection connection = null;
        boolean previousAutoCommit = true;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean previousReadOnly = false;
        ExecutorService timeoutExecutor = requireNetworkTimeoutExecutor();
        HikariDataSource currentDataSource = requireDataSource();
        Integer desiredIsolation = transactionIsolationLevel;
        if (desiredIsolation != null && !isKnownIsolationLevel(desiredIsolation))
            throw new DatabaseException("Unsupported transaction isolation level: " + desiredIsolation);

        try {
            connection = currentDataSource.getConnection();

            previousAutoCommit = connection.getAutoCommit();
            previousIsolation = connection.getTransactionIsolation();
            previousReadOnly = connection.isReadOnly();

            if (previousAutoCommit)
                connection.setAutoCommit(false);

            if (desiredIsolation != null && previousIsolation != desiredIsolation)
                connection.setTransactionIsolation(desiredIsolation);

            connection.setReadOnly(false);
            connection.setNetworkTimeout(timeoutExecutor, 30_000); // Set network timeout to 30 seconds

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
                logger.warn("Slow database operation executed in {} ms", durationMs);

            if (connection != null)
                try {
                    if (connection.getAutoCommit() != previousAutoCommit)
                        connection.setAutoCommit(previousAutoCommit);

                    if (connection.getTransactionIsolation() != previousIsolation)
                        connection.setTransactionIsolation(previousIsolation);

                    connection.setReadOnly(previousReadOnly);
                    connection.setNetworkTimeout(timeoutExecutor, 0);
                } catch (SQLException e) {
                    logger.error("Failed to restore connection state", e);
                } finally {
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        logger.error("Failed to close database connection", e);
                    }
                }

        }
    }

    /**
     * Prepares a {@link CallableStatement} using the provided database connection and SQL string,
     * then applies parameter operations to the statement.
     *
     * @param connection The active database connection used to prepare the CallableStatement
     * @param sql        The SQL string used to create the CallableStatement
     * @param parameters The operations to apply to the CallableStatement for parameter configuration
     * @return The CallableStatement with the applied parameter operations
     * @throws SQLException If a database access error occurs or the SQL string is invalid
     */
    private CallableStatement getCallableStatement(Connection connection, String sql, ParameterProcessor parameters) throws SQLException {
        CallableStatement statement = connection.prepareCall(sql);
        ParameterProcessor.of(parameters).execute(statement);
        return statement;
    }

    /**
     * Prepares a batch SQL statement using the provided connection, SQL query, and parameter operation.
     * Remember to use {@code statement.addBatch()} to add each batch statement to the prepared statement.
     *
     * @param connection The database connection to be used for preparing the statement.
     * @param sql        The SQL query string to prepare as a batch statement.
     * @param parameters A functional operation to apply parameters to the prepared statement.
     * @return The prepared batch SQL statement after applying the parameter operation.
     * @throws SQLException If an error occurs while preparing the statement or applying the parameters.
     */
    private PreparedStatement getBatchStatement(Connection connection, String sql, ParameterProcessor parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        ParameterProcessor.of(parameters).execute(statement);
        return statement;
    }

    /**
     * Prepares a {@link PreparedStatement} capable of returning generated keys using the provided SQL query.
     * A given parameter operation is applied to the prepared statement before returning it.
     *
     * @param connection The database connection to be used for preparing the {@link PreparedStatement}.
     *                   Must not be null and should represent a valid, open connection.
     * @param sql        The SQL query string to prepare the {@link PreparedStatement} with.
     *                   Must not be null or empty.
     * @param parameters An operation defining how to set parameters and operate on the {@link PreparedStatement}.
     * @return The prepared and parameterized {@link PreparedStatement} ready for execution,
     * configured to return generated keys.
     * @throws SQLException If an error occurs while preparing or handling the {@link PreparedStatement}.
     */
    private PreparedStatement getGeneratedKeysStatement(Connection connection, String sql, ParameterProcessor parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        ParameterProcessor.of(parameters).execute(statement);
        return statement;
    }

    /**
     * Prepares a {@link PreparedStatement} using the provided SQL and connection, and applies a parameter
     * operation on the prepared statement before returning it.
     *
     * @param connection The database connection to be used for preparing the {@link PreparedStatement}.
     *                   Must not be null and should represent a valid, open connection.
     * @param sql        The SQL query string to prepare the {@link PreparedStatement} with.
     *                   Must not be null or empty.
     * @param parameters An operation defining how to set parameters and execute the {@link PreparedStatement}.
     * @return The prepared and parameterized {@link PreparedStatement} ready for execution.
     * @throws SQLException If an error occurs while preparing or handling the {@link PreparedStatement}.
     */
    private PreparedStatement getPreparedStatement(Connection connection, String sql, ParameterProcessor parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        ParameterProcessor.of(parameters).execute(statement);
        return statement;
    }

    /**
     * Creates and returns a fixed thread pool executor service with a predefined pool size.
     *
     * @return An instance of ExecutorService configured with a fixed thread pool
     */
    private ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(EXECUTOR_POOL_SIZE, WORKER_THREAD_FACTORY);
    }

    /**
     * Creates the executor used for JDBC network timeout callbacks.
     *
     * @return An executor service dedicated to network timeout tasks
     */
    private ExecutorService createNetworkTimeoutExecutor() {
        return Executors.newCachedThreadPool(NETWORK_TIMEOUT_THREAD_FACTORY);
    }

    /**
     * Ensures that an active {@link ExecutorService} is available.
     * This method checks if the current executor service is not null and not shut down.
     * If the executor service is unavailable, an {@link DatabaseException} is thrown.
     *
     * @return The active {@link ExecutorService} instance.
     * @throws DatabaseException If the executor service is null or has been shut down.
     */
    private ExecutorService requireExecutor() {
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown())
            throw new DatabaseException("Database is not connected");
        return currentExecutor;
    }

    /**
     * Ensures that an active executor for network timeout callbacks is available.
     *
     * @return The executor dedicated to network timeout tasks
     * @throws DatabaseException If the executor is unavailable
     */
    private ExecutorService requireNetworkTimeoutExecutor() {
        ExecutorService currentExecutor = networkTimeoutExecutor;
        if (currentExecutor == null || currentExecutor.isShutdown())
            throw new DatabaseException("Database is not connected");
        return currentExecutor;
    }

    /**
     * Ensures that a data source is available and returns the current {@code HikariDataSource}.
     * If no data source is available, an {@link DatabaseException} is thrown.
     *
     * @return The currently configured {@code HikariDataSource}
     * @throws DatabaseException If no data source is connected
     */
    private HikariDataSource requireDataSource() {
        HikariDataSource currentDataSource = dataSource;
        if (currentDataSource == null)
            throw new DatabaseException("Database is not connected");
        return currentDataSource;
    }

    private boolean isKnownIsolationLevel(int isolationLevel) {
        return isolationLevel == Connection.TRANSACTION_NONE
                || isolationLevel == Connection.TRANSACTION_READ_UNCOMMITTED
                || isolationLevel == Connection.TRANSACTION_READ_COMMITTED
                || isolationLevel == Connection.TRANSACTION_REPEATABLE_READ
                || isolationLevel == Connection.TRANSACTION_SERIALIZABLE;
    }

    /**
     * Logs a warning if a database query took longer than the defined slow query threshold.
     *
     * @param startTime The start time of the query in nanoseconds
     * @param sql       The SQL query that was executed
     */
    private void logSlowQuery(long startTime, String sql) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        if (durationMs > SLOW_QUERY_THRESHOLD_MS)
            logger.warn("Slow database query [{}] executed in {} ms", sql, durationMs);
    }

    private void shutdownExecutor(ExecutorService executorService) {
        if (executorService == null || executorService.isShutdown())
            return;

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void closeDataSourceQuietly(HikariDataSource source) {
        if (source == null)
            return;

        try {
            if (!source.isClosed())
                source.close();
        } catch (Exception e) {
            logger.warn("Failed to close data source", e);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger counter = new AtomicInteger();

        private DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, namePrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Generates a SQL query string for creating a stored procedure in the database.
     * The procedure is created only if it does not already exist. The query includes
     * the procedure name, its parameters, and the procedure body.
     *
     * @param name       The name of the procedure. Must match the required naming pattern.
     *                   If the name is invalid, an {@link DatabaseException} is thrown.
     * @param parameters A string specifying the parameters of the procedure, including types.
     *                   This string is directly appended to the generated SQL.
     * @param body       The body of the procedure, containing the SQL logic to be executed
     *                   within the procedure.
     * @return A SQL string representing the creation of the specified stored procedure.
     * @throws DatabaseException If the procedure name does not match the expected pattern.
     */
    public static String getProcedureQuery(String name, String parameters, String body) {
        // Validate procedure name: Only alphanumeric and underscore characters are allowed.
        if (!NAME_PATTERN.matcher(name).matches())
            throw new DatabaseException("Invalid procedure name: " + name);

        return "CREATE PROCEDURE IF NOT EXISTS " + name + "(" + parameters + ")\n BEGIN\n    " + body + " \nEND;";
    }

    /**
     * A functional interface representing a database operation that executes a specific task
     * using a provided {@link Connection} object. This operation is designed to encapsulate
     * reusable database logic and handle SQL-related exceptions within a connection context.
     *
     * @param <T> The type of result produced by the operation executed within the database connection
     */
    @FunctionalInterface
    private interface ConnectionOperation<T> {
        /**
         * Executes a database operation using the provided {@link Connection} and returns the result of type {@code T}.
         * This method encapsulates the logic for performing a specific task within the scope of the database connection.
         *
         * @param connection The {@link Connection} object to be used for executing the operation. Must not be null.
         * @return An object of type {@code T}, representing the result of the executed database operation.
         * @throws SQLException If a database access error occurs or the operation fails.
         */
        T execute(Connection connection) throws SQLException;
    }
}
