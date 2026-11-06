package com.udpchat.server.pop3;

import com.udpchat.database.EmailDao;
import com.udpchat.database.UserDao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Máy chủ POP3 lắng nghe trên cổng TCP (mặc định 1110)
 */
public class Pop3Server {
    private final int port;
    private final UserDao userDao;
    private final EmailDao emailDao;
    private final Consumer<String> logger;

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    public Pop3Server(int port, UserDao userDao, EmailDao emailDao, Consumer<String> logger) {
        this.port = port;
        this.userDao = userDao;
        this.emailDao = emailDao;
        this.logger = logger != null ? logger : System.out::println;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        serverSocket = new ServerSocket(port);
        running = true;
        threadPool = Executors.newCachedThreadPool();

        logger.accept("[POP3 Server] Khởi chạy máy chủ POP3 thành công trên cổng TCP " + port);

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.submit(new Pop3SessionHandler(clientSocket, userDao, emailDao, logger));
                } catch (IOException e) {
                    if (!running) break;
                    logger.accept("[POP3 Server] Lỗi chấp nhận kết nối: " + e.getMessage());
                }
            }
        }, "POP3-Accept-Loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        logger.accept("[POP3 Server] Đã dừng máy chủ POP3 cổng " + port);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
