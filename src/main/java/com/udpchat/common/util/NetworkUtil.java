package com.udpchat.common.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

/**
 * Tiện ích đọc ghi dòng văn bản chuẩn Internet Protocol (CRLF: \r\n)
 */
public class NetworkUtil {
    public static final String CRLF = "\r\n";

    public static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write(CRLF);
        writer.flush();
    }

    public static String readLine(BufferedReader reader) throws IOException {
        return reader.readLine();
    }
}
