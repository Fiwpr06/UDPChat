package com.udpchat.shared.protocol;

public enum Command {
    REGISTER,
    LOGIN,
    MESSAGE,
    UPLOAD,
    DOWNLOAD,
    LOGOUT,
    CHUNK,
    ACK,
    TRANSFER_DONE,
    INCOMING_MSG,
    RESPONSE
}
