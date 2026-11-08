package com.deatrg.fit900nb;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/** Minimal blocking SPP client. All callbacks arrive on a background thread. */
final class SppClient {
    interface Listener {
        void onState(String state, String message);

        void onFrame(Protocol.Frame frame);

        void onLog(String message);
    }

    private static final UUID SPP_UUID =
            UUID.fromString("EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF");

    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private final Listener listener;

    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private Thread connectThread;
    private Thread readThread;

    SppClient(BluetoothAdapter adapter, BluetoothDevice device, Listener listener) {
        this.adapter = adapter;
        this.device = device;
        this.listener = listener;
    }

    void connect() {
        if (running) return;
        running = true;
        connectThread = new Thread(this::doConnect, "fit900nb-connect");
        connectThread.start();
    }

    private void doConnect() {
        listener.onState("connecting", "正在建立 SPP 连接…");
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }

        BluetoothSocket s = null;
        IOException last = null;

        // Primary path: the service record Edifier advertises.
        try {
            s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
        } catch (IOException | SecurityException e) {
            last = e instanceof IOException ? (IOException) e : new IOException(e);
            closeQuietly(s);
            s = null;
        }

        // Fallback 1: insecure service record.
        if (s == null) {
            try {
                s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
            } catch (IOException | SecurityException e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
                closeQuietly(s);
                s = null;
            }
        }

        // Fallback 2: direct RFCOMM channel 1.
        if (s == null) {
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                s = (BluetoothSocket) m.invoke(device, 1);
                s.connect();
            } catch (Exception e) {
                if (e instanceof IOException) last = (IOException) e;
                else last = new IOException(e);
                closeQuietly(s);
                s = null;
            }
        }

        if (s == null) {
            running = false;
            listener.onState("disconnected", "连接失败：" + (last == null ? "未知错误" : safeMessage(last)));
            return;
        }
        if (!running) {
            closeQuietly(s);
            return;
        }

        try {
            socket = s;
            input = s.getInputStream();
            output = s.getOutputStream();
        } catch (IOException e) {
            closeQuietly(s);
            running = false;
            listener.onState("disconnected", "获取数据流失败：" + safeMessage(e));
            return;
        }

        listener.onState("connected", "SPP 已连接");
        readThread = new Thread(this::readLoop, "fit900nb-read");
        readThread.start();
    }

    private void readLoop() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        try {
            while (running) {
                InputStream in = input;
                if (in == null) break;
                int n = in.read(chunk);
                if (n < 0) break;
                if (n == 0) continue;
                buffer.write(chunk, 0, n);
                for (Protocol.Frame frame : Protocol.drain(buffer)) {
                    listener.onFrame(frame);
                }
            }
        } catch (IOException e) {
            if (running) listener.onLog("读取结束：" + safeMessage(e));
        } catch (RuntimeException e) {
            if (running) listener.onLog("读取异常：" + safeMessage(e));
        } finally {
            if (running) {
                running = false;
                closeQuietly(socket);
                listener.onState("disconnected", "连接已断开");
            }
        }
    }

    void send(byte[] data) {
        OutputStream out = output;
        if (!running || out == null) {
            listener.onLog("未连接，无法发送");
            return;
        }
        try {
            synchronized (this) {
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            listener.onLog("发送失败：" + safeMessage(e));
            close();
            listener.onState("disconnected", "连接已断开");
        }
    }

    void close() {
        if (!running && socket == null) return;
        running = false;
        closeQuietly(socket);
        socket = null;
        input = null;
        output = null;
    }

    boolean isRunning() {
        return running;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
