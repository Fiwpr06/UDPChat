package com.udpchat.server.network;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MessageService;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.util.UDPUtil;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class RequestHandler {
    private final AuthService authService;
    private final MessageService messageService;
    private final FileService fileService;
    private final Consumer<String> logger;

    public RequestHandler(AuthService authService, MessageService messageService, FileService fileService, Consumer<String> logger) {
        this.authService = authService;
        this.messageService = messageService;
        this.fileService = fileService;
        this.logger = logger;
    }

    public void handle(String rawMessage, InetAddress senderAddr, int senderPort, DatagramSocket serverSocket) {
        try {
            Command cmd = ProtocolHelper.parseCommand(rawMessage);
            String[] params = ProtocolHelper.parseParams(rawMessage);
            String sessionKey = AuthService.makeSessionKey(senderAddr, senderPort);
            
            logger.accept("Received " + cmd + " from " + sessionKey);

            if (cmd == null) return;

            switch (cmd) {
                case REGISTER:
                    String regStatus = authService.register(params[0], params[1]);
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(regStatus.startsWith("Success") ? "OK" : "ERROR", regStatus), senderAddr, senderPort);
                    break;
                case LOGIN:
                    int listenerPort = Integer.parseInt(params[2]);
                    String loginStatus = authService.login(params[0], params[1], senderAddr, senderPort, listenerPort);
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(loginStatus.startsWith("Success") ? "OK" : "ERROR", loginStatus), senderAddr, senderPort);
                    if (loginStatus.startsWith("Success")) logger.accept("User " + params[0] + " logged in.");
                    break;
                case MESSAGE:
                    if (authService.isLoggedIn(sessionKey)) {
                        String username = authService.getUsername(sessionKey);
                        String content = params[0];
                        messageService.saveMessage(senderAddr.getHostAddress(), username, content);
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        String pushMsg = ProtocolHelper.buildPush(Command.INCOMING_MSG, username, timestamp, content);
                        
                        for (ClientSession session : authService.getOnlineSessionsExcept(sessionKey)) {
                            UDPUtil.sendString(serverSocket, pushMsg, session.getAddress(), session.getListenerPort());
                        }
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK"), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Not logged in"), senderAddr, senderPort);
                    }
                    break;
                case UPLOAD:
                    if (authService.isLoggedIn(sessionKey)) {
                        String filename = params[0];
                        int totalChunks = Integer.parseInt(params[1]);
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", "Ready"), senderAddr, senderPort);
                        fileService.receiveFile(filename, totalChunks, serverSocket, senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Not logged in"), senderAddr, senderPort);
                    }
                    break;
                case DOWNLOAD:
                    if (authService.isLoggedIn(sessionKey)) {
                        String dlFile = params[0];
                        if (fileService.fileExists(dlFile)) {
                            int totalChunks = fileService.getTotalChunks(dlFile);
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", String.valueOf(totalChunks)), senderAddr, senderPort);
                            fileService.sendFile(dlFile, serverSocket, senderAddr, senderPort);
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "File not found"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Not logged in"), senderAddr, senderPort);
                    }
                    break;
                case LOGOUT:
                    authService.logout(sessionKey);
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK"), senderAddr, senderPort);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
