package com.example.demo.server;

import com.example.demo.db.MatchDao;
import com.example.demo.model.GameMode;
import com.example.demo.net.Packet;
import com.example.demo.net.Protocol;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lobby of connected clients plus matchmaking/countdown orchestration. */
public final class Lobby {
    private final Map<String, ClientHandler> clients = new LinkedHashMap<>();
    private final HighlightRecorder recorder;
    private final MatchDao dao;
    private final TournamentManager tournament;
    private final int defaultVariant, defaultTurnSeconds;

    private final Map<String, PendingChallenge> pendingChallenges = new HashMap<>();
    private static final long CHALLENGE_TIMEOUT_MS = 30_000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lobby-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> countdownFuture;
    private final AtomicBoolean countdownRunning = new AtomicBoolean(false);

    private final com.example.demo.db.TournamentDao tournDao;

    private record PendingChallenge(String id, ClientHandler challenger, ClientHandler target, long createdAtMs) {
    }

    public Lobby(HighlightRecorder rec, MatchDao dao, com.example.demo.db.TournamentDao tournDao,
            int variant, int turnSeconds) {
        this.recorder = rec;
        this.dao = dao;
        this.tournDao = tournDao;
        this.defaultVariant = variant == 5 ? 5 : 4;
        this.defaultTurnSeconds = Math.max(0, turnSeconds);
        this.tournament = new TournamentManager(this, dao, tournDao);
    }

    public TournamentManager tournament() {
        return tournament;
    }

    public synchronized void join(ClientHandler h) {
        clients.put(h.id, h);
        broadcastLobby();
        updateLobbyStatus();
        // If a tournament is currently running, tell the newcomer explicitly,
        // and re-send the tournament scoreboard so they see live state.
        if (tournament.isTournamentActive()) {
            h.send(new Packet(Protocol.S_LOBBY_STATUS)
                    .put("msg", "A tournament is currently running. New tournament players queue for the next one.")
                    .put("countdown", "-1"));
            tournament.broadcastScoreboard();
        }
    }

    public synchronized void leave(ClientHandler h) {
        clearPendingChallengesFor(h, "Player disconnected.");
        clients.remove(h.id);
        tournament.onLeave(h);
        cancelCountdown();
        broadcastLobby();
        updateLobbyStatus();
    }

    public synchronized Collection<ClientHandler> all() {
        return List.copyOf(clients.values());
    }

    public synchronized void broadcastLobby() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler c : clients.values()) {
            if (sb.length() > 0)
                sb.append(',');
            sb.append(c.lobbyDisplayName()).append(':').append(c.id);
        }
        Packet p = new Packet(Protocol.S_LOBBY)
                .put("players", sb.toString())
                .put("king", tournament.kingName())
                .put("count", String.valueOf(clients.size()));
        for (ClientHandler c : clients.values())
            c.send(p);
    }

    private void updateLobbyStatus() {
        if (tournament.isTournamentActive()) {
            broadcastStatus("A tournament is currently running. Wait for it to end.", -1);
            return;
        }
        int tournamentAvailable = availableTournamentPlayers().size();
        int totalAvailable = availablePlayers().size();
        int total = clients.size();

        if (tournamentAvailable < 4)
            cancelCountdown();

        if (total == 0)
            return;
        if (totalAvailable == 0) {
            broadcastStatus("All connected players are currently in a match.", -1);
            return;
        }
        if (tournamentAvailable >= 4) {
            if (!countdownRunning.get())
                startCountdown();
            return;
        }

        String msg = switch (tournamentAvailable) {
            case 0 ->
                "Normal/Extras players can send challenge requests to players in the same mode. The other player must accept before a match starts.";
            case 1 -> "1 tournament player waiting. Tournament starts automatically at 4.";
            case 2 -> "2 tournament players waiting. Need 2 more for a tournament.";
            case 3 -> "3 tournament players waiting. Need 1 more for a tournament.";
            default -> "Tournament players are ready.";
        };
        broadcastStatus(msg, -1);
    }

    private void broadcastStatus(String msg, int countdown) {
        Packet p = new Packet(Protocol.S_LOBBY_STATUS)
                .put("msg", msg)
                .put("countdown", String.valueOf(countdown));
        for (ClientHandler c : clients.values())
            c.send(p);
    }

    private void startCountdown() {
        countdownRunning.set(true);
        countdownFuture = scheduler.scheduleAtFixedRate(new CountdownTask(), 0, 1, TimeUnit.SECONDS);
    }

    private void cancelCountdown() {
        if (countdownFuture != null) {
            countdownFuture.cancel(false);
            countdownFuture = null;
        }
        countdownRunning.set(false);
    }

    private List<ClientHandler> availablePlayers() {
        List<ClientHandler> available = new ArrayList<>();
        for (ClientHandler c : clients.values()) {
            if (c.currentMatch == null)
                available.add(c);
        }
        return available;
    }

    private List<ClientHandler> availableTournamentPlayers() {
        List<ClientHandler> available = new ArrayList<>();
        for (ClientHandler c : clients.values()) {
            if (c.currentMatch == null && c.mode == GameMode.TOURNAMENT)
                available.add(c);
        }
        return available;
    }

    private final class CountdownTask implements Runnable {
        private int remaining = 3;

        @Override
        public void run() {
            synchronized (Lobby.this) {
                if (tournament.isTournamentActive()) {
                    cancelCountdown();
                    return;
                }
                List<ClientHandler> available = availableTournamentPlayers();
                if (available.size() < 4) {
                    cancelCountdown();
                    updateLobbyStatus();
                    return;
                }
                if (remaining > 0) {
                    broadcastStatus("Tournament starts in " + remaining + "...", remaining);
                    remaining--;
                } else {
                    cancelCountdown();
                    tournament.startFourPlayerTournament(new ArrayList<>(available.subList(0, 4)));
                }
            }
        }
    }

    public synchronized void challenge(ClientHandler challenger, String targetId) {
        ClientHandler target = clients.get(targetId);
        if (!canRequestChallenge(challenger, target))
            return;

        cleanupExpiredChallenges();
        if (hasPendingChallenge(challenger)) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg",
                    "You already have a pending challenge. Wait for the response or timeout."));
            return;
        }
        if (hasPendingChallenge(target)) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg", target.name + " already has a pending challenge."));
            return;
        }

        String challengeId = UUID.randomUUID().toString().substring(0, 8);
        PendingChallenge pending = new PendingChallenge(challengeId, challenger, target, System.currentTimeMillis());
        pendingChallenges.put(challengeId, pending);

        target.send(new Packet(Protocol.S_CHALLENGE_REQUEST)
                .put("challengeId", challengeId)
                .put("fromId", challenger.id)
                .put("fromName", challenger.name)
                .put("mode", challenger.mode.displayName()));

        challenger.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                .put("challengeId", challengeId)
                .put("status", "PENDING")
                .put("msg", "Challenge sent to " + target.name + ". Waiting for them to accept."));

        scheduler.schedule(() -> expireChallenge(challengeId), CHALLENGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void answerChallenge(ClientHandler target, String challengeId, boolean accepted) {
        cleanupExpiredChallenges();
        PendingChallenge pending = pendingChallenges.remove(challengeId);
        if (pending == null || pending.target() != target) {
            target.send(new Packet(Protocol.S_ERROR).put("msg", "That challenge is no longer available."));
            return;
        }

        ClientHandler challenger = pending.challenger();
        if (!accepted) {
            target.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                    .put("challengeId", challengeId)
                    .put("status", "DECLINED")
                    .put("msg", "You declined " + challenger.name + "'s challenge."));
            challenger.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                    .put("challengeId", challengeId)
                    .put("status", "DECLINED")
                    .put("msg", target.name + " declined your challenge."));
            return;
        }

        if (!clients.containsKey(challenger.id) || !clients.containsKey(target.id)) {
            notifyChallengeCancelled(pending, "Challenge cancelled because one player left the lobby.");
            return;
        }
        if (challenger.currentMatch != null || target.currentMatch != null) {
            notifyChallengeCancelled(pending, "Challenge cancelled because one player is already in a match.");
            return;
        }
        if (tournament.isTournamentActive()) {
            notifyChallengeCancelled(pending, "Challenge cancelled because a tournament is currently running.");
            return;
        }
        if (challenger.mode != target.mode || challenger.mode == GameMode.TOURNAMENT) {
            notifyChallengeCancelled(pending,
                    "Challenge cancelled because both players must still be available in the same non-tournament mode.");
            return;
        }

        challenger.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                .put("challengeId", challengeId)
                .put("status", "ACCEPTED")
                .put("msg", target.name + " accepted your challenge. Starting match..."));
        target.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                .put("challengeId", challengeId)
                .put("status", "ACCEPTED")
                .put("msg", "You accepted " + challenger.name + "'s challenge. Starting match..."));

        clearPendingChallengesFor(challenger, "Challenge cancelled because a match is starting.");
        clearPendingChallengesFor(target, "Challenge cancelled because a match is starting.");
        cancelCountdown();
        startMatch(challenger, target);
        broadcastLobby();
        updateLobbyStatus();
    }

    private boolean canRequestChallenge(ClientHandler challenger, ClientHandler target) {
        if (target == null || target == challenger) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg", "Invalid target."));
            return false;
        }
        if (tournament.isTournamentActive()) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg", "A tournament is currently running."));
            return false;
        }
        if (challenger.currentMatch != null || target.currentMatch != null) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg", "Player is busy."));
            return false;
        }
        if (challenger.mode == GameMode.TOURNAMENT || target.mode == GameMode.TOURNAMENT) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg",
                    "Tournament mode starts automatically when 4 tournament players are ready."));
            return false;
        }
        if (challenger.mode != target.mode) {
            challenger.send(new Packet(Protocol.S_ERROR).put("msg",
                    "Pick a player in the same mode: Normal vs Normal, or Extras vs Extras."));
            return false;
        }
        return true;
    }

    private boolean hasPendingChallenge(ClientHandler h) {
        for (PendingChallenge pending : pendingChallenges.values()) {
            if (pending.challenger() == h || pending.target() == h)
                return true;
        }
        return false;
    }

    private void expireChallenge(String challengeId) {
        synchronized (this) {
            PendingChallenge pending = pendingChallenges.remove(challengeId);
            if (pending == null)
                return;
            pending.challenger().send(new Packet(Protocol.S_CHALLENGE_STATUS)
                    .put("challengeId", challengeId)
                    .put("status", "EXPIRED")
                    .put("msg", "Challenge to " + pending.target().name + " expired."));
            pending.target().send(new Packet(Protocol.S_CHALLENGE_STATUS)
                    .put("challengeId", challengeId)
                    .put("status", "EXPIRED")
                    .put("msg", "Challenge from " + pending.challenger().name + " expired."));
        }
    }

    private void cleanupExpiredChallenges() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (PendingChallenge pending : pendingChallenges.values()) {
            if (now - pending.createdAtMs() >= CHALLENGE_TIMEOUT_MS)
                expired.add(pending.id());
        }
        for (String id : expired)
            expireChallenge(id);
    }

    private void clearPendingChallengesFor(ClientHandler h, String reason) {
        List<PendingChallenge> toCancel = new ArrayList<>();
        for (PendingChallenge pending : pendingChallenges.values()) {
            if (pending.challenger() == h || pending.target() == h)
                toCancel.add(pending);
        }
        for (PendingChallenge pending : toCancel) {
            pendingChallenges.remove(pending.id());
            ClientHandler other = pending.challenger() == h ? pending.target() : pending.challenger();
            if (clients.containsKey(other.id)) {
                other.send(new Packet(Protocol.S_CHALLENGE_STATUS)
                        .put("challengeId", pending.id())
                        .put("status", "CANCELLED")
                        .put("msg", reason));
            }
        }
    }

    private void notifyChallengeCancelled(PendingChallenge pending, String msg) {
        pending.challenger().send(new Packet(Protocol.S_CHALLENGE_STATUS)
                .put("challengeId", pending.id())
                .put("status", "CANCELLED")
                .put("msg", msg));
        pending.target().send(new Packet(Protocol.S_CHALLENGE_STATUS)
                .put("challengeId", pending.id())
                .put("status", "CANCELLED")
                .put("msg", msg));
    }

    public MatchSession startMatch(ClientHandler red, ClientHandler yellow) {
        int matchVariant = red.mode == GameMode.EXTRAS && yellow.mode == GameMode.EXTRAS ? 5 : 4;
        boolean powers = red.mode == GameMode.EXTRAS && yellow.mode == GameMode.EXTRAS
                && red.powersEnabled && yellow.powersEnabled;
        int turnSeconds = powers ? Math.max(red.preferredTurnSeconds, yellow.preferredTurnSeconds) : 0;
        MatchSession m = new MatchSession(red, yellow, matchVariant, turnSeconds, powers, recorder, dao, tournament);
        m.start();
        return m;
    }

    public MatchSession startTournamentMatch(ClientHandler red, ClientHandler yellow) {
        int turnSeconds = defaultTurnSeconds > 0 ? defaultTurnSeconds : GameMode.TOURNAMENT.defaultTurnSeconds();
        MatchSession m = new MatchSession(red, yellow, 4, turnSeconds, false, recorder, dao, tournament);
        m.start();
        return m;
    }
}
