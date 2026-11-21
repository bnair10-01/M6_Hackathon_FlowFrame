package com.stevencastro.wornlink;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * TCP server that streams audio and camera frames to the phone over Wi-Fi.
 * It's announced on the local network as "_wornlink._tcp" so the phone can
 * find it, and also listens on a fixed port for typing the IP in by hand.
 *
 * Every message, both ways, is [type u8][length u32 little-endian][payload]:
 *   1 audio   glasses to phone, 16-bit little-endian PCM, 16 kHz mono
 *   2 video   glasses to phone, one JPEG
 *   3 command phone to glasses, UTF-8 JSON
 *
 * One phone at a time; a new connection replaces the old one. Audio always
 * goes first, and a frame is dropped if the previous one hasn't been sent.
 */
final class WifiLink {
    interface Listener {
        void onStatus(String status);

        void onStreaming(boolean streaming);

        void onCommand(String json);
    }

    static final int PORT = 47800;
    static final byte TYPE_AUDIO = 1;
    static final byte TYPE_VIDEO = 2;
    static final byte TYPE_COMMAND = 3;
    private static final String SERVICE_TYPE = "_wornlink._tcp";
    private static final int MAX_AUDIO_CHUNKS = 250; // ~5 s of backlog
    private static final String TAG = "WornLink";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> audio = new ArrayDeque<>();
    private byte[] frame;

    private NsdManager nsd;
    private WifiManager.MulticastLock multicastLock;
    private ServerSocket serverSocket;
    private Socket client;
    private volatile boolean running;
    private long bytesSent;

    WifiLink(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        running = true;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("WornLink");
            multicastLock.acquire();
        }
        new Thread(this::acceptLoop, "WornLink-accept").start();
    }

    void stop() {
        running = false;
        if (nsd != null) {
            try {
                nsd.unregisterService(registration);
            } catch (IllegalArgumentException notRegistered) {
                // Registration never completed.
            }
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        closeQuietly(serverSocket);
        dropClient();
    }

    boolean isStreaming() {
        synchronized (lock) {
            return client != null;
        }
    }

    long takeBytesSent() {
        synchronized (lock) {
            long sent = bytesSent;
            bytesSent = 0;
            return sent;
        }
    }

    void sendAudio(byte[] pcm) {
        synchronized (lock) {
            if (client == null) return;
            if (audio.size() >= MAX_AUDIO_CHUNKS) audio.poll();
            audio.add(pcm);
            lock.notifyAll();
        }
    }

    /** Queues a JPEG unless the previous frame is still sending. Returns whether it was queued. */
    boolean sendFrame(byte[] jpeg) {
        synchronized (lock) {
            if (client == null || frame != null) return false;
            frame = jpeg;
            lock.notifyAll();
            return true;
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            status("Couldn't open port " + PORT + ": " + e.getMessage());
            return;
        }
        register();
        status(waitingStatus());
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                dropClient();
                synchronized (lock) {
                    client = socket;
                    audio.clear();
                    frame = null;
                }
                status("Streaming to " + socket.getInetAddress().getHostAddress());
                main.post(() -> listener.onStreaming(true));
                new Thread(() -> writeLoop(socket), "WornLink-write").start();
                new Thread(() -> readLoop(socket), "WornLink-read").start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void register() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Worn Link");
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd != null) nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    private void writeLoop(Socket socket) {
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            while (running) {
                byte type;
                byte[] payload;
                synchronized (lock) {
                    while (running && client == socket && audio.isEmpty() && frame == null) lock.wait();
                    if (client != socket || !running) return;
                    if (!audio.isEmpty()) {
                        type = TYPE_AUDIO;
                        payload = audio.poll();
                    } else {
                        type = TYPE_VIDEO;
                        payload = frame;
                        frame = null;
                    }
                }
                writeMessage(out, type, payload);
                // Flush once the queue is drained so audio isn't held back.
                synchronized (lock) {
                    bytesSent += 5 + payload.length;
                    if (audio.isEmpty() && frame == null) out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            Log.i(TAG, "write ended: " + e.getMessage());
        } finally {
            disconnected(socket);
        }
    }

    private void readLoop(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] header = new byte[5];
            while (running) {
                in.readFully(header);
                int length = (header[1] & 0xff) | (header[2] & 0xff) << 8 | (header[3] & 0xff) << 16 | (header[4] & 0xff) << 24;
                if (length < 0 || length > 1 << 20) throw new IOException("bad length " + length);
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (header[0] == TYPE_COMMAND) {
                    String json = new String(payload, StandardCharsets.UTF_8);
                    main.post(() -> listener.onCommand(json));
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "read ended: " + e.getMessage());
        } finally {
            disconnected(socket);
        }
    }

    private static void writeMessage(OutputStream out, byte type, byte[] payload) throws IOException {
        int n = payload.length;
        out.write(new byte[]{type, (byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)});
        out.write(payload);
    }

    private void disconnected(Socket socket) {
        synchronized (lock) {
            if (client != socket) return;
            client = null;
            audio.clear();
            frame = null;
            lock.notifyAll();
        }
        closeQuietly(socket);
        main.post(() -> listener.onStreaming(false));
        status(waitingStatus());
    }

    private void dropClient() {
        Socket old;
        synchronized (lock) {
            old = client;
            client = null;
            lock.notifyAll();
        }
        closeQuietly(old);
    }

    private String waitingStatus() {
        String ip = localAddress();
        return ip == null
                ? "No Wi-Fi · join a network (or the iPhone's hotspot)"
                : "Waiting for the phone · " + ip + ":" + PORT;
    }

    /** The glasses' Wi-Fi IPv4 address, for typing into the phone by hand. */
    static String localAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address) return address.getHostAddress();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "no address", e);
        }
        return null;
    }

    private void status(String text) {
        Log.i(TAG, text);
        main.post(() -> listener.onStatus(text));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private final NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            Log.i(TAG, "announced as " + info.getServiceName());
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
            Log.w(TAG, "announce failed " + errorCode + "; the phone can still connect by IP");
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
        }
    };
}
