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
    private Consumer<String> onIncomingMessage;
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
            }
        }
    }

    public String register(String username, String password) {
        try {
            String request = ProtocolHelper.buildRequest(Command.REGISTER, username, password);
            String response = network.sendRequest(request);
            log("Register response: " + response);
            return response;
        } catch (Exception e) {
            log("Register error: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String login(String username, String password) {
        try {
            listener = new ClientListener(this::handlePushMessage);
            int listenerPort = listener.getPort();
            
            String request = ProtocolHelper.buildRequest(Command.LOGIN, username, password, String.valueOf(listenerPort));
            String response = network.sendRequest(request);
            log("Login response: " + response);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                currentUsername = username;
                listenerThread = new Thread(listener);
                listenerThread.setDaemon(true);
                listenerThread.start();
            } else {
                listener.stop();
            }
            return response;
        } catch (Exception e) {
            log("Login error: " + e.getMessage());
            if (listener != null) listener.stop();
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String sendMessage(String content) {
        if (!isLoggedIn()) return "ERROR|Not logged in";
        try {
            String request = ProtocolHelper.buildRequest(Command.MESSAGE, content);
            String response = network.sendRequest(request);
            log("Send Message response: " + response);
            return response;
        } catch (Exception e) {
            log("SendMessage error: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String uploadFile(File file) {
        if (!isLoggedIn()) return "ERROR|Not logged in";
        try {
            // Placeholder cho tính tổng chunks đơn giản
            int totalChunks = (int) Math.ceil((double) file.length() / UDPConstants.CHUNK_DATA_SIZE);
            String request = ProtocolHelper.buildRequest(Command.UPLOAD, file.getName(), String.valueOf(totalChunks));
            String response = network.sendRequest(request);
            log("Upload request response: " + response);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                network.sendFileChunks(file, network.getServerAddress(), network.getServerPort());
                log("File upload completed");
                return "OK|File uploaded";
            }
            return response;
        } catch (Exception e) {
            log("Upload error: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String downloadFile(String filename, File saveLocation) {
        if (!isLoggedIn()) return "ERROR|Not logged in";
        try {
            String request = ProtocolHelper.buildRequest(Command.DOWNLOAD, filename);
            String response = network.sendRequest(request);
            log("Download request response: " + response);
            
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                int totalChunks = Integer.parseInt(parts[2]);
                
                File outputFile = new File(saveLocation, filename);
                network.receiveFileChunks(totalChunks, outputFile);
                log("File download completed to " + outputFile.getAbsolutePath());
                return "OK|File downloaded";
            }
            return response;
        } catch (Exception e) {
            log("Download error: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String logout() {
        if (!isLoggedIn()) return "OK|Already logged out";
        try {
            String request = ProtocolHelper.buildRequest(Command.LOGOUT);
            String response = network.sendRequest(request);
            log("Logout response: " + response);
            return response;
        } catch (Exception e) {
            log("Logout error: " + e.getMessage());
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

    public void close() {
        if (listener != null) {
            listener.stop();
        }
        if (network != null) {
            network.close();
        }
    }
}
