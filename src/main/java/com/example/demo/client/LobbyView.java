package com.example.demo.client;

import javafx.animation.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * EXTENDED – Lobby panel with animated status, countdown, and global scoreboard.
 */
public final class LobbyView extends VBox {

    public static final class PlayerEntry {
        public final String name, id;
        public PlayerEntry(String name, String id) { this.name = name; this.id = id; }
        @Override public String toString() { return name; }
    }

    private final Label modeLabel = new Label("Mode: Normal Mode");
    private final Label kingLabel = new Label("King: (none)");
    private final ListView<PlayerEntry> list = new ListView<>();
    private final Button challengeBtn = new Button("⚔  Send challenge request");
    private final ObservableList<PlayerEntry> data = FXCollections.observableArrayList();

    private final Label lobbyStatusLabel = new Label();
    private final Label countdownLabel   = new Label();

    private final ListView<String> scoreboardView = new ListView<>();
    private final ObservableList<String> scoreData = FXCollections.observableArrayList();

    private final ListView<String> tourneyScoreView = new ListView<>();
    private final ObservableList<String> tourneyScoreData = FXCollections.observableArrayList();
    private final Label tourneyTitle = new Label("\uD83C\uDFC5  Tournament Score");

    private Timeline typingTimeline;
    private FadeTransition statusFade;

    public LobbyView(Consumer<String> onChallenge) {
        setSpacing(8);
        setPadding(new Insets(12));
        setStyle("-fx-background-color: #f5f5f7;");
        setPrefWidth(260);

        Label title = new Label("Lobby");
        title.setFont(Font.font("Georgia", FontWeight.BOLD, 22));

        modeLabel.setStyle("-fx-text-fill: #333; -fx-font-weight: bold;");
        kingLabel.setStyle("-fx-text-fill: #b8860b; -fx-font-weight: bold;");

        list.setItems(data);
        list.setPrefHeight(140);

        challengeBtn.setMaxWidth(Double.MAX_VALUE);
        challengeBtn.setOnAction(e -> {
            PlayerEntry sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) onChallenge.accept(sel.id);
        });

        lobbyStatusLabel.setWrapText(true);
        lobbyStatusLabel.setMaxWidth(240);
        lobbyStatusLabel.setAlignment(Pos.CENTER);
        lobbyStatusLabel.setFont(Font.font("Georgia", FontWeight.BOLD, 13));
        lobbyStatusLabel.setStyle("-fx-text-fill: #333;");

        countdownLabel.setFont(Font.font("Georgia", FontWeight.BOLD, 52));
        countdownLabel.setTextFill(Color.web("#e63946"));
        countdownLabel.setAlignment(Pos.CENTER);
        countdownLabel.setMaxWidth(Double.MAX_VALUE);
        countdownLabel.setVisible(false);

        Label sbTitle = new Label("\uD83C\uDFC6  Global Scoreboard");
        sbTitle.setFont(Font.font("Georgia", FontWeight.BOLD, 13));
        scoreboardView.setItems(scoreData);
        scoreboardView.setPrefHeight(130);
        scoreboardView.setStyle("-fx-font-family: monospace;");

        tourneyTitle.setFont(Font.font("Georgia", FontWeight.BOLD, 13));
        tourneyScoreView.setItems(tourneyScoreData);
        tourneyScoreView.setPrefHeight(80);
        tourneyScoreView.setStyle("-fx-font-family: monospace;");
        
        // Hide tournament score by default until tournament starts
        tourneyTitle.setVisible(false);
        tourneyTitle.setManaged(false);
        tourneyScoreView.setVisible(false);
        tourneyScoreView.setManaged(false);

        Separator sep1 = new Separator();
        Separator sep2 = new Separator();

        getChildren().addAll(title, modeLabel, kingLabel, list, challengeBtn, sep1,
                lobbyStatusLabel, countdownLabel, sep2, sbTitle, scoreboardView, tourneyTitle, tourneyScoreView);
    }

    public void setMode(String modeName) {
        modeLabel.setText("Mode: " + (modeName == null || modeName.isBlank() ? "Normal Mode" : modeName));
    }

    public void setChallengeEnabled(boolean enabled) {
        challengeBtn.setDisable(!enabled);
        challengeBtn.setText(enabled ? "⚔  Send challenge request" : "🏆 Tournament auto-starts at 4 players");
    }

    public void update(String playersField, String king, int count) {
        data.clear();
        if (playersField != null && !playersField.isEmpty()) {
            for (String entry : playersField.split(",")) {
                int colon = entry.indexOf(':');
                if (colon > 0)
                    data.add(new PlayerEntry(entry.substring(0, colon), entry.substring(colon + 1)));
            }
        }
        kingLabel.setText("King: " + (king == null || king.isEmpty() ? "(none)" : "\uD83D\uDC51 " + king));
    }

    public void update(String playersField, String king) {
        int count = 0;
        if (playersField != null && !playersField.isEmpty()) count = playersField.split(",").length;
        update(playersField, king, count);
    }

    public void applyStatus(String msg, int countdown) {
        if (countdown > 0) { showCountdown(countdown); }
        else { countdownLabel.setVisible(false); typeMessage(msg); }
    }

    public void updateScoreboard(String entries) {
        scoreData.clear();
        if (entries == null || entries.isEmpty()) return;
        for (String entry : entries.split("\\|")) {
            String[] parts = entry.split(":");
            if (parts.length >= 3) {
                String crown = "1".equals(parts[2].trim()) ? " \uD83D\uDC51" : "";
                scoreData.add(String.format("#%-3s %-15s %s pts%s", parts[2], parts[0], parts[1], crown));
            }
        }
    }

    public void updateTournamentScoreboard(String entries) {
        tourneyScoreData.clear();
        if (entries == null || entries.isEmpty()) {
            tourneyTitle.setVisible(false); tourneyTitle.setManaged(false);
            tourneyScoreView.setVisible(false); tourneyScoreView.setManaged(false);
            return;
        }
        tourneyTitle.setVisible(true); tourneyTitle.setManaged(true);
        tourneyScoreView.setVisible(true); tourneyScoreView.setManaged(true);
        
        for (String entry : entries.split("\\|")) {
            String[] parts = entry.split(":");
            if (parts.length >= 2) {
                tourneyScoreData.add(String.format("%-15s %s pts", parts[0], parts[1]));
            }
        }
    }

    private void typeMessage(String fullText) {
        if (typingTimeline != null) typingTimeline.stop();
        if (statusFade != null)     statusFade.stop();
        lobbyStatusLabel.setOpacity(1.0);
        lobbyStatusLabel.setText("");
        if (fullText == null || fullText.isEmpty()) return;
        typingTimeline = new Timeline();
        double delayPerChar = 35;
        for (int i = 1; i <= fullText.length(); i++) {
            final String partial = fullText.substring(0, i);
            KeyFrame kf = new KeyFrame(Duration.millis(i * delayPerChar), e -> lobbyStatusLabel.setText(partial));
            typingTimeline.getKeyFrames().add(kf);
        }
        typingTimeline.play();
    }

    private void showCountdown(int number) {
        if (typingTimeline != null) typingTimeline.stop();
        lobbyStatusLabel.setText("Tournament starting soon\u2026");
        countdownLabel.setText(String.valueOf(number));
        countdownLabel.setOpacity(0);
        countdownLabel.setScaleX(0.5); countdownLabel.setScaleY(0.5);
        countdownLabel.setVisible(true);
        FadeTransition ft = new FadeTransition(Duration.millis(300), countdownLabel);
        ft.setFromValue(0); ft.setToValue(1);
        ScaleTransition st = new ScaleTransition(Duration.millis(300), countdownLabel);
        st.setFromX(0.5); st.setFromY(0.5); st.setToX(1.0); st.setToY(1.0);
        ParallelTransition pt = new ParallelTransition(ft, st);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), countdownLabel);
        fadeOut.setDelay(Duration.millis(700));
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        new SequentialTransition(pt, fadeOut).play();
    }
}
