package com.udpchat.client.network;

import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.UDPConstants;
import com.udpchat.shared.util.FileChunkUtil;
import com.udpchat.shared.util.UDPUtil;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Xử lý giao tiếp UDP dạng request-response giữa client và server.
public class ClientNetwork {
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort;

    public ClientNetwork(InetAddress serverAddress, int serverPort) throws SocketException {
        this.socket = new DatagramSocket();
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    public synchronized String sendRequest(String request) throws Exception {
        UDPUtil.sendString(socket, request, serverAddress, serverPort);
        socket.setSoTimeout(5000);
        String[] response = UDPUtil.receiveString(socket);
        // Reset timeout
        socket.setSoTimeout(0);
        return response[0];
    }

    public synchronized void sendFileChunks(File file, InetAddress addr, int port) throws Exception {
        sendFileChunks(file, addr, port, null);
    }

    public synchronized void sendFileChunks(File file, InetAddress addr, int port, java.util.function.Consumer<Double> onProgress) throws Exception {
        List<byte[]> chunks = FileChunkUtil.splitFile(file);
        int totalChunks = chunks.size();

        for (int i = 0; i < totalChunks; i++) {
            String base64Data = FileChunkUtil.encodeChunk(chunks.get(i));
            String chunkMessage = Command.CHUNK.name() + UDPConstants.DELIMITER + i + UDPConstants.DELIMITER + totalChunks + UDPConstants.DELIMITER + base64Data;
            
            boolean ackReceived = false;
            int retries = 0;
            
            while (!ackReceived && retries < UDPConstants.MAX_RETRIES) {
                UDPUtil.sendString(socket, chunkMessage, addr, port);
                try {
                    socket.setSoTimeout(UDPConstants.ACK_TIMEOUT_MS);
                    String[] response = UDPUtil.receiveString(socket);
                    if (response[0].startsWith(Command.ACK.name() + UDPConstants.DELIMITER + i)) {
                        ackReceived = true;
                        if (onProgress != null) {
                            onProgress.accept((double) (i + 1) / totalChunks);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    retries++;
                }
            }
            if (!ackReceived) {
                throw new Exception("Không nhận được ACK cho chunk " + i);
            }
        }
        
        socket.setSoTimeout(0);
        UDPUtil.sendString(socket, Command.TRANSFER_DONE.name(), addr, port);
    }

    public synchronized void receiveFileChunks(int totalChunks, File outputFile) throws Exception {
        receiveFileChunks(totalChunks, outputFile, serverAddress, serverPort, null);
    }

    public synchronized void receiveFileChunks(int totalChunks, File outputFile, java.util.function.Consumer<Double> onProgress) throws Exception {
        receiveFileChunks(totalChunks, outputFile, serverAddress, serverPort, onProgress);
    }

    public synchronized void receiveFileChunks(int totalChunks, File outputFile, InetAddress fileServerAddr, int fileServerPort, java.util.function.Consumer<Double> onProgress) throws Exception {
        Map<Integer, byte[]> chunksMap = new HashMap<>();
        socket.setSoTimeout(5000);
        
        while (chunksMap.size() < totalChunks) {
            String[] response = UDPUtil.receiveString(socket);
            String message = response[0];

            if (message.startsWith(Command.CHUNK.name())) {
                String[] parts = message.split("\\" + UDPConstants.DELIMITER);
                int index = Integer.parseInt(parts[1]);
                String base64Data = parts[3];
                
                chunksMap.put(index, FileChunkUtil.decodeChunk(base64Data));
                if (onProgress != null) {
                    onProgress.accept((double) chunksMap.size() / totalChunks);
                }
                
                // Gửi ACK đến port riêng của server (nơi server đang nhận ACK)
                String ack = Command.ACK.name() + UDPConstants.DELIMITER + index;
                UDPUtil.sendString(socket, ack, fileServerAddr, fileServerPort);
            } else if (message.equals(Command.TRANSFER_DONE.name())) {
                break;
            }
        }
        
        // Nhận gói TRANSFER_DONE nếu server gửi sau chunk cuối để làm sạch socket buffer
        try {
            socket.setSoTimeout(1000);
            UDPUtil.receiveString(socket);
        } catch (Exception ignored) {}
        
        socket.setSoTimeout(0);
        FileChunkUtil.assembleFile(chunksMap, totalChunks, outputFile);
    }

    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public InetAddress getServerAddress() {
        return serverAddress;
    }

    public int getServerPort() {
        return serverPort;
    }
}
