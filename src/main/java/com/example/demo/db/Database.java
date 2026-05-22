package com.example.demo.db;

import java.sql.*;
import java.util.Objects;

/**
 * MySQL connection holder + schema bootstrap/migration.
 *
 * The server keeps one synchronized JDBC connection. This is enough for this
 * small socket game server and avoids accidental connection leaks in the DAO
 * classes. DAO methods synchronize on the Database instance before using it.
 */
public final class Database implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;
    private Connection conn;
    private boolean bootstrapped;

    public Database(String host, int port, String dbName, String user, String password) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(dbName, "dbName");
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?createDatabaseIfNotExist=true"
                + "&useSSL=false"
                + "&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC"
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&cachePrepStmts=true"
                + "&prepStmtCacheSize=250"
                + "&prepStmtCacheSqlLimit=2048";
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    public Database(String dbName, String user, String password) {
        this("localhost", 3306, dbName, user, password);
    }

    /**
     * Returns the shared connection. Callers must synchronize on this Database
     * instance while executing SQL statements/transactions.
     */
    public synchronized Connection get() throws SQLException {
        if (conn == null || conn.isClosed()) {
            loadMysqlDriverIfPresent();
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(true);
        }
        if (!bootstrapped) {
            bootstrap(conn);
            bootstrapped = true;
        }
        return conn;
    }

    private void loadMysqlDriverIfPresent() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            // DriverManager can still discover the driver when Connector/J is on the module/class path.
        }
    }

    private void bootstrap(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS Player (
                  id              INT AUTO_INCREMENT PRIMARY KEY,
                  username        VARCHAR(100) NOT NULL UNIQUE,
                  total_points    INT NOT NULL DEFAULT 0,
                  matches_played  INT NOT NULL DEFAULT 0,
                  wins            INT NOT NULL DEFAULT 0,
                  losses          INT NOT NULL DEFAULT 0,
                  current_streak  INT NOT NULL DEFAULT 0,
                  best_streak     INT NOT NULL DEFAULT 0,
                  registered_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  last_seen_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT chk_player_points CHECK (total_points >= 0),
                  CONSTRAINT chk_player_record CHECK (matches_played >= 0 AND wins >= 0 AND losses >= 0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS WinRecord (
                  id          INT AUTO_INCREMENT PRIMARY KEY,
                  match_id    VARCHAR(64),
                  winner      VARCHAR(100) NOT NULL,
                  loser       VARCHAR(100) NOT NULL,
                  variant     INT NOT NULL,
                  score       INT NOT NULL,
                  score_type  VARCHAR(50) NOT NULL DEFAULT 'NORMAL',
                  board_final TEXT,
                  played_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT chk_win_score CHECK (score >= 0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS WinningSequence (
                  id             INT AUTO_INCREMENT PRIMARY KEY,
                  win_id         INT NOT NULL,
                  sequence       TEXT NOT NULL,
                  sequence_len   INT NOT NULL DEFAULT 4,
                  board_snapshot TEXT NOT NULL,
                  CONSTRAINT fk_winning_sequence_win
                    FOREIGN KEY (win_id) REFERENCES WinRecord(id)
                    ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS TournamentRecord (
                  id         INT AUTO_INCREMENT PRIMARY KEY,
                  player1    VARCHAR(100) NOT NULL,
                  player2    VARCHAR(100) NOT NULL,
                  player3    VARCHAR(100) NOT NULL,
                  player4    VARCHAR(100) NOT NULL,
                  winner     VARCHAR(100),
                  status     VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
                  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  ended_at   DATETIME
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS TournamentBracket (
                  id            INT AUTO_INCREMENT PRIMARY KEY,
                  tournament_id INT,
                  round_name    VARCHAR(40) NOT NULL DEFAULT 'MATCH',
                  king          VARCHAR(100) NOT NULL,
                  challenger    VARCHAR(100) NOT NULL,
                  result        VARCHAR(100) NOT NULL,
                  played_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_tournament_bracket_tournament
                    FOREIGN KEY (tournament_id) REFERENCES TournamentRecord(id)
                    ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }

        migrateExistingSchema(c);
        createIndex(c, "idx_player_points", "Player", "total_points DESC, username ASC");
        createIndex(c, "idx_win_played_at", "WinRecord", "played_at DESC");
        createIndex(c, "idx_win_match_id", "WinRecord", "match_id");
        createIndex(c, "idx_bracket_tournament", "TournamentBracket", "tournament_id, played_at");
    }

    private void migrateExistingSchema(Connection c) throws SQLException {
        addColumnIfMissing(c, "Player", "matches_played", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "Player", "wins", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "Player", "losses", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "Player", "current_streak", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "Player", "best_streak", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "Player", "last_seen_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        addColumnIfMissing(c, "WinRecord", "match_id", "VARCHAR(64)");
        addColumnIfMissing(c, "WinRecord", "board_final", "TEXT");
        addColumnIfMissing(c, "WinRecord", "score_type", "VARCHAR(50) NOT NULL DEFAULT 'NORMAL'");
        addColumnIfMissing(c, "TournamentRecord", "status", "VARCHAR(30) NOT NULL DEFAULT 'RUNNING'");
        addColumnIfMissing(c, "TournamentBracket", "round_name", "VARCHAR(40) NOT NULL DEFAULT 'MATCH'");
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) return;
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void createIndex(Connection c, String indexName, String table, String columns) throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return;
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
        }
    }

    @Override public synchronized void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
