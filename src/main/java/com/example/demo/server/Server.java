package com.example.demo.server;

import com.example.demo.db.Database;
import com.example.demo.db.MatchDao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EXTENDED – Top-level Connect Four server.
 *
 * Change: exposes matchDao() so ClientHandler can call
 * matchDao().playerDao().ensurePlayer() on connect.
 */
public final class Server {
    private final int port;
    private final Lobby lobby;
    private final MatchDao matchDao;
    private final com.example.demo.db.TournamentDao tournDao;
    private final HighlightRecorder recorder;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public Server(int port, int variant, int turnSeconds,
                  String dbHost, int dbPort, String dbName,
                  String dbUser, String dbPass,
                  Path clipsDir) throws IOException {
        this.port = port;
        Database db = new Database(dbHost, dbPort, dbName, dbUser, dbPass);
        this.matchDao = new MatchDao(db);
        this.tournDao = new com.example.demo.db.TournamentDao(db);
        this.recorder = new HighlightRecorder(clipsDir);
        Thread recThread = new Thread(recorder, "highlight-recorder");
        recThread.setDaemon(true);
        recThread.start();
        this.lobby = new Lobby(recorder, matchDao, tournDao, variant, turnSeconds);
    }

    public Lobby lobby()     { return lobby;    }
    public MatchDao matchDao() { return matchDao; }

    /** Network listener – runs the accept loop on the calling thread. */
    public void run() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("[Server] listening on " + port);
            while (running) {
                Socket s = ss.accept();
                ClientHandler h = new ClientHandler(s, this);
                clientPool.submit(h);
            }
        } finally {
            recorder.stop();
        }
    }

    public void stop() { running = false; }
}
