package com.example.demo.client;

import com.example.demo.model.GameMode;
import com.example.demo.net.Protocol;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JavaFX client entry point. Prompts for a display name and game mode, then
 * opens the lobby.
 */
public final class ClientApp1 extends Application {

    private record StartOptions(GameMode mode, int variant, boolean powersEnabled, int turnSeconds) {
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        String host = System.getProperty("c4.host", "localhost");
        int port = Integer.parseInt(System.getProperty("c4.port",
                String.valueOf(Protocol.DEFAULT_PORT)));

        TextInputDialog nameDialog = new TextInputDialog("player" + (int) (Math.random() * 1000));
        nameDialog.setHeaderText("Enter your display name");
        nameDialog.setTitle("Connect Four");
        String name = nameDialog.showAndWait().orElse(null);
        if (name == null) {
            Platform.exit();
            return;
        }
        name = name.trim().isEmpty() ? "anon" : name.trim();

        StartOptions options = chooseMode();
        if (options == null) {
            Platform.exit();
            return;
        }

        new MainWindow(stage, name, host, port,
                options.mode(), options.variant(), options.powersEnabled(), options.turnSeconds());
    }

    private StartOptions chooseMode() {
        Map<String, StartOptions> options = new LinkedHashMap<>();
        options.put("Normal mode — regular Connect Four", new StartOptions(GameMode.NORMAL, 4, false, 0));
        options.put("Tournament mode — 4-player bracket with final + 3rd-place match",
                new StartOptions(GameMode.TOURNAMENT, 4, false, 20));
        options.put("Extras mode — 5-in-a-row, power discs, and timed moves",
                new StartOptions(GameMode.EXTRAS, 5, true, 20));

        ChoiceDialog<String> dialog = new ChoiceDialog<>(options.keySet().iterator().next(), options.keySet());
        dialog.setTitle("Connect Four");
        dialog.setHeaderText("Choose playing mode");
        dialog.setContentText("Mode:");
        String selected = dialog.showAndWait().orElse(null);
        return selected == null ? null : options.get(selected);
    }
}
