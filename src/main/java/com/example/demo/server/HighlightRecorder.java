package com.example.demo.server;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Highlight recorder thread. Writes one JSON clip per completed match. */
public final class HighlightRecorder implements Runnable {
    public static class Frame {
        public final String matchId;
        public final String boardSnapshot;
        public final int col, row, player;
        public final long t;

        public Frame(String matchId, String snap, int col, int row, int player) {
            this.matchId = matchId == null ? "" : matchId;
            this.boardSnapshot = snap == null ? "" : snap;
            this.col = col;
            this.row = row;
            this.player = player;
            this.t = System.currentTimeMillis();
        }
    }

    public static final class Flush extends Frame {
        public final String winner;
        public final List<int[]> sequence;
        public Flush(String matchId, String winner, List<int[]> sequence) {
            super(matchId, "", -1, -1, 0);
            this.winner = winner == null ? "" : winner;
            this.sequence = sequence == null ? List.of() : sequence;
        }
    }

    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final Map<String, List<Frame>> buffers = new HashMap<>();
    private final Path outDir;
    private volatile boolean running = true;

    public HighlightRecorder(Path outDir) throws IOException {
        this.outDir = outDir;
        Files.createDirectories(outDir);
    }

    public void enqueue(Frame f) { if (f != null) queue.offer(f); }
    public void stop() { running = false; queue.offer(new Frame("", "", -1, -1, 0)); }

    @Override public void run() {
        while (running) {
            try {
                Frame f = queue.take();
                if (!running) break;
                if (f instanceof Flush flush) {
                    writeClip(flush);
                    buffers.remove(flush.matchId);
                } else if (!f.matchId.isEmpty()) {
                    buffers.computeIfAbsent(f.matchId, k -> new ArrayList<>()).add(f);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                System.err.println("[Recorder] write failed: " + e.getMessage());
            }
        }
    }

    private void writeClip(Flush flush) throws IOException {
        List<Frame> frames = buffers.getOrDefault(flush.matchId, List.of());
        Path file = outDir.resolve(safeFilePart(flush.matchId) + "-" + Instant.now().toEpochMilli() + ".json");
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("{\n");
            w.write("  \"matchId\": \"" + json(flush.matchId) + "\",\n");
            w.write("  \"winner\": \"" + json(flush.winner) + "\",\n");
            w.write("  \"sequence\": [");
            for (int i = 0; i < flush.sequence.size(); i++) {
                int[] rc = flush.sequence.get(i);
                if (rc == null || rc.length < 2) continue;
                if (i > 0) w.write(",");
                w.write("[" + rc[0] + "," + rc[1] + "]");
            }
            w.write("],\n  \"frames\": [\n");
            for (int i = 0; i < frames.size(); i++) {
                Frame fr = frames.get(i);
                if (i > 0) w.write(",\n");
                w.write("    {\"t\":" + fr.t + ",\"player\":" + fr.player +
                        ",\"col\":" + fr.col + ",\"row\":" + fr.row +
                        ",\"board\":\"" + json(fr.boardSnapshot) + "\"}");
            }
            w.write("\n  ]\n}\n");
        }
    }

    private static String json(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }

    private static String safeFilePart(String s) {
        return (s == null || s.isBlank()) ? "match" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
