package com.udpchat.shared.util;

import com.udpchat.shared.protocol.UDPConstants;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class UDPUtil {

    public static void sendString(DatagramSocket socket, String message, InetAddress address, int port) throws IOException {
        byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
        socket.send(packet);
    }

    public static String[] receiveString(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[UDPConstants.BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        
        String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String senderIP = packet.getAddress().getHostAddress();
        String senderPort = String.valueOf(packet.getPort());
        
        return new String[] { message, senderIP, senderPort };
    }
}
