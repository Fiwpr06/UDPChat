-- Khởi tạo Database và các bảng cho UDP Mail Chat
CREATE TABLE IF NOT EXISTS users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    gmail_address VARCHAR(255) DEFAULT NULL,
    client_ip     VARCHAR(45)  DEFAULT '127.0.0.1',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_gmail (gmail_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oauth_tokens (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    gmail_address VARCHAR(255) NOT NULL,
    access_token  TEXT         NOT NULL,
    refresh_token TEXT         NOT NULL,
    token_type    VARCHAR(50)  DEFAULT 'Bearer',
    expires_at    DATETIME     NOT NULL,
    scope         VARCHAR(500) DEFAULT 'https://mail.google.com/',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emails (
    id               VARCHAR(36)  PRIMARY KEY,
    thread_id        VARCHAR(36)  NOT NULL,
    gmail_message_id VARCHAR(255) DEFAULT NULL,
    sender           VARCHAR(50)  NOT NULL,
    sender_email     VARCHAR(255) DEFAULT NULL,
    subject          VARCHAR(500) DEFAULT '(Không có chủ đề)',
    body             MEDIUMTEXT,
    sent_at          VARCHAR(50),
    timestamp_order  BIGINT       NOT NULL,
    is_read          BOOLEAN      DEFAULT FALSE,
    is_starred       BOOLEAN      DEFAULT FALSE,
    is_draft         BOOLEAN      DEFAULT FALSE,
    reply_to_id      VARCHAR(36)  DEFAULT NULL,
    created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_thread (thread_id),
    INDEX idx_sender (sender),
    INDEX idx_sent_at (sent_at),
    INDEX idx_timestamp (timestamp_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS email_recipients (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    email_id   VARCHAR(36)  NOT NULL,
    recipient  VARCHAR(255) NOT NULL,
    rec_type   VARCHAR(10)  DEFAULT 'TO',
    folder     VARCHAR(20)  NOT NULL,
    owner      VARCHAR(50)  NOT NULL,
    is_read    BOOLEAN      DEFAULT FALSE,
    is_starred BOOLEAN      DEFAULT FALSE,
    FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
    INDEX idx_owner_folder (owner, folder),
    INDEX idx_email (email_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS attachments (
    id           VARCHAR(36)  PRIMARY KEY,
    email_id     VARCHAR(36)  NOT NULL,
    filename     VARCHAR(500) NOT NULL,
    file_size    BIGINT       DEFAULT 0,
    content_type VARCHAR(50)  DEFAULT 'OTHER',
    uploaded_by  VARCHAR(50),
    uploaded_at  VARCHAR(50),
    FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
    INDEX idx_email (email_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS server_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
