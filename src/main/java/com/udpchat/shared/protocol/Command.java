package com.udpchat.shared.protocol;

/**
 * Các lệnh giao thức qua UDP Mail System
 */
public enum Command {
    // Xác thực
    REGISTER,
    LOGIN,
    LOGOUT,

    // Thao tác thư
    SEND_MAIL,
    LIST_MAIL,
    READ_MAIL,
    DELETE_MAIL,
    STAR_MAIL,
    MARK_READ,
    SAVE_DRAFT,
    SEARCH_MAIL,
    GET_STATS,

    // Truyền tệp đính kèm UDP
    UPLOAD,
    DOWNLOAD,
    CHUNK,
    ACK,
    TRANSFER_DONE,

    // Thông báo đẩy từ Server tới Client
    NEW_MAIL,

    // Phản hồi chung
    RESPONSE
}
