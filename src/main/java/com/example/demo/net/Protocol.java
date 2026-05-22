package com.example.demo.net;

/**
 * Wire protocol constants.
 * Packets are newline-delimited text: TYPE|field1=value|field2=value
 *
 * EXTENDED – added constants for:
 *   • S_SCOREBOARD  – global ranking broadcast
 *   • S_LOBBY_STATUS – animated status message
 *   • S_TOURNAMENT_START – kicks off the 4-player bracket
 *   • C_PLAYER_COUNT – server notifies count change
 */
public final class Protocol {
    private Protocol() {}

    // ── Client → Server ───────────────────────────────────────────────────
    public static final String C_HELLO        = "HELLO";        // name=...|mode=NORMAL|TOURNAMENT|EXTRAS|variant=4|5|powers=true|false|turnSeconds=N
    public static final String C_JOIN_LOBBY   = "JOIN_LOBBY";
    public static final String C_CHALLENGE    = "CHALLENGE";    // target=playerId
    public static final String C_CHALLENGE_RESPONSE = "CHALLENGE_RESPONSE"; // challengeId=..|accepted=true|false
    public static final String C_DROP         = "DROP";         // matchId=..|col=..|power=NONE|CLEAR_COLUMN|DOUBLE_POINTS
    public static final String C_WIN_CONFIRM  = "WIN_CONFIRM";  // matchId=..
    public static final String C_RESIGN       = "RESIGN";       // matchId=..

    // ── Server → Client ───────────────────────────────────────────────────
    public static final String S_WELCOME         = "WELCOME";       // playerId=..
    public static final String S_LOBBY           = "LOBBY";         // players=a:id,..|king=..|count=N
    public static final String S_LOBBY_STATUS    = "LOBBY_STATUS";  // msg=..|countdown=N (N=-1 means no countdown)
    public static final String S_CHALLENGE_REQUEST = "CHALLENGE_REQUEST"; // challengeId=..|fromId=..|fromName=..|mode=..
    public static final String S_CHALLENGE_STATUS  = "CHALLENGE_STATUS";  // challengeId=..|status=PENDING|ACCEPTED|DECLINED|EXPIRED|CANCELLED|msg=..
    public static final String S_MATCH_START     = "MATCH_START";   // matchId=..|opponent=..|youAre=R|Y|variant=4|5|turnSeconds=N|powers=true|false
    public static final String S_STATE           = "STATE";         // matchId=..|board=...|toMove=R|Y|deadline=...|redClear=...
    public static final String S_WIN             = "WIN";           // matchId=..|winner=..|sequence=r1c1;r2c2,...|score=N
    public static final String S_DRAW            = "DRAW";          // matchId=..
    public static final String S_TIMEOUT         = "TIMEOUT";       // matchId=..|loser=..
    public static final String S_ERROR           = "ERROR";         // msg=..
    public static final String S_SCOREBOARD      = "SCOREBOARD";    // entries=name:pts:rank|name:pts:rank|...
    public static final String S_TOURNAMENT_START= "TOURNAMENT_START"; // match1=p1vsp2|match2=p3vsp4
    public static final String S_TOURNAMENT_ROUND= "TOURNAMENT_ROUND"; // round=FINAL|THIRD|desc=..
    public static final String S_TOURNAMENT_SCORE= "TOURNAMENT_SCORE"; // entries=name:pts|name:pts

    public static final int DEFAULT_PORT = 5555;
}
