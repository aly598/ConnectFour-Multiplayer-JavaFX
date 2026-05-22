package com.example.demo.db;

import java.sql.*;

/** DAO for tournament records and bracket rows. */
public final class TournamentDao {
    private final Database db;

    public TournamentDao(Database db) { this.db = db; }

    public void recordBracket(String roundName, String king, String challenger,
                              String result, Long tournamentId) throws SQLException {
        String cleanRound = roundName == null || roundName.isBlank() ? "MATCH" : roundName.trim();
        String cleanKing = PlayerDao.requireName(king);
        String cleanChallenger = PlayerDao.requireName(challenger);
        String cleanResult = result == null || result.isBlank() ? "UNKNOWN" : result.trim();
        synchronized (db) {
            Connection c = db.get();
            String sql = tournamentId != null && tournamentId > 0
                    ? "INSERT INTO TournamentBracket(tournament_id,round_name,king,challenger,result) VALUES(?,?,?,?,?)"
                    : "INSERT INTO TournamentBracket(round_name,king,challenger,result) VALUES(?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (tournamentId != null && tournamentId > 0) {
                    ps.setLong(1, tournamentId);
                    ps.setString(2, cleanRound);
                    ps.setString(3, cleanKing);
                    ps.setString(4, cleanChallenger);
                    ps.setString(5, cleanResult);
                } else {
                    ps.setString(1, cleanRound);
                    ps.setString(2, cleanKing);
                    ps.setString(3, cleanChallenger);
                    ps.setString(4, cleanResult);
                }
                ps.executeUpdate();
            }
        }
    }

    public void recordBracket(String king, String challenger, String result, Long tournamentId) throws SQLException {
        recordBracket("MATCH", king, challenger, result, tournamentId);
    }

    public void recordBracket(String king, String challenger, String result) throws SQLException {
        recordBracket("MATCH", king, challenger, result, null);
    }

    public long startTournament(String p1, String p2, String p3, String p4) throws SQLException {
        synchronized (db) {
            String sql = """
                INSERT INTO TournamentRecord(player1,player2,player3,player4,status)
                VALUES(?,?,?,?, 'RUNNING')
                """;
            try (PreparedStatement ps = db.get().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, PlayerDao.requireName(p1));
                ps.setString(2, PlayerDao.requireName(p2));
                ps.setString(3, PlayerDao.requireName(p3));
                ps.setString(4, PlayerDao.requireName(p4));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new SQLException("TournamentRecord insert did not return an id");
                    return rs.getLong(1);
                }
            }
        }
    }

    public void finishTournament(long tournamentId, String winner) throws SQLException {
        if (tournamentId <= 0) return;
        synchronized (db) {
            String sql = """
                UPDATE TournamentRecord
                SET winner = ?, status = 'COMPLETE', ended_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
            try (PreparedStatement ps = db.get().prepareStatement(sql)) {
                ps.setString(1, PlayerDao.requireName(winner));
                ps.setLong(2, tournamentId);
                ps.executeUpdate();
            }
        }
    }

    public void abortTournament(long tournamentId) throws SQLException {
        if (tournamentId <= 0) return;
        synchronized (db) {
            try (PreparedStatement ps = db.get().prepareStatement(
                    "UPDATE TournamentRecord SET status = 'ABORTED', ended_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setLong(1, tournamentId);
                ps.executeUpdate();
            }
        }
    }
}
