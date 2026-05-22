package com.example.demo.server;

import com.example.demo.db.MatchDao;
import com.example.demo.model.Board;
import com.example.demo.model.PowerDisc;
import com.example.demo.net.Packet;
import com.example.demo.net.Protocol;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** A single Connect Four match between two clients. */
public final class MatchSession {
    public final String id = UUID.randomUUID().toString().substring(0, 8);
    private final ClientHandler red, yellow;
    private final Board board;
    private final int turnSeconds;
    private final boolean powersEnabled;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "match-timer");
        t.setDaemon(true);
        return t;
    });
    private final HighlightRecorder recorder;
    private final MatchDao dao;
    private final TournamentManager tournament;

    private volatile int toMove = Board.RED;
    private volatile boolean ended = false;
    private ScheduledFuture<?> deadline;

    private boolean redClearAvailable;
    private boolean yellowClearAvailable;
    private boolean redDoubleAvailable;
    private boolean yellowDoubleAvailable;

    public MatchSession(ClientHandler red, ClientHandler yellow, int variant,
                        int turnSeconds, boolean powersEnabled,
                        HighlightRecorder rec, MatchDao dao,
                        TournamentManager tournament) {
        this.red = red;
        this.yellow = yellow;
        this.board = new Board(6, 7, variant == 5 ? 5 : 4);
        this.turnSeconds = Math.max(0, turnSeconds);
        this.powersEnabled = powersEnabled;
        this.redClearAvailable = powersEnabled;
        this.yellowClearAvailable = powersEnabled;
        this.redDoubleAvailable = powersEnabled;
        this.yellowDoubleAvailable = powersEnabled;
        this.recorder = rec;
        this.dao = dao;
        this.tournament = tournament;
        red.currentMatch = this;
        yellow.currentMatch = this;
    }

    public void start() {
        notifyStart(red, "R");
        notifyStart(yellow, "Y");
        broadcastState();
        armTimer();
    }

    private void notifyStart(ClientHandler h, String youAre) {
        h.send(new Packet(Protocol.S_MATCH_START)
                .put("matchId", id)
                .put("opponent", h == red ? yellow.name : red.name)
                .put("youAre", youAre)
                .put("variant", String.valueOf(board.winLen))
                .put("turnSeconds", String.valueOf(turnSeconds))
                .put("powers", String.valueOf(powersEnabled)));
    }

    private void armTimer() {
        if (deadline != null) deadline.cancel(false);
        if (turnSeconds <= 0 || ended) return;
        deadline = timer.schedule(this::onTimeout, turnSeconds, TimeUnit.SECONDS);
    }

    private synchronized void onTimeout() {
        if (ended || turnSeconds <= 0) return;
        ClientHandler loser = toMove == Board.RED ? red : yellow;
        ClientHandler winner = loser == red ? yellow : red;
        Packet p = new Packet(Protocol.S_TIMEOUT).put("matchId", id).put("loser", loser.name);
        red.send(p);
        yellow.send(p);
        finishWin(winner, loser, List.of(), 1, "TIMEOUT");
    }

    public synchronized void onResign(ClientHandler who) {
        if (ended) return;
        ClientHandler winner = who == red ? yellow : red;
        finishWin(winner, who, List.of(), 1, "RESIGN");
    }

    public synchronized void onWinConfirm(ClientHandler who) { /* ack only */ }

    public synchronized void onDrop(ClientHandler who, Packet pkt) {
        if (ended) return;
        if (!id.equals(pkt.get("matchId", id))) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", "This move belongs to a different match."));
            return;
        }
        int player = who == red ? Board.RED : Board.YEL;
        if (player != toMove) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", "Not your turn."));
            return;
        }

        int col = pkt.getInt("col", -1);
        PowerDisc power = parsePower(pkt.get("power", PowerDisc.NONE.name()));
        if (!powersEnabled) power = PowerDisc.NONE;

        if (power == PowerDisc.CLEAR_COLUMN) {
            handleClearColumn(who, player, col);
            return;
        }

        boolean doublePointsUsed = power == PowerDisc.DOUBLE_POINTS;
        if (doublePointsUsed && !hasPower(player, PowerDisc.DOUBLE_POINTS)) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", "Double Points has already been used."));
            return;
        }

        int row = board.drop(col, player);
        if (row < 0) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", col < 0 ? "Invalid column." : "Column is full."));
            return;
        }
        if (doublePointsUsed) consumePower(player, PowerDisc.DOUBLE_POINTS);

        recorder.enqueue(new HighlightRecorder.Frame(id, board.encode(), col, row, player));

        List<int[]> win = board.findWin(row, col);
        if (win != null) {
            int score = board.winLen >= 5 ? 2 : 1;
            String scoreType = board.winLen >= 5 ? "FIVE_IN_A_ROW" : "NORMAL";
            if (doublePointsUsed) {
                score *= 2;
                scoreType = scoreType + "_DOUBLE_POINTS";
            }
            broadcastState();
            Packet w = new Packet(Protocol.S_WIN).put("matchId", id).put("winner", who.name)
                    .put("sequence", encodeSeq(win)).put("score", String.valueOf(score));
            red.send(w);
            yellow.send(w);
            ClientHandler loser = who == red ? yellow : red;
            finishWin(who, loser, win, score, scoreType);
            return;
        }

        if (board.isFull()) {
            Packet d = new Packet(Protocol.S_DRAW).put("matchId", id);
            red.send(d);
            yellow.send(d);
            ended = true;
            cleanup();
            tournament.onMatchDraw(red, yellow);
            return;
        }
        advanceTurn();
    }

    private void handleClearColumn(ClientHandler who, int player, int col) {
        if (!hasPower(player, PowerDisc.CLEAR_COLUMN)) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", "Clear Column has already been used."));
            return;
        }
        if (col < 0 || col >= board.cols) {
            who.send(new Packet(Protocol.S_ERROR).put("msg", "Invalid column."));
            return;
        }
        board.clearColumn(col);
        consumePower(player, PowerDisc.CLEAR_COLUMN);
        recorder.enqueue(new HighlightRecorder.Frame(id, board.encode(), col, -1, player));
        advanceTurn();
    }

    private void advanceTurn() {
        toMove = toMove == Board.RED ? Board.YEL : Board.RED;
        broadcastState();
        armTimer();
    }

    private boolean hasPower(int player, PowerDisc power) {
        if (!powersEnabled) return false;
        return switch (power) {
            case CLEAR_COLUMN -> player == Board.RED ? redClearAvailable : yellowClearAvailable;
            case DOUBLE_POINTS -> player == Board.RED ? redDoubleAvailable : yellowDoubleAvailable;
            case NONE -> true;
        };
    }

    private void consumePower(int player, PowerDisc power) {
        if (power == PowerDisc.CLEAR_COLUMN) {
            if (player == Board.RED) redClearAvailable = false;
            else yellowClearAvailable = false;
        } else if (power == PowerDisc.DOUBLE_POINTS) {
            if (player == Board.RED) redDoubleAvailable = false;
            else yellowDoubleAvailable = false;
        }
    }

    private void finishWin(ClientHandler winner, ClientHandler loser,
                           List<int[]> seq, int score, String scoreType) {
        ended = true;
        try {
            dao.recordWin(id, winner.name, loser.name, board.winLen, score, seq, board.encode(),
                    seq.isEmpty() ? 0 : seq.size(), scoreType);
        } catch (Exception e) {
            System.err.println("[DB] record failed: " + e.getMessage());
        }
        recorder.enqueue(new HighlightRecorder.Flush(id, winner.name, seq));
        tournament.onMatchEnd(winner, loser, score);
        tournament.broadcastScoreboard();
        cleanup();
    }

    private void cleanup() {
        if (deadline != null) deadline.cancel(false);
        timer.shutdownNow();
        if (red.currentMatch == this) red.currentMatch = null;
        if (yellow.currentMatch == this) yellow.currentMatch = null;
    }

    private void broadcastState() {
        long deadlineMs = turnSeconds > 0 ? System.currentTimeMillis() + turnSeconds * 1000L : 0L;
        Packet s = new Packet(Protocol.S_STATE).put("matchId", id).put("board", board.encode())
                .put("toMove", toMove == Board.RED ? "R" : "Y")
                .put("deadline", String.valueOf(deadlineMs))
                .put("powers", String.valueOf(powersEnabled))
                .put("redClear", String.valueOf(redClearAvailable))
                .put("redDouble", String.valueOf(redDoubleAvailable))
                .put("yelClear", String.valueOf(yellowClearAvailable))
                .put("yelDouble", String.valueOf(yellowDoubleAvailable));
        red.send(s);
        yellow.send(s);
    }

    private String encodeSeq(List<int[]> seq) {
        StringBuilder sb = new StringBuilder();
        for (int[] rc : seq) {
            if (sb.length() > 0) sb.append(';');
            sb.append(rc[0]).append(',').append(rc[1]);
        }
        return sb.toString();
    }

    private static PowerDisc parsePower(String raw) {
        if (raw == null || raw.isBlank()) return PowerDisc.NONE;
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CLEAR", "CLEAR_COLUMN" -> PowerDisc.CLEAR_COLUMN;
            case "DOUBLE", "DOUBLE_POINTS" -> PowerDisc.DOUBLE_POINTS;
            default -> PowerDisc.NONE;
        };
    }
}
