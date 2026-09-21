package com.udpchat.server.service;

import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.util.FileChunkUtil;
import com.udpchat.shared.util.UDPUtil;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileService {
    private final Path filesDir;

    public FileService() {
        filesDir = Paths.get("server-data", "files");
        try {
            Files.createDirectories(filesDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void receiveFile(String filename, int totalChunks, DatagramSocket socket, InetAddress clientAddr, int clientPort) {
        Map<Integer, byte[]> chunks = new HashMap<>();
        try {
            socket.setSoTimeout(5000);
            int chunksReceived = 0;
            while (chunksReceived < totalChunks) {
                try {
                    String[] data = UDPUtil.receiveString(socket);
                    String raw = data[0];
                    if (raw.startsWith(Command.CHUNK.name())) {
                        String[] parts = ProtocolHelper.parseParams(raw);
                        int index = Integer.parseInt(parts[0]);
                        String base64 = parts[2];
                        boolean isNew = !chunks.containsKey(index);
                        chunks.put(index, FileChunkUtil.decodeChunk(base64));
                        UDPUtil.sendString(socket, Command.ACK.name() + "|" + index, clientAddr, clientPort);
                        if (isNew) {
                            chunksReceived++;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout waiting for chunk");
                }
            }
            // wait for TRANSFER_DONE
            try {
                String[] doneData = UDPUtil.receiveString(socket);
                if (doneData != null && doneData[0].startsWith(Command.TRANSFER_DONE.name())) {
                    File file = filesDir.resolve(filename).toFile();
                    FileChunkUtil.assembleFile(chunks, totalChunks, file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            socket.setSoTimeout(0); // reset
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getTotalChunks(String filename) {
        File file = filesDir.resolve(filename).toFile();
        if (!file.exists()) return 0;
        try {
            List<byte[]> chunks = FileChunkUtil.splitFile(file);
            return chunks.size();
        } catch (IOException e) {
            return 0;
        }
    }

    public void sendFile(String filename, DatagramSocket socket, InetAddress clientAddr, int clientPort) {
        File file = filesDir.resolve(filename).toFile();
        if (!file.exists()) return;
        try {
            List<byte[]> chunks = FileChunkUtil.splitFile(file);
            int total = chunks.size();
            socket.setSoTimeout(2000);
            for (int i = 0; i < total; i++) {
                String base64 = FileChunkUtil.encodeChunk(chunks.get(i));
                String chunkMsg = Command.CHUNK.name() + "|" + i + "|" + total + "|" + base64;
                boolean acked = false;
                int retries = 0;
                while (!acked && retries < 3) {
                    UDPUtil.sendString(socket, chunkMsg, clientAddr, clientPort);
                    try {
                        String[] ack = UDPUtil.receiveString(socket);
                        if (ack[0].startsWith(Command.ACK.name() + "|" + i)) {
                            acked = true;
                        }
                    } catch (SocketTimeoutException e) {
                        retries++;
                    }
                }
            }
            UDPUtil.sendString(socket, Command.TRANSFER_DONE.name(), clientAddr, clientPort);
            socket.setSoTimeout(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean fileExists(String filename) {
        return Files.exists(filesDir.resolve(filename));
    }
}
