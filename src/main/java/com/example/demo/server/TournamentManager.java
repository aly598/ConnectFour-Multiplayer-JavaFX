package com.example.demo.server;

import com.example.demo.db.MatchDao;
import com.example.demo.db.PlayerDao;
import com.example.demo.net.Packet;
import com.example.demo.net.Protocol;

import java.util.ArrayList;
import java.util.List;

/** 4-player bracket tournament manager plus global king/scoreboard state. */
public final class TournamentManager {
    private enum Phase {
        IDLE, SEMIS, FINALS, COMPLETE
    }

    private final Lobby lobby;
    private final MatchDao dao;
    private final com.example.demo.db.TournamentDao tournDao;

    private volatile ClientHandler king;
    private volatile String kingName = "";

    private Phase phase = Phase.IDLE;
    private long tournamentDbId = -1;

    private final List<ClientHandler> semifinalists = new ArrayList<>();

    private ClientHandler semiWinner1, semiLoser1;
    private ClientHandler semiWinner2, semiLoser2;
    private int semisFinished = 0;
    private boolean grandFinalDone = false;
    private boolean thirdPlaceDone = false;
    private ClientHandler champion;

    private final java.util.Map<String, Integer> tournamentPoints = new java.util.concurrent.ConcurrentHashMap<>();

    public TournamentManager(Lobby lobby, MatchDao dao, com.example.demo.db.TournamentDao tournDao) {
        this.lobby = lobby;
        this.dao = dao;
        this.tournDao = tournDao;
    }

    public synchronized String kingName() {
        return kingName;
    }

    public synchronized ClientHandler king() {
        return king;
    }

    public synchronized boolean isTournamentActive() {
        return phase == Phase.SEMIS || phase == Phase.FINALS;
    }

    public synchronized void startFourPlayerTournament(List<ClientHandler> players) {
        if (phase != Phase.IDLE)
            return;
        if (players.size() < 4)
            return;
        for (ClientHandler p : players) {
            if (p.currentMatch != null)
                return;
        }

        phase = Phase.SEMIS;
        semisFinished = 0;
        grandFinalDone = false;
        thirdPlaceDone = false;
        champion = null;
        tournamentPoints.clear();
        semifinalists.clear();
        semifinalists.addAll(players.subList(0, 4));

        try {
            tournamentDbId = tournDao.startTournament(
                    semifinalists.get(0).name, semifinalists.get(1).name,
                    semifinalists.get(2).name, semifinalists.get(3).name);
        } catch (Exception e) {
            tournamentDbId = -1;
            System.err.println("[Tournament] DB start error: " + e.getMessage());
        }

        broadcast(new Packet(Protocol.S_TOURNAMENT_START)
                .put("match1", semifinalists.get(0).name + " vs " + semifinalists.get(1).name)
                .put("match2", semifinalists.get(2).name + " vs " + semifinalists.get(3).name));

        lobby.startTournamentMatch(semifinalists.get(0), semifinalists.get(1));
        lobby.startTournamentMatch(semifinalists.get(2), semifinalists.get(3));
    }

    public synchronized void onMatchEnd(ClientHandler winner, ClientHandler loser, int winScore) {
        if (winner == null || loser == null)
            return;

        if (isTournamentActive() || phase == Phase.COMPLETE) {
            tournamentPoints.merge(winner.name, winScore, Integer::sum);
            broadcastTournamentScore();
        }

        if (phase == Phase.SEMIS) {
            recordBracket("SEMI", winner, loser);
            handleSemiResult(winner, loser);
        } else if (phase == Phase.FINALS) {
            recordBracket(isGrandFinal(winner, loser) ? "FINAL" : "THIRD_PLACE", winner, loser);
            handleFinalResult(winner, loser);
        } else {
            recordBracket("KING_OF_THE_HILL", winner, loser);
            updateKing(winner, loser);
        }
    }

    public synchronized void onMatchEnd(ClientHandler winner, ClientHandler loser) {
        onMatchEnd(winner, loser, 1);
    }

    public synchronized void onMatchDraw(ClientHandler a, ClientHandler b) {
        if (isTournamentActive() && a != null && b != null) {
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND)
                    .put("round", "REMATCH")
                    .put("desc", "Draw between " + a.name + " and " + b.name + ". Starting rematch."));
            lobby.startTournamentMatch(a, b);
        }
    }

    public synchronized void onLeave(ClientHandler h) {
        if (king == h)
            king = null;
        if (isTournamentActive() && semifinalists.contains(h)) {
            try {
                tournDao.abortTournament(tournamentDbId);
            } catch (Exception e) {
                System.err.println("[Tournament] abort DB error: " + e.getMessage());
            }
            phase = Phase.IDLE;
            tournamentDbId = -1;
            tournamentPoints.clear();
            broadcast(new Packet(Protocol.S_ERROR)
                    .put("msg", "Tournament aborted — a player disconnected."));
        }
        lobby.broadcastLobby();
    }

    public int tournamentPoints(String name) {
        return tournamentPoints.getOrDefault(name, 0);
    }

    private void handleSemiResult(ClientHandler winner, ClientHandler loser) {
        // Identify which semi this is by checking the pairing, not by player[0] only.
        // Semi 1 = semifinalists[0] vs semifinalists[1]
        // Semi 2 = semifinalists[2] vs semifinalists[3]
        boolean isSemi1 = (semifinalists.get(0) == winner && semifinalists.get(1) == loser) ||
                (semifinalists.get(0) == loser && semifinalists.get(1) == winner);
        boolean isSemi2 = (semifinalists.get(2) == winner && semifinalists.get(3) == loser) ||
                (semifinalists.get(2) == loser && semifinalists.get(3) == winner);

        if (!isSemi1 && !isSemi2) {
            // Not a recognized semifinal pairing — ignore so we don't corrupt state.
            System.err.println("[Tournament] ignoring unknown semi result: "
                    + winner.name + " vs " + loser.name);
            return;
        }

        if (isSemi1) {
            if (semiWinner1 != null)
                return; // already recorded
            semiWinner1 = winner;
            semiLoser1 = loser;
        } else {
            if (semiWinner2 != null)
                return; // already recorded
            semiWinner2 = winner;
            semiLoser2 = loser;
        }
        semisFinished++;

        if (semisFinished == 2) {
            phase = Phase.FINALS;
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND).put("round", "FINAL")
                    .put("desc", semiWinner1.name + " vs " + semiWinner2.name
                            + " | 3rd place: " + semiLoser1.name + " vs " + semiLoser2.name));
            lobby.startTournamentMatch(semiWinner1, semiWinner2);
            lobby.startTournamentMatch(semiLoser1, semiLoser2);
        } else {
            // Only one semi done so far — explicitly tell clients the tournament is still
            // running.
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND).put("round", "SEMI_DONE")
                    .put("desc", winner.name + " advances. Waiting for the other semifinal to finish."));
        }
    }

    private void handleFinalResult(ClientHandler winner, ClientHandler loser) {
        if (isGrandFinal(winner, loser)) {
            grandFinalDone = true;
            champion = winner;
            try {
                tournDao.finishTournament(tournamentDbId, winner.name);
            } catch (Exception e) {
                System.err.println("[Tournament] DB finalize error: " + e.getMessage());
            }
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND).put("round", "CHAMPION")
                    .put("desc", "Champion: " + winner.name));
        } else {
            thirdPlaceDone = true;
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND).put("round", "THIRD_PLACE")
                    .put("desc", "Third place: " + winner.name));
        }

        broadcastScoreboard();
        if (grandFinalDone && thirdPlaceDone) {
            phase = Phase.COMPLETE;
            broadcast(new Packet(Protocol.S_TOURNAMENT_ROUND).put("round", "COMPLETE")
                    .put("desc", "Champion: " + (champion == null ? "unknown" : champion.name)));
            phase = Phase.IDLE;
            tournamentDbId = -1;
        }
    }

    private boolean isGrandFinal(ClientHandler a, ClientHandler b) {
        return (semiWinner1 == a || semiWinner1 == b) && (semiWinner2 == a || semiWinner2 == b);
    }

    private void recordBracket(String round, ClientHandler winner, ClientHandler loser) {
        try {
            Long tid = tournamentDbId > 0 ? tournamentDbId : null;
            tournDao.recordBracket(round, winner.name, loser.name, winner.name, tid);
        } catch (Exception e) {
            System.err.println("[Tournament] bracket DB error: " + e.getMessage());
        }
    }

    private void broadcastTournamentScore() {
        StringBuilder sb = new StringBuilder();
        tournamentPoints.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> {
                    if (sb.length() > 0)
                        sb.append('|');
                    sb.append(e.getKey()).append(':').append(e.getValue());
                });
        broadcast(new Packet(Protocol.S_TOURNAMENT_SCORE).put("entries", sb.toString()));
    }

    private void updateKing(ClientHandler winner, ClientHandler loser) {
        refreshKingFromDb();
        lobby.broadcastLobby();
    }

    private void refreshKingFromDb() {
        try {
            List<PlayerDao.PlayerScore> ranking = dao.playerDao().getGlobalRanking();
            if (!ranking.isEmpty()) {
                String topPlayer = ranking.get(0).username();
                ClientHandler found = null;
                for (ClientHandler ch : lobby.all()) {
                    if (ch.name.equals(topPlayer)) {
                        found = ch;
                        break;
                    }
                }
                king = found;
                kingName = topPlayer;
            }
        } catch (Exception e) {
            System.err.println("[Tournament] refreshKing error: " + e.getMessage());
        }
    }

    void broadcastScoreboard() {
        try {
            List<PlayerDao.PlayerScore> ranking = dao.playerDao().getGlobalRanking();
            StringBuilder sb = new StringBuilder();
            for (PlayerDao.PlayerScore ps : ranking) {
                if (sb.length() > 0)
                    sb.append('|');
                sb.append(ps.username()).append(':').append(ps.totalPoints()).append(':').append(ps.rank());
            }
            if (!ranking.isEmpty()) {
                String topPlayer = ranking.get(0).username();
                ClientHandler found = null;
                for (ClientHandler ch : lobby.all()) {
                    if (ch.name.equals(topPlayer)) {
                        found = ch;
                        break;
                    }
                }
                king = found;
                kingName = topPlayer;
            }
            Packet p = new Packet(Protocol.S_SCOREBOARD).put("entries", sb.toString());
            for (ClientHandler ch : lobby.all())
                ch.send(p);
            lobby.broadcastLobby();
        } catch (Exception e) {
            System.err.println("[Tournament] scoreboard error: " + e.getMessage());
        }
    }

    private void broadcast(Packet p) {
        for (ClientHandler ch : lobby.all())
            ch.send(p);
    }
}
