package com.example.demo.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Data-access object for players and global ranking. */
public final class PlayerDao {

    public record PlayerScore(String username, int totalPoints, int rank) {
    }

    private final Database db;

    public PlayerDao(Database db) {
        this.db = db;
    }

    public void ensurePlayer(String username) throws SQLException {
        String clean = requireName(username);
        synchronized (db) {
            Connection c = db.get();
            ensurePlayer(c, clean);
        }
    }

    static void ensurePlayer(Connection c, String username) throws SQLException {
        String sql = """
                INSERT INTO Player(username, total_points, last_seen_at)
                VALUES (?, 0, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, requireName(username));
            ps.executeUpdate();
        }
    }

    public void addPoints(String username, int points) throws SQLException {
        if (points < 0)
            throw new IllegalArgumentException("points must be >= 0");
        String clean = requireName(username);
        synchronized (db) {
            Connection c = db.get();
            ensurePlayer(c, clean);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Player SET total_points = total_points + ?, last_seen_at = CURRENT_TIMESTAMP WHERE username = ?")) {
                ps.setInt(1, points);
                ps.setString(2, clean);
                ps.executeUpdate();
            }
        }
    }

    public List<PlayerScore> getGlobalRanking() throws SQLException {
        String sql = """
                SELECT username, total_points,
                       RANK() OVER (ORDER BY total_points DESC) AS `rank`
                FROM Player
                ORDER BY total_points DESC, username ASC
                """;
        List<PlayerScore> result = new ArrayList<>();
        synchronized (db) {
            try (Statement st = db.get().createStatement();
                    ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    result.add(new PlayerScore(
                            rs.getString("username"),
                            rs.getInt("total_points"),
                            rs.getInt("rank")));
                }
            }
        }
        return result;
    }

    public int getPoints(String username) throws SQLException {
        String clean = requireName(username);
        synchronized (db) {
            try (PreparedStatement ps = db.get().prepareStatement(
                    "SELECT total_points FROM Player WHERE username = ?")) {
                ps.setString(1, clean);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("total_points") : 0;
                }
            }
        }
    }

    static String requireName(String username) {
        if (username == null)
            throw new IllegalArgumentException("username is required");
        String clean = username.trim();
        if (clean.isEmpty())
            throw new IllegalArgumentException("username is required");
        if (clean.length() > 100)
            clean = clean.substring(0, 100);
        return clean;
    }
}
