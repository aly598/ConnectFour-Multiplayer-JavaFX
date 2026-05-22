package com.example.demo.client;

import com.example.demo.net.Packet;
import com.example.demo.net.PacketChannel;

import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;

/** Client-side socket wrapper. Spawns a network listener thread that
 *  reads packets from the server and dispatches to a handler running
 *  on the JavaFX Application Thread (via Platform.runLater in the GUI). */
public final class NetworkClient implements AutoCloseable {
    private final PacketChannel channel;
    private final Thread listener;
    private volatile boolean running = true;

    public NetworkClient(String host, int port, Consumer<Packet> onPacket) throws IOException {
        this.channel = new PacketChannel(new Socket(host, port));
        this.listener = new Thread(() -> {
            try {
                Packet p;
                while (running && (p = channel.receive()) != null) {
                    onPacket.accept(p);
                }
            } catch (IOException e) {
                if (running) System.err.println("[Net] " + e.getMessage());
            }
        }, "network-listener");
        listener.setDaemon(true);
        listener.start();
    }

    public void send(Packet p) {
        try { channel.send(p); } catch (IOException e) { System.err.println("[Net] send failed"); }
    }

    @Override public void close() {
        running = false;
        try { channel.close(); } catch (IOException ignored) {}
    }
}
