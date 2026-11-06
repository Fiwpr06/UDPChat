package com.udpchat.server.smtp;

import com.udpchat.database.EmailDao;
import com.udpchat.server.service.GmailForwarder;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Máy chủ SMTP lắng nghe trên cổng TCP (mặc định 2525)
 */
public class SmtpServer {
    private final int port;
    private final EmailDao emailDao;
    private final GmailForwarder gmailForwarder;
    private final Consumer<String> logger;

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    public SmtpServer(int port, EmailDao emailDao, GmailForwarder gmailForwarder, Consumer<String> logger) {
        this.port = port;
        this.emailDao = emailDao;
        this.gmailForwarder = gmailForwarder;
        this.logger = logger != null ? logger : System.out::println;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        serverSocket = new ServerSocket(port);
        running = true;
        threadPool = Executors.newCachedThreadPool();

        logger.accept("[SMTP Server] Khởi chạy máy chủ SMTP thành công trên cổng TCP " + port);

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.submit(new SmtpSessionHandler(clientSocket, emailDao, gmailForwarder, logger));
                } catch (IOException e) {
                    if (!running) break;
                    logger.accept("[SMTP Server] Lỗi chấp nhận kết nối: " + e.getMessage());
                }
            }
        }, "SMTP-Accept-Loop");
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
        logger.accept("[SMTP Server] Đã dừng máy chủ SMTP cổng " + port);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
