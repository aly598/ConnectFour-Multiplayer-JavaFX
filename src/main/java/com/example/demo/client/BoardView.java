package com.example.demo.client;

import com.example.demo.model.Board;

import javafx.animation.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.List;

/** JavaFX view of the Connect Four board. */
public final class BoardView extends StackPane {
    private static final double CELL = 64;
    private final int rows, cols;
    private final GridPane grid = new GridPane();
    private final Pane overlay = new Pane();
    private final Circle[][] cells;

    private static final Color HOLE_BG   = Color.web("#0d1f50");
    private static final Color RED_COLOR = Color.web("#e63946");
    private static final Color YEL_COLOR = Color.web("#ffd166");

    public BoardView(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.cells = new Circle[rows][cols];

        setStyle("-fx-background-color: #1f3b8b; -fx-background-radius: 12;");
        setPadding(new javafx.geometry.Insets(10));

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Circle hole = new Circle(CELL / 2 - 4, HOLE_BG);
                cells[r][c] = hole;
                StackPane cell = new StackPane(hole);
                cell.setMinSize(CELL, CELL);
                grid.add(cell, c, r);
            }
        }

        overlay.setMouseTransparent(true);
        overlay.setPrefSize(cols * CELL, rows * CELL);
        getChildren().addAll(grid, overlay);
    }

    /** Render a static board from an encoded string. Invalid packets are ignored safely. */
    public void render(String encoded) {
        if (encoded == null || encoded.length() < rows * cols) return;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = encoded.charAt(r * cols + c) - '0';
                cells[r][c].setFill(switch (v) {
                    case Board.RED -> RED_COLOR;
                    case Board.YEL -> YEL_COLOR;
                    default        -> HOLE_BG;
                });
                cells[r][c].setEffect(null);
                cells[r][c].setOpacity(1.0);
                cells[r][c].setScaleX(1.0);
                cells[r][c].setScaleY(1.0);
            }
        }
    }

    /** Animates a disc falling from the top of the board into (row, col). */
    public void animateDrop(int row, int col, int player, Runnable onDone) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            if (onDone != null) onDone.run();
            return;
        }
        Color color = player == Board.RED ? RED_COLOR : YEL_COLOR;
        double radius = CELL / 2 - 4;

        Circle disc = new Circle(radius, color);
        double centreX = col * CELL + CELL / 2;
        double startY  = -radius;
        double endY    = row * CELL + CELL / 2;

        disc.setLayoutX(centreX);
        disc.setLayoutY(startY);
        overlay.getChildren().add(disc);

        int rowsFallen = row + 1;
        double fallMs  = Math.min(150 + rowsFallen * 80, 600);

        Timeline fall = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(disc.layoutYProperty(), startY, Interpolator.EASE_IN)),
                new KeyFrame(Duration.millis(fallMs), new KeyValue(disc.layoutYProperty(), endY, Interpolator.EASE_IN))
        );
        fall.setOnFinished(ev -> {
            overlay.getChildren().remove(disc);
            cells[row][col].setFill(color);

            ScaleTransition squish = new ScaleTransition(Duration.millis(80), cells[row][col]);
            squish.setFromX(1.0); squish.setFromY(1.0);
            squish.setToX(1.15);  squish.setToY(0.85);
            squish.setAutoReverse(true);
            squish.setCycleCount(2);
            squish.setOnFinished(e2 -> { if (onDone != null) onDone.run(); });
            squish.play();
        });
        fall.play();
    }

    /** Highlights the winning sequence with glow + pulsating fade. */
    public void glowWinningSequence(List<int[]> seq) {
        if (seq == null) return;
        for (int[] rc : seq) {
            if (rc == null || rc.length < 2 || rc[0] < 0 || rc[0] >= rows || rc[1] < 0 || rc[1] >= cols) continue;
            Circle c = cells[rc[0]][rc[1]];
            Color fill = c.getFill() instanceof Color color ? color : Color.WHITE;

            Glow glow = new Glow(1.0);
            DropShadow ds = new DropShadow(20, fill.brighter());
            ds.setInput(glow);
            c.setEffect(ds);

            ScaleTransition pop = new ScaleTransition(Duration.millis(200), c);
            pop.setFromX(1.0); pop.setFromY(1.0);
            pop.setToX(1.25);  pop.setToY(1.25);
            pop.setAutoReverse(true);
            pop.setCycleCount(2);
            pop.play();

            FadeTransition pulse = new FadeTransition(Duration.millis(550), c);
            pulse.setFromValue(1.0);
            pulse.setToValue(0.45);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.setAutoReverse(true);
            pulse.play();
        }
    }

    public int colAtX(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return -1;
        int c = (int) (x / CELL);
        return c < 0 || c >= cols ? -1 : c;
    }

    public double boardWidth() { return cols * CELL; }
}
