package com.udpchat.client.network;

import com.udpchat.shared.util.UDPUtil;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.function.Consumer;

// Luồng daemon lắng nghe các tin nhắn được server đẩy xuống (push) như INCOMING_MSG.
public class ClientListener implements Runnable {
    private DatagramSocket listenerSocket;
    private Consumer<String> onMessageReceived;
    private volatile boolean running;

    public ClientListener(Consumer<String> onMessageReceived) throws SocketException {
        // Tạo socket với port ngẫu nhiên
        this.listenerSocket = new DatagramSocket();
        this.onMessageReceived = onMessageReceived;
        this.running = true;
    }

    @Override
    public void run() {
        while (running) {
            try {
                String[] result = UDPUtil.receiveString(listenerSocket);
                String rawMessage = result[0];
                if (onMessageReceived != null) {
                    onMessageReceived.accept(rawMessage);
                }
            } catch (Exception e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    public int getPort() {
        return listenerSocket.getLocalPort();
    }

    public void stop() {
        this.running = false;
        if (listenerSocket != null && !listenerSocket.isClosed()) {
            listenerSocket.close();
        }
    }
}
