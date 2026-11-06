# ✉️ Mail Server & Mail Client — Lab 3 (Java 21 + JavaFX 21)

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![JavaFX 21](https://img.shields.io/badge/JavaFX-21-blue.svg)](https://openjfx.io/)
[![Protocol](https://img.shields.io/badge/Protocols-SMTP%20%7C%20POP3%20(TCP)-green.svg)](#)
[![Dual Database](https://img.shields.io/badge/Database-MySQL%20%7C%20SQLite%20Fallback-blue.svg)](#)
[![Theme](https://img.shields.io/badge/UI%20Theme-Amber%20Signal-yellow.svg)](#)
[![Build](https://img.shields.io/badge/Build-Passing%20(6%2F6%20Tests)-brightgreen.svg)](#)

Ứng dụng **Mail Server & Mail Client** chuẩn học thuật cho bài tập lớn **Lab 3 (Lập trình mạng)**, được xây dựng hoàn toàn bằng **Java 21** và **JavaFX 21**. Hệ thống phân định rạch ròi giữa việc **Gửi thư (SMTP - RFC 5321)**, **Nhận thư (POP3 - RFC 1939)** và **Lưu trữ CSDL (MySQL / SQLite nhúng tự động)** trên nền tảng **TCP Socket**.

---

## 📑 Mục lục
1. [Đáp ứng tiêu chuẩn Lab 3](#-đáp-ứng-tiêu-chuẩn-lab-3)
2. [Kiến trúc giao thức & Luồng xử lý](#-kiến-trúc-giao-thức--luồng-xử-lý)
3. [Cơ chế Dual Database](#-cơ-chế-dual-database-mysql--sqlite)
4. [Tệp đính kèm (Attachments)](#-tệp-đính-kèm-attachments)
5. [Cấu trúc mã nguồn](#-cấu-trúc-mã-nguồn)
6. [Hướng dẫn cài đặt & Khởi chạy](#-hướng-dẫn-cài-đặt--khởi-chạy)
7. [Kiểm thử tự động (Unit / Integration Tests)](#-kiểm-thử-tự-động)

---

## 🎯 Đáp ứng tiêu chuẩn Lab 3

| Tiêu chuẩn | Triển khai trong dự án | Trạng thái |
| :--- | :--- | :---: |
| **Giao thức SMTP** | Sử dụng TCP Socket cổng `2525`. Thực hiện đầy đủ chuỗi lệnh bắt tay SMTP chuẩn: `HELO` $\to$ `MAIL FROM` $\to$ `RCPT TO` $\to$ `DATA` $\to$ `QUIT`. | ✅ Đạt 100% |
| **Giao thức POP3** | Sử dụng TCP Socket cổng `1110`. Thực hiện đầy đủ chuỗi lệnh POP3: `USER` $\to$ `PASS` $\to$ `STAT` $\to$ `LIST` $\to$ `RETR` $\to$ `DELE` $\to$ `QUIT`. | ✅ Đạt 100% |
| **Gửi thư trên Gmail** | Gửi trực tiếp hoặc chuyển tiếp qua `smtp.gmail.com:587` (TLS) sử dụng **Mật khẩu ứng dụng (App Password 16 ký tự)**. | ✅ Đạt 100% |
| **Gửi thư trên localhost** | Gửi email giữa các tài khoản nội bộ (`alice`, `bob`...) trên cổng 2525 và tự động lưu vào CSDL Server. | ✅ Đạt 100% |
| **Lưu trữ Database** | Lưu trữ toàn bộ tài khoản (`users`), email (`emails`) và tệp đính kèm (`attachments`). | ✅ Đạt 100% |
| **Tệp đính kèm** | Đóng gói theo chuẩn MIME multipart và Base64, hỗ trợ đính kèm nhiều tệp và tải về máy. | ✅ Đạt 100% |

---

## 🔌 Kiến trúc giao thức & Luồng xử lý

```
[ Client A (Alice) ]                          [ Mail Server ]                          [ Client B (Bob) ]
        │                                            │                                          │
        ├──── Gửi thư (SMTP: TCP 2525) ─────────────►│                                          │
        │     HELO -> MAIL FROM -> RCPT TO -> DATA   │ (Lưu vào CSDL)                           │
        │     250 OK: Message accepted               │                                          │
        │                                            │                                          │
        │                                            │◄─── Nhận thư (POP3: TCP 1110) ───────────┤
        │                                            │     USER -> PASS -> STAT -> LIST -> RETR │
        │                                            │──── Trả nội dung MIME + Attachments ────►│
```

---

## 💾 Cơ chế Dual Database (MySQL + SQLite)

Để đảm bảo chương trình không bao giờ gặp lỗi `Connection refused` khi bảo vệ hoặc chấm bài:
1. **Ưu tiên MySQL**: Khởi động kết nối `jdbc:mariadb://localhost:3306/udp_mail_chat`. Nếu MySQL đang chạy, hệ thống sẽ sử dụng MySQL.
2. **Tự động Fallback sang SQLite**: Nếu MySQL chưa bật, `DatabaseManager` tự động kích hoạt CSDL SQLite nhúng lưu tại `server-data/mail_server.db`, tự tạo các bảng `users`, `emails`, `attachments` mà không gây gián đoạn hay crash ứng dụng.

---

## 📁 Cấu trúc mã nguồn

```
src/main/java/com/udpchat/
├── common/
│   ├── model/
│   │   ├── User.java                     # Model tài khoản người dùng
│   │   ├── EmailMessage.java             # Model email (From, To, Subject, Body, Date, Attachments)
│   │   └── Attachment.java               # Model tệp đính kèm (Tên, Kích thước, Dữ liệu byte[])
│   └── util/
│       ├── NetworkUtil.java              # Đọc/ghi dòng văn bản chuẩn CRLF (\r\n)
│       └── MimeUtil.java                 # Chuyển đổi EmailMessage <-> Chuỗi MIME RFC 822 / Multipart
│
├── database/
│   ├── DatabaseManager.java              # Quản lý kết nối: MySQL ưu tiên + SQLite Fallback
│   ├── UserDao.java                      # Đăng ký & Xác thực người dùng
│   ├── EmailDao.java                     # Lưu thư, truy vấn hộp thư đến (INBOX) và thư đi (SENT)
│   └── AttachmentDao.java                # Lưu và truy xuất tệp đính kèm trên đĩa/DB
│
├── server/
│   ├── ServerApp.java                    # Entry point giao diện JavaFX Server
│   ├── controller/ServerController.java  # Bảng điều khiển cổng 2525/1110, nhật ký log thời gian thực
│   ├── smtp/
│   │   ├── SmtpServer.java               # ServerSocket TCP cổng 2525
│   │   └── SmtpSessionHandler.java       # Bộ xử lý lệnh SMTP (HELO, MAIL FROM, RCPT TO, DATA, QUIT)
│   ├── pop3/
│   │   ├── Pop3Server.java               # ServerSocket TCP cổng 1110
│   │   └── Pop3SessionHandler.java       # Bộ xử lý lệnh POP3 (USER, PASS, STAT, LIST, RETR, DELE, QUIT)
│   └── service/
│       └── GmailForwarder.java           # Chuyển tiếp email ra Gmail thật nếu người nhận là @gmail.com
│
└── client/
    ├── ClientApp.java                    # Entry point giao diện JavaFX Client
    ├── controller/ClientController.java  # Điều khiển giao diện Client (Hộp thư, Soạn thư, Đính kèm)
    ├── smtp/
    │   └── SmtpClient.java               # Socket TCP gửi thư SMTP lên cổng 2525
    ├── pop3/
    │   └── Pop3Client.java               # Socket TCP nhận thư POP3 từ cổng 1110
    └── service/
        └── GmailDirectClient.java        # Gửi trực tiếp ra Gmail SMTP TLS (smtp.gmail.com:587)
```

---

## 🛠️ Hướng dẫn cài đặt & Khởi chạy

### 1. Chạy 1-Click bằng Script (.vbs / .bat)

Các file kịch bản đã có sẵn ở thư mục gốc:

| Thao tác | Mô tả |
| :--- | :--- |
| Nhấn đúp chuột vào [`run-demo.vbs`](run-demo.vbs) | Mở tự động 1 Server và 2 Client (Alice, Bob) không hiện màn hình đen console. |
| Nhấn đúp chuột vào [`run-server.vbs`](run-server.vbs) | Khởi động riêng Mail Server (SMTP 2525 & POP3 1110). |
| Nhấn đúp chuột vào [`run-client.vbs`](run-client.vbs) | Khởi động Mail Client (nhấn nhiều lần để mở nhiều Client đồng thời). |

### 2. Chạy bằng dòng lệnh Maven Wrapper

```powershell
# Cửa sổ 1: Khởi động Server
.\mvnw.cmd javafx:run -Pserver

# Cửa sổ 2: Khởi động Client
.\mvnw.cmd javafx:run -Pclient
```

---

## 🧪 Kiểm thử tự động (Integration Test)

Chạy toàn bộ 6/6 kịch bản kiểm thử tích hợp tự động qua Maven:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"; .\mvnw.cmd test
```

### Các kịch bản kiểm thử:
1. `testDatabaseConnection`: Kiểm tra kết nối CSDL và tự động kích hoạt SQLite fallback.
2. `testUserRegistrationAndAuth`: Kiểm tra đăng ký, đăng nhập và bảo mật mật khẩu.
3. `testSmtpSendWithAttachment`: Kiểm tra gửi email kèm file đính kèm qua Socket SMTP cổng 12525.
4. `testPop3FetchMailboxAndRetrieval`: Đăng nhập POP3 cổng 11110, STAT, LIST, RETR tải thư và file đính kèm.
5. `testPop3AuthenticationFailure`: Chặn đăng nhập sai mật khẩu với phản hồi `-ERR`.
6. `testPop3DeleteEmail`: Xóa thư bằng lệnh POP3 `DELE` và kiểm tra cập nhật hộp thư.
