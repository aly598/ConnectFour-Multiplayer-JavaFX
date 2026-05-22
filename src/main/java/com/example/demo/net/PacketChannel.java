package com.example.demo.net;

import java.io.*;
import java.net.Socket;

/** Newline-delimited packet channel over a TCP socket.
 *  Thread-safety: one writer thread should call send(); one reader uses receive(). */
public final class PacketChannel implements Closeable {
    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    public PacketChannel(Socket socket) throws IOException {
        this.socket = socket;
        this.in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
    }

    public synchronized void send(Packet p) throws IOException {
        out.write(p.encode()); out.write('\n'); out.flush();
    }

    /** Blocks until a packet is read, or returns null on EOF. */
    public Packet receive() throws IOException {
        String line = in.readLine();
        return line == null ? null : Packet.decode(line);
    }

    @Override public void close() throws IOException { socket.close(); }
    public Socket socket() { return socket; }
}
