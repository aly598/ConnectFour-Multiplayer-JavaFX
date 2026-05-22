package com.example.demo.server;

import com.example.demo.model.GameMode;
import com.example.demo.net.Packet;
import com.example.demo.net.PacketChannel;
import com.example.demo.net.Protocol;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/** Per-client thread on the server. */
public final class ClientHandler implements Runnable {
    public final String id = UUID.randomUUID().toString().substring(0, 8);
    public volatile String name = "anon";
    public volatile GameMode mode = GameMode.NORMAL;
    public volatile int preferredVariant = GameMode.NORMAL.defaultWinLength();
    public volatile boolean powersEnabled = GameMode.NORMAL.defaultPowersEnabled();
    public volatile int preferredTurnSeconds = GameMode.NORMAL.defaultTurnSeconds();
    private final PacketChannel channel;
    private final Server server;
    public volatile MatchSession currentMatch;

    public ClientHandler(Socket socket, Server server) throws IOException {
        this.channel = new PacketChannel(socket);
        this.server = server;
    }

    public void send(Packet p) {
        try { channel.send(p); } catch (IOException e) { close(); }
    }

    @Override public void run() {
        try {
            channel.send(new Packet(Protocol.S_WELCOME).put("playerId", id));
            Packet p;
            while ((p = channel.receive()) != null) handle(p);
        } catch (IOException e) {
            // client disconnected
        } finally {
            close();
            server.lobby().leave(this);
        }
    }

    private void handle(Packet p) {
        switch (p.type) {
            case Protocol.C_HELLO -> {
                name = sanitizeName(p.get("name", name));
                configureGamePreferences(p);
                try {
                    server.matchDao().playerDao().ensurePlayer(name);
                } catch (Exception e) {
                    System.err.println("[DB] ensurePlayer: " + e.getMessage());
                }
                server.lobby().join(this);
                server.lobby().tournament().broadcastScoreboard();
            }
            case Protocol.C_JOIN_LOBBY -> {
                server.lobby().broadcastLobby();
                server.lobby().tournament().broadcastScoreboard();
            }
            case Protocol.C_CHALLENGE -> server.lobby().challenge(this, p.get("target"));
            case Protocol.C_CHALLENGE_RESPONSE -> server.lobby().answerChallenge(this, p.get("challengeId"), Boolean.parseBoolean(p.get("accepted", "false")));
            case Protocol.C_DROP -> {
                MatchSession m = currentMatch;
                if (m != null) m.onDrop(this, p);
                else send(new Packet(Protocol.S_ERROR).put("msg", "You are not currently in a match."));
            }
            case Protocol.C_RESIGN -> {
                MatchSession m = currentMatch;
                if (m != null) m.onResign(this);
            }
            case Protocol.C_WIN_CONFIRM -> {
                MatchSession m = currentMatch;
                if (m != null) m.onWinConfirm(this);
            }
        }
    }

    private void configureGamePreferences(Packet p) {
        mode = GameMode.fromWire(p.get("mode", GameMode.NORMAL.wireName()));
        preferredVariant = normalizeVariant(p.getInt("variant", mode.defaultWinLength()), mode.defaultWinLength());
        powersEnabled = Boolean.parseBoolean(p.get("powers", String.valueOf(mode.defaultPowersEnabled())));
        preferredTurnSeconds = Math.max(0, p.getInt("turnSeconds", mode.defaultTurnSeconds()));

        // Server-side safety: keep each menu mode tied to its intended rules.
        if (mode == GameMode.NORMAL) {
            preferredVariant = 4;
            powersEnabled = false;
            preferredTurnSeconds = 0;
        } else if (mode == GameMode.TOURNAMENT) {
            preferredVariant = 4;
            powersEnabled = false;
            if (preferredTurnSeconds == 0) preferredTurnSeconds = GameMode.TOURNAMENT.defaultTurnSeconds();
        } else if (mode == GameMode.EXTRAS) {
            preferredVariant = 5;
            powersEnabled = true;
            if (preferredTurnSeconds == 0) preferredTurnSeconds = GameMode.EXTRAS.defaultTurnSeconds();
        }
    }

    private static int normalizeVariant(int requested, int fallback) {
        if (requested == 5) return 5;
        if (requested == 4) return 4;
        return fallback == 5 ? 5 : 4;
    }

    public String lobbyDisplayName() {
        return name + " [" + switch (mode) {
            case NORMAL -> "Normal";
            case TOURNAMENT -> "Tournament";
            case EXTRAS -> "Extras";
        } + "]";
    }

    private static String sanitizeName(String raw) {
        String value = raw == null ? "anon" : raw.trim();
        value = value.replace('|', ' ').replace(',', ' ').replace(':', ' ').replace('=', ' ');
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) value = "anon";
        if (value.length() > 32) value = value.substring(0, 32).trim();
        return value;
    }

    public void close() {
        try { channel.close(); } catch (IOException ignored) {}
    }
}
