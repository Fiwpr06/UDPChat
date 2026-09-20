package com.udpchat.shared.util;

import com.udpchat.shared.protocol.UDPConstants;
import java.io.*;
import java.util.*;

public class FileChunkUtil {

    public static List<byte[]> splitFile(File file) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[UDPConstants.CHUNK_DATA_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                if (bytesRead < UDPConstants.CHUNK_DATA_SIZE) {
                    byte[] lastChunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, lastChunk, 0, bytesRead);
                    chunks.add(lastChunk);
                } else {
                    chunks.add(buffer.clone());
                }
            }
        }
        return chunks;
    }

    public static void assembleFile(Map<Integer, byte[]> chunks, int totalChunks, File outputFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk == null) {
                    throw new IOException("Missing chunk: " + i);
                }
                fos.write(chunk);
            }
        }
    }

    public static String encodeChunk(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeChunk(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }
}
