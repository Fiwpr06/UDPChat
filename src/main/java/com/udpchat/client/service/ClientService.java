package com.udpchat.client.service;

import com.udpchat.client.network.ClientListener;
import com.udpchat.client.network.ClientNetwork;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.protocol.UDPConstants;

import java.io.File;
import java.net.InetAddress;
import java.util.function.Consumer;

// Tầng logic xử lý nghiệp vụ, giao tiếp giữa giao diện và mạng.
public class ClientService {
    private ClientNetwork network;
    private ClientListener listener;
    private Thread listenerThread;
    private String currentUsername;
    @FunctionalInterface
    public interface StructuredMessageConsumer {
        void accept(String sender, String time, String content);
    }

    private Consumer<String> onIncomingMessage;
    private StructuredMessageConsumer onIncomingStructuredMessage;
    private Consumer<String> onLogMessage;
    
    private String serverHost;
    private int serverPort;

    public ClientService(String serverHost, int serverPort) throws Exception {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.network = new ClientNetwork(InetAddress.getByName(serverHost), serverPort);
    }

    public void setOnIncomingMessage(Consumer<String> onIncomingMessage) {
        this.onIncomingMessage = onIncomingMessage;
    }

    public void setOnIncomingStructuredMessage(StructuredMessageConsumer consumer) {
        this.onIncomingStructuredMessage = consumer;
    }

    public void setOnLogMessage(Consumer<String> onLogMessage) {
        this.onLogMessage = onLogMessage;
    }
    
    private void log(String msg) {
        if (onLogMessage != null) {
            onLogMessage.accept(msg);
        }
    }

    private void handlePushMessage(String rawMessage) {
        Command cmd = ProtocolHelper.parseCommand(rawMessage);
        String[] params = ProtocolHelper.parseParams(rawMessage);
        
        if (cmd == Command.INCOMING_MSG) {
            if (params.length >= 3) {
                String sender = params[0];
                String time = params[1];
                String content = params[2];
                String display = "[" + time + "] " + sender + ": " + content;
                if (onIncomingMessage != null) {
                    onIncomingMessage.accept(display);
                }
                if (onIncomingStructuredMessage != null) {
                    onIncomingStructuredMessage.accept(sender, time, content);
                }
            }
        }
    }

    public String register(String username, String password) {
        try {
            String request = ProtocolHelper.buildRequest(Command.REGISTER, username, password);
            String response = network.sendRequest(request);
            if (response.startsWith("RESPONSE|OK")) {
                log("Đăng ký thành công tài khoản '" + username + "'");
            } else {
                log("Đăng ký không thành công: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi đăng ký: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String login(String username, String password) {
        try {
            listener = new ClientListener(this::handlePushMessage);
            int listenerPort = listener.getPort();
            
            String request = ProtocolHelper.buildRequest(Command.LOGIN, username, password, String.valueOf(listenerPort));
            String response = network.sendRequest(request);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                currentUsername = username;
                listenerThread = new Thread(listener);
                listenerThread.setDaemon(true);
                listenerThread.start();
                log("Đăng nhập thành công! Chào mừng '" + username + "' (Cổng nhận: " + listenerPort + ")");
            } else {
                listener.stop();
                log("Đăng nhập thất bại: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi kết nối khi đăng nhập: " + e.getMessage());
            if (listener != null) listener.stop();
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String sendMessage(String content) {
        if (!isLoggedIn()) return "ERROR|Chưa đăng nhập";
        try {
            String request = ProtocolHelper.buildRequest(Command.MESSAGE, content);
            String response = network.sendRequest(request);
            if (response.startsWith("RESPONSE|OK")) {
                log("Đã gửi tin nhắn tới Server");
            } else {
                log("Gửi tin nhắn thất bại: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi gửi tin nhắn: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String uploadFile(File file) {
        return uploadFile(file, null);
    }

    public String uploadFile(File file, Consumer<Double> onProgress) {
        if (!isLoggedIn()) return "ERROR|Chưa đăng nhập";
        try {
            int totalChunks = (int) Math.ceil((double) file.length() / UDPConstants.CHUNK_DATA_SIZE);
            if (totalChunks == 0) totalChunks = 1;
            String request = ProtocolHelper.buildRequest(Command.UPLOAD, file.getName(), String.valueOf(totalChunks));
            log("Yêu cầu tải lên file '" + file.getName() + "' (" + totalChunks + " phần, " + file.length() + " bytes)...");
            String response = network.sendRequest(request);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                network.sendFileChunks(file, network.getServerAddress(), network.getServerPort(), onProgress);
                log("Tải lên file '" + file.getName() + "' thành công!");
                return "OK|File uploaded";
            } else {
                log("Server từ chối tải lên: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi tải lên file: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String downloadFile(String filename, File saveLocation) {
        return downloadFile(filename, saveLocation, null);
    }

    public String downloadFile(String filename, File saveLocation, Consumer<Double> onProgress) {
        if (!isLoggedIn()) return "ERROR|Chưa đăng nhập";
        try {
            String request = ProtocolHelper.buildRequest(Command.DOWNLOAD, filename);
            log("Yêu cầu tải xuống file '" + filename + "'...");
            String response = network.sendRequest(request);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                int totalChunks = Integer.parseInt(parts[2]);
                
                File outputFile = new File(saveLocation, filename);
                log("Bắt đầu nhận " + totalChunks + " phần dữ liệu...");
                network.receiveFileChunks(totalChunks, outputFile, onProgress);
                log("Tải file '" + filename + "' thành công! Lưu tại: " + outputFile.getAbsolutePath());
                return "OK|File downloaded";
            } else {
                log("Tải xuống không thành công: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi tải xuống file: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String logout() {
        if (!isLoggedIn()) return "OK|Đã đăng xuất trước đó";
        try {
            String request = ProtocolHelper.buildRequest(Command.LOGOUT);
            String response = network.sendRequest(request);
            log("Đã đăng xuất khỏi Server thành công.");
            return response;
        } catch (Exception e) {
            log("Lỗi đăng xuất: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        } finally {
            currentUsername = null;
            if (listener != null) {
                listener.stop();
            }
        }
    }

    public boolean isLoggedIn() {
        return currentUsername != null;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void close() {
        if (listener != null) {
            listener.stop();
        }
        if (network != null) {
            network.close();
        }
    }
}
