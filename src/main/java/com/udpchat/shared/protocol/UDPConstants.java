package com.udpchat.shared.protocol;

public class UDPConstants {
    public static final int SERVER_PORT = 8888;
    public static final int BUFFER_SIZE = 65535;
    public static final int CHUNK_DATA_SIZE = 4096;
    public static final int MAX_RETRIES = 3;
    public static final int ACK_TIMEOUT_MS = 2000;
    public static final String DELIMITER = "|";
    
    private UDPConstants() {} // prevent instantiation
}
