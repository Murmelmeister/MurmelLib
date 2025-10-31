package de.murmelmeister.library.database;

import de.murmelmeister.library.exceptions.DatabaseException;
import org.junit.jupiter.api.*;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.CallableStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    private final Path realEnv = Path.of(".env"); // Load the .env file for database connection details
    private final Path testEnv = Path.of(".env.test");
    private Database database;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;

    @BeforeEach
    void setUp() {
        // Initialize
        database = new Database();
        Properties properties = new Properties();

        // Load data from the .env file
        try (FileReader reader = new FileReader(realEnv.toFile())) {
            properties.load(reader);

            String dbName = properties.getProperty("MARIADB_DATABASE");
            String dbPort = properties.getProperty("MARIADB_PORT");
            String dbHost = properties.getProperty("MARIADB_HOST");

            dbUrl = "jdbc:mariadb://" + dbHost + ":" + dbPort + "/" + dbName;
            dbUser = properties.getProperty("MARIADB_USER");
            dbPassword = properties.getProperty("MARIADB_PASSWORD");
        } catch (IOException e) {
            Assertions.fail(e);
        }
    }

    @AfterEach
    void tearDown() {
        // Close the database connection
        database.close();

        try {
            // Delete the temporary .env.test file
            Files.deleteIfExists(testEnv);
        } catch (IOException e) {
            Assertions.fail(e);
        }
    }

    @Test
    void testConnectWithProperties() {
        String content = """
                jdbcUrl=%s
                username=%s
                password=%s""".formatted(dbUrl, dbUser, dbPassword);
        Properties properties = new Properties();

        // Write content to the temporary .env.test file
        try {
            Files.writeString(testEnv, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            try (FileReader reader = new FileReader(testEnv.toFile())) {
                properties.load(reader);
            }
        } catch (IOException e) {
            Assertions.fail(e);
        }

        database.connect(properties);
        assertNotNull(database);
    }

    @Test
    void testConnectWithFileNames() {
        String content = """
                jdbcUrl=%s
                username=%s
                password=%s""".formatted(dbUrl, dbUser, dbPassword);

        // Write content to the temporary .env.test file
        try {
            Files.writeString(testEnv, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            Assertions.fail(e);
        }

        database.connect(testEnv.toString());
        assertNotNull(database);
    }

    @Test
    void testConnectWithUrl() {
        database.connect(dbUrl, dbUser, dbPassword);
        assertNotNull(database);
    }

    @Test
    void testDisconnect() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.disconnect();
        DatabaseException exception = Assertions.assertThrows(DatabaseException.class,
                () -> database.update("DROP TABLE IF EXISTS test"));
        assertEquals("Database is not connected", exception.getMessage());
    }

    @Test
    void testUpdate() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY, name VARCHAR(255)");

        // Execute
        int id = 1;
        String name = "Test";
        String sql = "INSERT INTO test (id, name) VALUES (?, ?)";
        int result = database.update(sql, statement -> {
            statement.setInt(1, id);
            statement.setString(2, name);
        });
        assertEquals(1, result);

        // Verify
        int fromDb = database.query("SELECT * FROM test WHERE name=?", 0,
                resultSet -> resultSet.getInt("id"),
                statement -> statement.setString(1, name));
        assertEquals(id, fromDb);
    }

    @Test
    void testUpdateGetGeneratedKeys() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Execute
        String name = "Test";
        long id = database.updateAndGetGeneratedKeys("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));
        assertEquals(1L, id);

        // Verify
        int fromDb = database.query("SELECT * FROM test WHERE name=?", 0,
                resultSet -> resultSet.getInt("id"),
                statement -> statement.setString(1, name));
        assertEquals(id, fromDb);
    }

    @Test
    void testUpdateCallable() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc", "p_name VARCHAR(255)", "INSERT INTO test (name) VALUES (p_name);");
        database.update(prod);

        // Execute
        String name = "Test";
        int result = database.updateCallable("CALL test_proc(?)",
                statement -> statement.setString(1, name));
        assertEquals(1, result);

        // Verify
        int fromDb = database.query("SELECT * FROM test WHERE name=?", 0,
                resultSet -> resultSet.getInt("id"),
                statement -> statement.setString(1, name));
        assertEquals(1, fromDb);
    }

    @Test
    void testUpdateBatch() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Execute
        String sql = "INSERT INTO test (name) VALUES (?)";
        int[] result = database.updateBatch(sql, statement -> {
            for (String n : List.of("a", "b", "c", "d")) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });
        assertEquals(4, result.length);

        // Verify
        int fromDb = database.query("SELECT COUNT(*) FROM test", 0,
                resultSet -> resultSet.getInt(1),
                statement -> {
                });
        assertEquals(4, fromDb);
    }

    @Test
    void testCreateTable() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");

        // Execute
        int result = database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");
        assertEquals(0, result);

        // Verify
        List<String> tables = database.queryList("SHOW TABLES",
                resultSet -> resultSet.getString(1),
                statement -> {
                });
        assertTrue(tables.contains("test"));
    }

    @Test
    void testQuery() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        int fromDb = database.query("SELECT * FROM test WHERE name=?", 0,
                resultSet -> resultSet.getInt("id"),
                statement -> statement.setString(1, name));

        // Verify
        assertEquals(1, fromDb);
    }

    @Test
    void testQueryCallable() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc",
                "p_name VARCHAR(255)",
                "SELECT * FROM test WHERE name=p_name;");
        database.update(prod);

        String[] names = {"a", "b", "c", "d"};
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        int id = database.queryCallable("CALL test_proc(?)", 0,
                resultSet -> resultSet.getInt("id"),
                statement -> statement.setString(1, names[2]));

        // Verify
        assertEquals(3, id);
    }

    @Test
    void testQueryAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        int fromDb = database.queryAsync("SELECT * FROM test WHERE name=?", 0,
                        resultSet -> resultSet.getInt("id"),
                        statement -> statement.setString(1, name))
                .join();

        // Verify
        assertEquals(1, fromDb);
    }

    @Test
    void testQueryCallableAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc",
                "p_name VARCHAR(255)",
                "SELECT * FROM test WHERE name=p_name;");
        database.update(prod);

        String[] names = {"a", "b", "c", "d"};
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        int id = database.queryCallableAsync("CALL test_proc(?)", 0,
                        resultSet -> resultSet.getInt("id"),
                        statement -> statement.setString(1, names[2]))
                .join();

        // Verify
        assertEquals(3, id);
    }

    @Test
    void testQueryList() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        List<String> names = List.of("a", "b", "c", "d");
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        List<String> results = database.queryList("SELECT name FROM test",
                resultSet -> resultSet.getString("name"),
                statement -> {
                });

        // Verify
        assertEquals(4, results.size());
        assertTrue(results.containsAll(names));
    }

    @Test
    void testQueryListCallable() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc", "", "SELECT * FROM test;");
        database.update(prod);

        List<String> names = List.of("a", "b", "c", "d");
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        List<String> results = database.queryListCallable("CALL test_proc",
                resultSet -> resultSet.getString("name"),
                statement -> {
                });

        // Verify
        assertEquals(4, names.size());
        assertTrue(results.containsAll(names));
    }

    @Test
    void testQueryListAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        List<String> names = List.of("a", "b", "c", "d");
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        List<String> results = database.queryListAsync("SELECT name FROM test",
                resultSet -> resultSet.getString("name"),
                statement -> {
                }).join();

        // Verify
        assertEquals(4, results.size());
        assertTrue(results.containsAll(names));
    }

    @Test
    void testQueryListCallableAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc", "", "SELECT * FROM test;");
        database.update(prod);

        List<String> names = List.of("a", "b", "c", "d");
        String sql = "INSERT INTO test (name) VALUES (?)";
        database.updateBatch(sql, statement -> {
            for (String n : names) {
                statement.setString(1, n);
                statement.addBatch();
            }
        });

        // Execute
        List<String> results = database.queryListCallableAsync("CALL test_proc",
                resultSet -> resultSet.getString("name"),
                statement -> {
                }).join();

        // Verify
        assertEquals(4, names.size());
        assertTrue(results.containsAll(names));
    }

    @Test
    void testExists() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        boolean result = database.exists("SELECT * FROM test WHERE name=?",
                statement -> statement.setString(1, name));

        // Verify
        assertTrue(result);
    }

    @Test
    void testExistsCallable() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc", "p_name VARCHAR(255)", "SELECT * FROM test WHERE name=p_name;");
        database.update(prod);

        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        boolean result = database.existsCallable("CALL test_proc(?)",
                statement -> statement.setString(1, name));

        // Verify
        assertTrue(result);
    }

    @Test
    void testExistsAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        boolean result = database.existsAsync("SELECT * FROM test WHERE name=?",
                statement -> statement.setString(1, name))
                .join();

        // Verify
        assertTrue(result);
    }

    @Test
    void testExistsCallableAsync() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.update("DROP PROCEDURE IF EXISTS test_proc");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String prod = Database.getProcedureQuery("test_proc", "p_name VARCHAR(255)", "SELECT * FROM test WHERE name=p_name;");
        database.update(prod);

        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        boolean results = database.existsCallableAsync("CALL test_proc(?)",
                statement -> statement.setString(1, name))
                .join();

        // Verify
        assertTrue(results);
    }

    @Test
    void testGetAutoIncrement() {
        database.connect(dbUrl, dbUser, dbPassword);
        database.update("DROP TABLE IF EXISTS test");
        database.createTable("test", "id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255)");

        // Create
        String name = "Test";
        database.update("INSERT INTO test (name) VALUES (?)",
                statement -> statement.setString(1, name));

        // Execute
        long result = database.getAutoIncrement("test").join();

        // Verify
        assertEquals(2L, result);
    }
}