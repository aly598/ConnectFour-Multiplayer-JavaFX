package com.example.demo.model;

/** Game modes chosen by the client when joining the lobby. */
public enum GameMode {
    NORMAL("Normal Mode", 4, false, 0),
    TOURNAMENT("Tournament Mode", 4, false, 20),
    EXTRAS("Extras Mode", 5, true, 20);

    private final String displayName;
    private final int defaultWinLength;
    private final boolean defaultPowersEnabled;
    private final int defaultTurnSeconds;

    GameMode(String displayName, int defaultWinLength, boolean defaultPowersEnabled, int defaultTurnSeconds) {
        this.displayName = displayName;
        this.defaultWinLength = defaultWinLength;
        this.defaultPowersEnabled = defaultPowersEnabled;
        this.defaultTurnSeconds = defaultTurnSeconds;
    }

    public String displayName() { return displayName; }
    public int defaultWinLength() { return defaultWinLength; }
    public boolean defaultPowersEnabled() { return defaultPowersEnabled; }
    public int defaultTurnSeconds() { return defaultTurnSeconds; }

    public String wireName() { return name(); }

    public static GameMode fromWire(String raw) {
        if (raw == null || raw.isBlank()) return NORMAL;
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "TOURNAMENT", "TOURNAMENT_MODE" -> TOURNAMENT;
            case "EXTRAS", "EXTRAS_MODE", "EXTRA" -> EXTRAS;
            default -> NORMAL;
        };
    }
}
