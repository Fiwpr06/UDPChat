package com.udpchat.server.network;

import com.udpchat.shared.util.UDPUtil;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.function.Consumer;

public class ServerNetwork {
    private DatagramSocket socket;
    private volatile boolean running;
    private final RequestHandler handler;
    private final Consumer<String> logger;

    public ServerNetwork(RequestHandler handler, Consumer<String> logger) {
        this.handler = handler;
        this.logger = logger;
    }

    public void start(int port) {
        try {
            socket = new DatagramSocket(port);
            running = true;
            logger.accept("UDP Server đã khởi động và lắng nghe trên cổng " + port);

            while (running) {
                try {
                    String[] data = UDPUtil.receiveString(socket);
                    if (data != null) {
                        String message = data[0];
                        java.net.InetAddress senderIP = java.net.InetAddress.getByName(data[1]);
                        int senderPort = Integer.parseInt(data[2]);
                        
                        handler.handle(message, senderIP, senderPort, socket);
                    }
                } catch (SocketException e) {
                    if (!running) break; // socket closed purposely
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (SocketException e) {
            logger.accept("Lỗi: Không thể liên kết (bind) tới cổng " + port);
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        logger.accept("UDP Server đã dừng hoạt động.");
    }

    public boolean isRunning() {
        return running;
    }
}
