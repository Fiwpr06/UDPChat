package com.udpchat.server.model;

import java.net.InetAddress;

public class ClientSession {
    private String username;
    private InetAddress address;
    private int requestPort;
    private int listenerPort;

    public ClientSession(String username, InetAddress address, int requestPort, int listenerPort) {
        this.username = username;
        this.address = address;
        this.requestPort = requestPort;
        this.listenerPort = listenerPort;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public InetAddress getAddress() { return address; }
    public void setAddress(InetAddress address) { this.address = address; }
    public int getRequestPort() { return requestPort; }
    public void setRequestPort(int requestPort) { this.requestPort = requestPort; }
    public int getListenerPort() { return listenerPort; }
    public void setListenerPort(int listenerPort) { this.listenerPort = listenerPort; }

    @Override
    public String toString() {
        return username + " (" + address.getHostAddress() + ":" + requestPort + " / " + listenerPort + ")";
    }
}
