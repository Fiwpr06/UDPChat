# 📡 UDPChat — Ứng dụng Client–Server giao thức UDP (Java 21 + JavaFX 21)

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![JavaFX 21](https://img.shields.io/badge/JavaFX-21-blue.svg)](https://openjfx.io/)
[![Protocol](https://img.shields.io/badge/Protocol-100%25%20UDP-green.svg)](#)
[![Theme](https://img.shields.io/badge/UI%20Theme-Amber%20Signal-yellow.svg)](#)
[![Build](https://img.shields.io/badge/Build-Passing%20(5%2F5%20Tests)-brightgreen.svg)](#)

Ứng dụng mạng mô hình **Client–Server** được lập trình hoàn toàn bằng **Java 21** và **JavaFX 21**, sử dụng **100% giao thức UDP (`DatagramSocket` / `DatagramPacket`)**, tuyệt đối không sử dụng TCP trong bất kỳ thành phần nào. Dự án hỗ trợ nhiều Client đồng thời, nhắn tin trò chuyện chuyển tiếp (forwarding) qua Server, truyền file phân mảnh tin cậy (Stop-and-Wait với ACK) và giao diện tối hiện đại **Amber Signal** (lấy cảm hứng từ *Deus Ex* & *Watch Dogs*).

---

## 📑 Mục lục
1. [Tính năng chính](#-tính-năng-chính)
2. [Kiến trúc mạng & Giao thức UDP](#-kiến-trúc-mạng--giao-thức-udp)
3. [Giao diện Amber Signal](#-giao-diện-amber-signal)
4. [Cấu trúc mã nguồn](#-cấu-trúc-mã-nguồn)
5. [Yêu cầu hệ thống](#-yêu-cầu-hệ-thống)
6. [Hướng dẫn cài đặt & Khởi chạy](#-hướng-dẫn-cài-đặt--khởi-chạy)
   - [Chạy 1-Click bằng Script (.bat / .vbs)](#1-chạy-1-click-bằng-script-bat--vbs-khuyên-dùng)
   - [Chạy bằng dòng lệnh Maven](#2-chạy-bằng-dòng-lệnh-maven)
   - [Chạy trong IDE (IntelliJ IDEA / Eclipse)](#3-chạy-trực-tiếp-trong-ide-intellij-idea--eclipse)
7. [Kiểm thử tự động (Unit / Integration Tests)](#-kiểm-thử-tự-động)
8. [Quy chuẩn định dạng lưu trữ](#-quy-chuẩn-định-dạng-lưu-trữ)

---

## 🚀 Tính năng chính

### Phía Client
- **Đăng ký tài khoản (Register)**: Đăng ký tài khoản người dùng mới với Server.
- **Đăng nhập (Login)**: Xác thực đăng nhập; tự động mở cổng lắng nghe và đăng ký với Server.
- **Nhắn tin trò chuyện (Chat)**: Gửi tin nhắn qua Server, Server tự động chuyển tiếp tới tất cả Client khác đang online.
- **Tải lên file (Upload)**: Cắt file thành các chunk 4KB, mã hóa Base64 và truyền tin cậy qua UDP (cơ chế Stop-and-Wait có ACK và tự động thử lại tối đa 3 lần).
- **Tải xuống file (Download)**: Yêu cầu file từ Server, nhận từng chunk và tự động ghép thành file gốc nguyên vẹn.
- **Đăng xuất (Logout)**: Thông báo Server đóng phiên, cập nhật trạng thái `OFFLINE`.
- **Tiện ích trên giao diện**:
  - Tự động nhận diện và hiển thị **IP máy tôi** (card mạng nội bộ) kèm nút **📋 Copy**.
  - Nút **📋 Copy** Server IP.
  - Nút **👁️ Ẩn/Hiện Log** giúp mở rộng tối đa không gian chat.
  - Nút **🗑️ Xóa log** màn hình.

### Phía Server
- **Khởi động / Dừng (Start/Stop)** trên cổng cấu hình (mặc định `8888`).
- **Quản lý người dùng**: Lưu từng tài khoản thành file văn bản riêng biệt: `server-data/users/{username}.txt`.
- **Lưu trữ vết tin nhắn**: Tự động lưu toàn bộ tin nhắn vào `server-data/messages/messages.log` theo đúng chuẩn định dạng: `IP|Tên|TĐ|Nội dung`.
- **Quản lý kết nối Client**: Hiển thị danh sách và số lượng Client online thời gian thực (chu kỳ quét 2 giây).
- **Truyền nhận file tập trung**: Lưu trữ file upload và phục vụ download tại thư mục `server-data/files/`.
- **Tiện ích quản trị**:
  - Tự động nhận diện IPv4 máy chủ kèm nút **📋 Copy IP**.
  - Nút **📋 Copy IP Client chọn** từ danh sách online.
  - Nút **📂 Mở file Log** trực tiếp bằng Notepad của Windows.
  - Nút **👁️ Ẩn/Hiện Log** và **🗑️ Xóa log** trên giao diện.

---

## 🔌 Kiến trúc mạng & Giao thức UDP

Vì UDP là giao thức phi kết nối (*connectionless*), hệ thống áp dụng các giải pháp kiến trúc sau:

```
[ Client A (Alice) ]                          [ UDP Server ]                          [ Client B (Bob) ]
  Socket Chính (Req)  ─── Gửi tin nhắn ────►   Port: 8888
                                              (Lưu log file)
                                              (Chuyển tiếp) ────── Gói INCOMING_MSG ───►  Socket Phụ (Listen)
                                                                                          (Daemon Thread)
```

### 1. Cơ chế 2-Socket phía Client
- **Socket chính (`ClientNetwork`)**: Dùng gửi yêu cầu (Request/Response) đồng bộ có thời gian chờ (timeout) 5 giây (REGISTER, LOGIN, UPLOAD, DOWNLOAD, LOGOUT).
- **Socket phụ (`ClientListener`)**: Chạy vòng lặp nền (*daemon thread*) trên một cổng ngẫu nhiên. Khi Client đăng nhập, cổng này được gửi lên Server (`LOGIN|user|pass|listenerPort`) để Server lưu vào phiên (`ClientSession`) và dùng để đẩy (*push*) tin nhắn chuyển tiếp từ Client khác về.

### 2. Cơ chế truyền file phân mảnh (Stop-and-Wait over UDP)
- Kích thước mỗi chunk: `4096 bytes`.
- Dữ liệu chunk được mã hóa **Base64** an toàn khi gửi qua gói tin text:
  `CHUNK|index|totalChunks|base64Payload`
- Mỗi khi nhận được chunk, bên nhận phản hồi gói tin `ACK|index`.
- Bên gửi chờ gói ACK với timeout `2000 ms`, thử lại tối đa `3 lần`. Khi nhận đủ ACK mới gửi chunk tiếp theo.
- Sau chunk cuối cùng, bên gửi phát gói `TRANSFER_DONE` để bên nhận ráp file và lưu vào đĩa.

### 3. Bảng lệnh giao thức (Command Protocol)

| Lệnh | Định dạng gửi | Ý nghĩa |
| :--- | :--- | :--- |
| `REGISTER` | `REGISTER\|<username>\|<password>` | Đăng ký tài khoản mới |
| `LOGIN` | `LOGIN\|<username>\|<password>\|<listenerPort>` | Đăng nhập và đăng ký cổng nhận tin |
| `MESSAGE` | `MESSAGE\|<nội dung>` | Gửi tin nhắn chat |
| `UPLOAD` | `UPLOAD\|<filename>\|<totalChunks>` | Báo hiệu tải lên file |
| `DOWNLOAD` | `DOWNLOAD\|<filename>` | Yêu cầu tải xuống file |
| `CHUNK` | `CHUNK\|<index>\|<totalChunks>\|<base64>` | Gói tin dữ liệu phân đoạn |
| `ACK` | `ACK\|<index>` | Xác nhận đã nhận chunk thành công |
| `TRANSFER_DONE` | `TRANSFER_DONE` | Hoàn tất quá trình truyền file |
| `INCOMING_MSG` | `INCOMING_MSG\|<sender>\|<time>\|<content>` | Server đẩy tin nhắn tới Client |
| `RESPONSE` | `RESPONSE\|OK\|...` hoặc `RESPONSE\|ERROR\|...` | Server phản hồi kết quả yêu cầu |
| `LOGOUT` | `LOGOUT` | Đăng xuất phiên làm việc |

---

## 🎨 Giao diện Amber Signal

Giao diện đồ họa được thiết kế theo chủ đề **Amber Signal** (Cyberpunk Industrial):
- **Bảng màu nền**: Phân lớp tối dịu mắt (Base `#1A1A2E`, Surface `#16213E`, Field `#0F0F1E`).
- **Màu nhấn (Accent)**: Vàng hổ phách `#C8A84E`, viền mảnh `#2A2A4A`.
- **Typography**: `Segoe UI` kết hợp monospace `Consolas` hỗ trợ hiển thị 100% tiếng Việt Unicode không bị lỗi font hay mất dấu.
- **8 Trạng thái nhận diện trực quan**:
  - `Online`: Xanh lá `#4ADE80` (có hiệu ứng glow)
  - `Offline`: Xám `#6B7280`
  - `Connecting`: Vàng hổ phách `#C8A84E`
  - `Success`: `#4ADE80` | `Warning`: `#FBBF24` | `Error`: `#F87171`
  - `Uploading`: Xanh dương `#60A5FA` | `Downloading`: Tím `#A78BFA`

---

## 📁 Cấu trúc mã nguồn

```
UDPChat/
├── pom.xml                                         # Cấu hình Maven (Java 21, JavaFX 21, Surefire)
├── run-demo.bat / run-demo.vbs                     # Script chạy demo (1 Server + 2 Client)
├── run-server.bat / run-server.vbs                 # Script chạy Server (kèm console hoặc ẩn console)
├── run-client.bat / run-client.vbs                 # Script chạy Client (kèm console hoặc ẩn console)
├── server-data/                                    # Thư mục dữ liệu Server (tự động tạo)
│   ├── users/                                      # File thông tin từng client đăng ký ({user}.txt)
│   ├── messages/                                   # Nhật ký tin nhắn (messages.log)
│   └── files/                                      # Thư mục lưu trữ file upload/download
└── src/
    ├── main/
    │   ├── java/
    │   │   ├── module-info.java                    # JPMS Module descriptor
    │   │   └── com/udpchat/
    │   │       ├── shared/                         # DÙNG CHUNG (Models, Protocols, Utilities)
    │   │       │   ├── model/Message.java          # Model tin nhắn (IP, Tên, TĐ, Nội dung)
    │   │       │   ├── protocol/Command.java       # Bộ 11 lệnh protocol
    │   │       │   ├── protocol/ProtocolHelper.java# Tiện ích đóng gói/giải mã packet
    │   │       │   ├── protocol/UDPConstants.java  # Hằng số mạng (cổng 8888, buffer, chunk)
    │   │       │   └── util/
    │   │       │       ├── FileChunkUtil.java      # Phân rã Base64 và ráp file theo index
    │   │       │       └── UDPUtil.java            # Tiện ích socket UDP, lấy IP nội bộ
    │   │       ├── client/                         # PHÍA CLIENT
    │   │       │   ├── ClientApp.java              # JavaFX Client entry point
    │   │       │   ├── controller/ClientController.java # Điều khiển giao diện & thread nền
    │   │       │   ├── service/ClientService.java  # Logic nghiệp vụ Client
    │   │       │   └── network/
    │   │       │       ├── ClientNetwork.java      # Socket chính (Request, Upload, Download)
    │   │       │       └── ClientListener.java     # Socket phụ daemon lắng nghe Server push
    │   │       └── server/                         # PHÍA SERVER
    │   │           ├── ServerApp.java              # JavaFX Server entry point
    │   │           ├── controller/ServerController.java # Điều khiển Server & giám sát online
    │   │           ├── service/
    │   │           │   ├── AuthService.java        # Quản lý tài khoản và phiên client online
    │   │           │   ├── MessageService.java     # Lưu messages.log theo chuẩn định dạng
    │   │           │   └── FileService.java        # Quản lý truyền nhận file UDP chunks
    │   │           ├── network/
    │   │           │   ├── ServerNetwork.java      # Vòng lặp socket UDP chính cổng 8888
    │   │           │   └── RequestHandler.java     # Điều phối lệnh & chuyển tiếp tin nhắn
    │   │           └── model/ClientSession.java    # Quản lý IP, requestPort, listenerPort
    │   └── resources/
    │       └── com/udpchat/
    │           ├── shared/styles/common.css        # Theme Amber Signal chuẩn
    │           ├── client/client_view.fxml         # Giao diện Client FXML
    │           ├── client/styles/client.css        # CSS tùy biến Client
    │           ├── server/server_view.fxml         # Giao diện Server FXML
    │           └── server/styles/server.css        # CSS tùy biến Server
    └── test/
        └── java/com/udpchat/
            └── IntegrationTest.java                # Kiểm thử tự động 5/5 kịch bản tích hợp
```

---

## 💻 Yêu cầu hệ thống

- **Hệ điều hành**: Windows 10/11 (khuyên dùng để sử dụng trọn vẹn script `.bat` và `.vbs`).
- **Java**: JDK 21 LTS trở lên (đã cài đặt tại máy).
- **Maven**: 3.8+ (hoặc dùng trực tiếp Maven Wrapper `mvnw.cmd` đi kèm project).

---

## 🛠️ Hướng dẫn cài đặt & Khởi chạy

### 1. Chạy 1-Click bằng Script (.bat / .vbs) — *Khuyên dùng*

Các file kịch bản đã được đặt sẵn ở thư mục gốc của project:

| Kịch bản | Thao tác | Mô tả |
| :--- | :--- | :--- |
| **Chạy Demo mượt mà** | Nhấn đúp chuột vào [`run-demo.vbs`](run-demo.vbs) | Tự động bật 1 Server và 2 Client (Alice, Bob), **ẩn hoàn toàn màn hình đen console CMD**, chỉ hiện giao diện JavaFX. |
| **Chạy Demo có console** | Nhấn đúp chuột vào [`run-demo.bat`](run-demo.bat) | Tự động mở 1 Server và 2 Client kèm cửa sổ terminal để quan sát luồng console. |
| **Mở Server riêng lẻ** | Nhấn đúp chuột vào [`run-server.bat`](run-server.bat) hoặc [`run-server.vbs`](run-server.vbs) | Khởi động riêng UDP Server. |
| **Mở thêm Client mới** | Nhấn đúp chuột vào [`run-client.bat`](run-client.bat) hoặc [`run-client.vbs`](run-client.vbs) | Nhấn đúp nhiều lần để mở bao nhiêu Client tùy ý. |

> **Ghi chú**: Các script `.bat` và `.vbs` đã tích hợp sẵn cơ chế tự động tìm kiếm `C:\Program Files\Java\jdk-21` nếu máy bạn chưa cấu hình biến môi trường `JAVA_HOME`.

---

### 2. Chạy bằng dòng lệnh Maven

Mở PowerShell tại thư mục dự án:

```powershell
# Thiết lập JAVA_HOME (nếu chưa có trong hệ thống)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# Cửa sổ 1: Khởi động Server
.\mvnw.cmd javafx:run -Pserver

# Cửa sổ 2: Khởi động Client 1 (Alice)
.\mvnw.cmd javafx:run -Pclient

# Cửa sổ 3: Khởi động Client 2 (Bob)
.\mvnw.cmd javafx:run -Pclient
```

---

### 3. Chạy trực tiếp trong IDE (IntelliJ IDEA / Eclipse)

1. Mở thư mục dự án `UDPChat` trong IDE dưới dạng **Maven Project**.
2. Đảm bảo Project SDK được chọn là **Java 21**.
3. **Chạy Server**: Chuột phải vào file [`ServerApp.java`](src/main/java/com/udpchat/server/ServerApp.java) $\to$ chọn **Run 'ServerApp.main()'**.
4. **Chạy Client**: Chuột phải vào file [`ClientApp.java`](src/main/java/com/udpchat/client/ClientApp.java) $\to$ chọn **Run 'ClientApp.main()'**.
5. **Mở nhiều Client trong IntelliJ**: Vào cấu hình *Run/Debug Configurations* của `ClientApp` $\to$ bật tùy chọn **"Allow multiple instances"** (Cho phép chạy nhiều phiên bản). Khi đó bạn có thể nhấn nút Run nhiều lần để mở nhiều cửa sổ Client đồng thời.

---

## 🧪 Kiểm thử tự động

Dự án tích hợp bộ kiểm thử tích hợp tự động toàn diện [IntegrationTest.java](src/test/java/com/udpchat/IntegrationTest.java) sử dụng **JUnit 5 Jupiter** và **Maven Surefire Plugin 3.2.5**:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"; .\mvnw.cmd test
```

### Kết quả kiểm thử thực tế (5/5 tests PASS):
1. **`testRegister`**: Đăng ký tài khoản mới, tạo file `server-data/users/alice.txt`, ngăn chặn đăng ký trùng tên.
2. **`testLogin`**: Kiểm tra mật khẩu đúng/sai, tạo phiên làm việc đa client.
3. **`testSendMessageAndForwarding`**: Client A gửi tin nhắn $\to$ Server ghi log $\to$ Server chuyển tiếp tức thì tới Client B qua cổng listener.
4. **`testFileUploadAndDownload`**: Cắt file 5.3 KB thành 2 chunks, truyền nhận Stop-and-Wait với ACK, Server ghép file, Client B download về kiểm tra toàn vẹn nội dung khớp 100%.
5. **`testLogout`**: Đăng xuất Client, dọn dẹp phiên kết nối.

---

## 📝 Quy chuẩn định dạng lưu trữ

Theo đúng yêu cầu của đề bài:

1. **Lưu trữ tài khoản Client**:
   Mỗi khi Client đăng ký, Server tạo một file riêng biệt trong thư mục `server-data/users/`:
   ```
   server-data/users/alice.txt
   server-data/users/bob.txt
   ```
   Nội dung file: `password=<mật khẩu>` (lưu dạng đơn giản không mã hóa).

2. **Lưu trữ tin nhắn Chat**:
   Được lưu nối tiếp trong `server-data/messages/messages.log`, thể hiện đúng 4 thông tin:
   ```
   IP|Tên|TĐ|Nội dung
   ```
   *Ví dụ thực tế:*
   ```text
   127.0.0.1|alice|2026-09-23 16:11:58|Hello Bob, this is Alice via UDP!
   192.168.1.15|bob|2026-09-23 16:12:05|Chào Alice, mình nhận được rồi!
   ```

---

## 👤 Tác giả & Giấy phép
- **Tác giả**: [Fiwpr06](https://github.com/Fiwpr06)
- **Dự án**: Bài tập môn học Lập trình mạng (Network Programming) — Client–Server UDP
- **Giấy phép**: MIT License
