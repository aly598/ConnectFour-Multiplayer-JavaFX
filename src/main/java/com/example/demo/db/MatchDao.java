package com.example.demo.db;

import java.sql.*;
import java.util.List;
import java.util.UUID;

/** DAO for persisted match results and player stats. */
public final class MatchDao {
    private final Database db;
    private final PlayerDao playerDao;

    public MatchDao(Database db) {
        this.db = db;
        this.playerDao = new PlayerDao(db);
    }

    public PlayerDao playerDao() { return playerDao; }

    public long recordWin(String matchId, String winner, String loser, int variant, int score,
                          List<int[]> sequence, String boardSnapshot,
                          int sequenceLen, String scoreType) throws SQLException {
        String cleanWinner = PlayerDao.requireName(winner);
        String cleanLoser = PlayerDao.requireName(loser);
        if (score < 0) throw new IllegalArgumentException("score must be >= 0");
        if (sequence == null) sequence = List.of();
        if (scoreType == null || scoreType.isBlank()) scoreType = "NORMAL";
        if (matchId == null || matchId.isBlank()) matchId = UUID.randomUUID().toString();

        synchronized (db) {
            Connection c = db.get();
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                PlayerDao.ensurePlayer(c, cleanWinner);
                PlayerDao.ensurePlayer(c, cleanLoser);

                long winId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO WinRecord(match_id,winner,loser,variant,score,score_type,board_final) " +
                                "VALUES(?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, matchId);
                    ps.setString(2, cleanWinner);
                    ps.setString(3, cleanLoser);
                    ps.setInt(4, variant);
                    ps.setInt(5, score);
                    ps.setString(6, scoreType);
                    ps.setString(7, boardSnapshot == null ? "" : boardSnapshot);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) throw new SQLException("WinRecord insert did not return an id");
                        winId = rs.getLong(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO WinningSequence(win_id,sequence,sequence_len,board_snapshot) VALUES(?,?,?,?)")) {
                    ps.setLong(1, winId);
                    ps.setString(2, encodeSequence(sequence));
                    ps.setInt(3, Math.max(0, sequenceLen));
                    ps.setString(4, boardSnapshot == null ? "" : boardSnapshot);
                    ps.executeUpdate();
                }

                updateWinner(c, cleanWinner, score);
                updateLoser(c, cleanLoser);

                c.commit();
                return winId;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public long recordWin(String winner, String loser, int variant, int score,
                          List<int[]> sequence, String boardSnapshot,
                          int sequenceLen, String scoreType) throws SQLException {
        return recordWin(UUID.randomUUID().toString(), winner, loser, variant, score,
                sequence, boardSnapshot, sequenceLen, scoreType);
    }

    public long recordWin(String winner, String loser, int variant, int score,
                          List<int[]> sequence, String boardSnapshot) throws SQLException {
        int sequenceLen = sequence == null || sequence.isEmpty() ? variant : sequence.size();
        return recordWin(UUID.randomUUID().toString(), winner, loser, variant, score,
                sequence == null ? List.of() : sequence, boardSnapshot, sequenceLen, "NORMAL");
    }

    private static void updateWinner(Connection c, String username, int points) throws SQLException {
        String sql = """
            UPDATE Player
            SET total_points = total_points + ?,
                matches_played = matches_played + 1,
                wins = wins + 1,
                current_streak = current_streak + 1,
                best_streak = GREATEST(best_streak, current_streak + 1),
                last_seen_at = CURRENT_TIMESTAMP
            WHERE username = ?
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, points);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    private static void updateLoser(Connection c, String username) throws SQLException {
        String sql = """
            UPDATE Player
            SET matches_played = matches_played + 1,
                losses = losses + 1,
                current_streak = 0,
                last_seen_at = CURRENT_TIMESTAMP
            WHERE username = ?
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    private static String encodeSequence(List<int[]> sequence) {
        StringBuilder seq = new StringBuilder();
        for (int[] rc : sequence) {
            if (rc == null || rc.length < 2) continue;
            if (seq.length() > 0) seq.append(';');
            seq.append(rc[0]).append(',').append(rc[1]);
        }
        return seq.toString();
    }
}
