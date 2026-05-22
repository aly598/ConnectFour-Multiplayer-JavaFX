package com.example.demo.client;

import com.example.demo.model.Board;
import com.example.demo.model.GameMode;
import com.example.demo.model.PowerDisc;
import com.example.demo.net.Packet;
import com.example.demo.net.Protocol;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** Main JavaFX window. */
public final class MainWindow {

    private final NetworkClient net;
    private final String playerName;
    private final GameMode gameMode;
    private final int requestedVariant;
    private final boolean requestedPowers;
    private final int requestedTurnSeconds;

    private final BorderPane root = new BorderPane();
    private final LobbyView lobby;
    private final Label status    = new Label("Connecting...");
    private final Label timerLabel = new Label("");
    private final Circle myDisc   = new Circle(8);
    private final Label nameLabel = new Label();
    private final Label movesLabel = new Label();

    private final HBox powerBar = new HBox(8);
    private final ToggleGroup powerToggleGroup = new ToggleGroup();
    private final ToggleButton normalDropBtn = new ToggleButton("Normal drop");
    private final ToggleButton clearColumnBtn = new ToggleButton("Clear column");
    private final ToggleButton doublePointsBtn = new ToggleButton("Double points");

    private BoardView boardView;
    private String currentMatchId;
    private String mySide;
    private long deadlineMs;
    private AnimationTimer countdown;

    private volatile boolean animating = false;
    private String opponentName;
    private String lastEncoded = null;
    private boolean powersEnabledForMatch = false;

    public MainWindow(Stage stage, String name, String host, int port,
                      GameMode mode, int variant, boolean powersEnabled, int turnSeconds) throws Exception {
        this.playerName = name;
        this.gameMode = mode == null ? GameMode.NORMAL : mode;
        this.requestedVariant = normalizeVariant(variant, this.gameMode.defaultWinLength());
        this.requestedPowers = powersEnabled;
        this.requestedTurnSeconds = Math.max(0, turnSeconds);
        this.lobby = new LobbyView(this::sendChallenge);

        this.net = new NetworkClient(host, port, p -> Platform.runLater(() -> handle(p)));

        myDisc.setFill(Color.GRAY);
        myDisc.setStroke(Color.web("#555"));
        myDisc.setStrokeWidth(1.5);
        myDisc.setVisible(false);

        nameLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        nameLabel.setFont(new Font(14));

        HBox discName = new HBox(6, myDisc, nameLabel);
        discName.setAlignment(Pos.CENTER_LEFT);

        movesLabel.setStyle("-fx-text-fill: #aaa;");
        movesLabel.setFont(new Font(13));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(12, discName, status, spacer, movesLabel, timerLabel);
        topBar.setPadding(new Insets(8));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #222;");
        status.setStyle("-fx-text-fill: white;");
        status.setFont(new Font(14));
        timerLabel.setStyle("-fx-text-fill: #ffd166;");
        timerLabel.setFont(new Font(14));

        configurePowerBar();
        VBox top = new VBox(topBar, powerBar);
        root.setTop(top);

        lobby.setMode(gameMode.displayName());
        lobby.setChallengeEnabled(gameMode != GameMode.TOURNAMENT);
        root.setLeft(lobby);
        root.setCenter(new Label(initialCenterText()));

        stage.setScene(new Scene(root, 1060, 640));
        stage.setTitle("Connect Four — " + name + " — " + gameMode.displayName());
        stage.show();

        net.send(new Packet(Protocol.C_HELLO)
                .put("name", name)
                .put("mode", gameMode.wireName())
                .put("variant", String.valueOf(requestedVariant))
                .put("powers", String.valueOf(requestedPowers))
                .put("turnSeconds", String.valueOf(requestedTurnSeconds)));
    }

    private void configurePowerBar() {
        normalDropBtn.setToggleGroup(powerToggleGroup);
        clearColumnBtn.setToggleGroup(powerToggleGroup);
        doublePointsBtn.setToggleGroup(powerToggleGroup);
        normalDropBtn.setUserData(PowerDisc.NONE);
        clearColumnBtn.setUserData(PowerDisc.CLEAR_COLUMN);
        doublePointsBtn.setUserData(PowerDisc.DOUBLE_POINTS);
        normalDropBtn.setSelected(true);

        Label title = new Label("Power disc:");
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        powerBar.getChildren().addAll(title, normalDropBtn, clearColumnBtn, doublePointsBtn);
        powerBar.setPadding(new Insets(6, 8, 6, 8));
        powerBar.setAlignment(Pos.CENTER_LEFT);
        powerBar.setStyle("-fx-background-color: #333;");
        powerBar.setVisible(false);
        powerBar.setManaged(false);
    }

    private String initialCenterText() {
        return switch (gameMode) {
            case NORMAL -> "Normal mode — pick a player and send a challenge request. The other player must accept before the match starts.";
            case TOURNAMENT -> "Tournament mode — waiting for 4 tournament players. Semifinals, final, and 3rd-place match will start automatically.";
            case EXTRAS -> "Extras mode — pick another Extras player and send a challenge request. Win with 5 in a row; each player gets one Clear Column and one Double Points power disc.";
        };
    }

    private void handle(Packet p) {
        switch (p.type) {
            case Protocol.S_WELCOME -> status.setText("Connected as " + playerName + " — " + gameMode.displayName());
            case Protocol.S_LOBBY -> lobby.update(p.get("players"), p.get("king"), p.getInt("count", 0));
            case Protocol.S_LOBBY_STATUS -> lobby.applyStatus(p.get("msg"), p.getInt("countdown", -1));
            case Protocol.S_CHALLENGE_REQUEST -> handleChallengeRequest(p);
            case Protocol.S_CHALLENGE_STATUS -> handleChallengeStatus(p);
            case Protocol.S_MATCH_START -> startMatch(p);
            case Protocol.S_STATE -> handleState(p);
            case Protocol.S_WIN -> handleWin(p);
            case Protocol.S_DRAW -> { status.setText("Draw! Rematch may start if this is a tournament."); currentMatchId = null; stopCountdown(); animating = false; }
            case Protocol.S_TIMEOUT -> { status.setText("⏰ Timeout — " + p.get("loser") + " lost"); currentMatchId = null; stopCountdown(); animating = false; }
            case Protocol.S_ERROR -> { status.setText("⚠ " + p.get("msg")); animating = false; }
            case Protocol.S_SCOREBOARD -> lobby.updateScoreboard(p.get("entries"));
            case Protocol.S_TOURNAMENT_SCORE -> lobby.updateTournamentScoreboard(p.get("entries"));
            case Protocol.S_TOURNAMENT_START ->
                    status.setText("🏆 Tournament! " + p.get("match1") + "  |  " + p.get("match2"));
            case Protocol.S_TOURNAMENT_ROUND -> {
                String round = p.get("round");
                String desc  = p.get("desc");
                if ("COMPLETE".equals(round)) status.setText("🏆 Tournament complete! " + desc);
                else status.setText("🏆 " + round + ": " + desc);
            }
        }
    }

    private void startMatch(Packet p) {
        currentMatchId = p.get("matchId");
        mySide = p.get("youAre");
        opponentName = p.get("opponent");
        lastEncoded = null;
        animating = false;
        int matchVariant = p.getInt("variant", 4);
        int matchTurnSeconds = p.getInt("turnSeconds", 0);
        powersEnabledForMatch = Boolean.parseBoolean(p.get("powers", "false"));
        boardView = new BoardView(6, 7);

        boolean isRed = "R".equalsIgnoreCase(mySide);
        myDisc.setFill(isRed ? Color.web("#e63946") : Color.web("#ffd166"));
        myDisc.setStroke(isRed ? Color.web("#c1121f") : Color.web("#e6b800"));
        myDisc.setVisible(true);
        String colorName = isRed ? "Red" : "Yellow";
        nameLabel.setText(playerName + " (" + colorName + ")");
        movesLabel.setText(playerName + ": 0  |  " + opponentName + ": 0");

        powerBar.setVisible(powersEnabledForMatch);
        powerBar.setManaged(powersEnabledForMatch);
        normalDropBtn.setSelected(true);
        clearColumnBtn.setDisable(!powersEnabledForMatch);
        doublePointsBtn.setDisable(!powersEnabledForMatch);

        boardView.setOnMousePressed(e -> {
            if (animating) return;
            double localX = e.getX() - boardView.getPadding().getLeft();
            int col = boardView.colAtX(localX);
            if (col < 0 || col >= 7) return;
            animating = true;
            PowerDisc power = selectedPowerDisc();
            net.send(new Packet(Protocol.C_DROP)
                    .put("matchId", currentMatchId)
                    .put("col", String.valueOf(col))
                    .put("power", power.name()));
        });

        StackPane wrap = new StackPane(boardView);
        wrap.setPadding(new Insets(20));
        root.setCenter(wrap);

        String timerText = matchTurnSeconds > 0 ? " | " + matchTurnSeconds + "s turns" : "";
        String powerText = powersEnabledForMatch ? " | powers on" : "";
        status.setText("vs " + opponentName + " | connect " + matchVariant + timerText + powerText);
        if (matchTurnSeconds <= 0) stopCountdown();
    }

    private PowerDisc selectedPowerDisc() {
        Toggle selected = powerToggleGroup.getSelectedToggle();
        if (!powersEnabledForMatch || selected == null || selected.getUserData() == null) return PowerDisc.NONE;
        return (PowerDisc) selected.getUserData();
    }

    private void handleState(Packet p) {
        if (boardView == null) return;
        String encoded = p.get("board");
        deadlineMs = parseLong(p.get("deadline", "0"));
        String toMove = p.get("toMove");
        boolean myTurn = toMove.equals(mySide);

        updatePowerButtons(p, myTurn);
        if (deadlineMs > 0) startCountdown(); else stopCountdown();

        int redMoves = 0, yelMoves = 0;
        if (encoded != null) {
            for (int i = 0; i < encoded.length(); i++) {
                int v = encoded.charAt(i) - '0';
                if (v == Board.RED) redMoves++;
                else if (v == Board.YEL) yelMoves++;
            }
        }
        boolean iAmRed = "R".equalsIgnoreCase(mySide);
        int myMoves  = iAmRed ? redMoves : yelMoves;
        int oppMoves = iAmRed ? yelMoves : redMoves;
        movesLabel.setText(playerName + ": " + myMoves + "  |  " + opponentName + ": " + oppMoves);

        int newRow = -1, newCol = -1, newPlayer = 0;
        String currentEncoded = lastEncoded;
        if (currentEncoded != null && encoded != null && currentEncoded.length() >= 42 && encoded.length() >= 42) {
            for (int r = 0; r < 6; r++) {
                for (int c = 0; c < 7; c++) {
                    int idx = r * 7 + c;
                    int prev = currentEncoded.charAt(idx) - '0';
                    int next = encoded.charAt(idx) - '0';
                    if (prev == Board.EMPTY && next != Board.EMPTY) {
                        newRow = r; newCol = c; newPlayer = next;
                    }
                }
            }
        }
        lastEncoded = encoded;

        String turnText = myTurn ? "🟢 Your turn — drop a disc!" : "⏳ Opponent's turn...";
        if (newRow >= 0 && boardView != null) {
            final int fr = newRow, fc = newCol, fp = newPlayer;
            boardView.animateDrop(fr, fc, fp, () -> {
                animating = false;
                status.setText(turnText);
            });
        } else {
            boardView.render(encoded);
            animating = false;
            status.setText(turnText);
        }
    }

    private void updatePowerButtons(Packet p, boolean myTurn) {
        if (!powersEnabledForMatch) return;
        boolean iAmRed = "R".equalsIgnoreCase(mySide);
        boolean clearAvailable = Boolean.parseBoolean(p.get(iAmRed ? "redClear" : "yelClear", "false"));
        boolean doubleAvailable = Boolean.parseBoolean(p.get(iAmRed ? "redDouble" : "yelDouble", "false"));

        clearColumnBtn.setDisable(!myTurn || !clearAvailable);
        doublePointsBtn.setDisable(!myTurn || !doubleAvailable);
        normalDropBtn.setDisable(!myTurn);

        Toggle selected = powerToggleGroup.getSelectedToggle();
        if (selected == null || (selected instanceof ToggleButton tb && tb.isDisable())) {
            normalDropBtn.setSelected(true);
        }
    }

    private void handleWin(Packet p) {
        String winner = p.get("winner");
        int score = p.getInt("score", 1);
        boolean iWon = playerName.equals(winner);

        status.setText(iWon ? "🎉 You win! +" + score + " pts"
                            : "😞 " + winner + " wins (+" + score + " pts)");

        if (boardView != null) boardView.glowWinningSequence(parseSeq(p.get("sequence")));
        net.send(new Packet(Protocol.C_WIN_CONFIRM).put("matchId", currentMatchId));
        currentMatchId = null;
        stopCountdown();
        animating = false;
        normalDropBtn.setSelected(true);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Game Over");
        if (iWon) {
            alert.setHeaderText("🎉 Congratulations, " + playerName + "!");
            alert.setContentText("You won the match! +" + score + " point" + (score > 1 ? "s" : "") + ".");
        } else {
            alert.setHeaderText("🏆 " + winner + " wins!");
            alert.setContentText(winner + " won the match with +" + score + " point" + (score > 1 ? "s" : "") + ".\nBetter luck next time!");
        }
        alert.showAndWait();
    }

    private void sendChallenge(String targetId) {
        if (currentMatchId != null) {
            status.setText("You are already in a match.");
            return;
        }
        status.setText("Sending challenge request...");
        net.send(new Packet(Protocol.C_CHALLENGE).put("target", targetId));
    }

    private void handleChallengeRequest(Packet p) {
        String challengeId = p.get("challengeId");
        String fromName = p.get("fromName", "A player");
        String modeName = p.get("mode", gameMode.displayName());

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Challenge Request");
        alert.setHeaderText(fromName + " challenged you");
        alert.setContentText("Mode: " + modeName + "\nDo you want to accept and start the match?");
        ButtonType accept = new ButtonType("Accept", ButtonBar.ButtonData.OK_DONE);
        ButtonType decline = new ButtonType("Decline", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(accept, decline);

        boolean accepted = alert.showAndWait().orElse(decline) == accept;
        status.setText(accepted ? "Challenge accepted. Waiting for server..." : "Challenge declined.");
        net.send(new Packet(Protocol.C_CHALLENGE_RESPONSE)
                .put("challengeId", challengeId)
                .put("accepted", String.valueOf(accepted)));
    }

    private void handleChallengeStatus(Packet p) {
        String msg = p.get("msg", "Challenge updated.");
        String challengeStatus = p.get("status", "");
        status.setText(msg);
        if ("DECLINED".equals(challengeStatus) || "EXPIRED".equals(challengeStatus) || "CANCELLED".equals(challengeStatus)) {
            animating = false;
        }
    }

    private void startCountdown() {
        if (countdown != null) return;
        countdown = new AnimationTimer() {
            @Override public void handle(long now) {
                long remaining = Math.max(0, (deadlineMs - System.currentTimeMillis()) / 1000);
                timerLabel.setText("⏱ " + remaining + "s");
            }
        };
        countdown.start();
    }

    private void stopCountdown() {
        if (countdown != null) { countdown.stop(); countdown = null; }
        timerLabel.setText("");
    }

    private List<int[]> parseSeq(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String pair : s.split(";")) {
            String[] rc = pair.split(",");
            if (rc.length == 2) {
                try { out.add(new int[]{ Integer.parseInt(rc[0].trim()), Integer.parseInt(rc[1].trim()) });
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    private static int normalizeVariant(int value, int fallback) {
        return value == 5 ? 5 : fallback == 5 ? 5 : 4;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); } catch (Exception e) { return 0L; }
    }
}
