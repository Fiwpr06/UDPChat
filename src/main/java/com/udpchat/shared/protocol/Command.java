package com.udpchat.shared.protocol;

/**
 * Các lệnh giao thức qua UDP Mail System
 */
public enum Command {
    // Xác thực
    REGISTER,
    LOGIN,
    LOGOUT,

    // Google OAuth 2.0 & Email
    OAUTH_START,
    OAUTH_CALLBACK,
    OAUTH_STATUS,
    SET_EMAIL,
    GET_EMAIL,

    // Thao tác thư
    SEND_MAIL,
    LIST_MAIL,
    READ_MAIL,
    READ_THREAD,
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
