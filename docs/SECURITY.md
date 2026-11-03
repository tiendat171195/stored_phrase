# Bảo mật & mô hình mối đe doạ

## Tài sản cần bảo vệ

- Seed phrase (chỉ số từ BIP39), passphrase BIP39, ghi chú. Đây là bí mật cấp cao nhất.
- Nhãn và metadata (tên ví, số từ, ngày). Ít nhạy cảm hơn, nhưng vẫn được mã hoá.

## Những gì app chống được

| Mối đe doạ | Biện pháp |
|---|---|
| Mất máy khi đang khoá; trích xuất bộ nhớ (forensic) | Dữ liệu chỉ tồn tại dưới dạng bản mã AES-256-GCM. Khoá nằm trong Knox Vault, không xuất ra được, có cảm biến chống can thiệp vật lý. Khoá chỉ dùng được khi máy đang mở khoá (nếu StrongBox hỗ trợ) và sau khi xác thực. Thử PIN bị giới hạn bằng phần cứng. |
| App khác trên máy | Sandbox của Android; chỉ có một activity được exported. |
| Backup cloud, Smart Switch | `allowBackup=false`, `dataExtractionRules` loại trừ mọi thứ, file nằm trong `noBackupFilesDir`. |
| Gửi dữ liệu ra mạng | Không có quyền INTERNET; CI kiểm tra APK không chứa quyền này. |
| Chụp màn hình, quay màn hình, cast, ảnh trong Recents | `FLAG_SECURE`, `setRecentsScreenshotEnabled(false)`. |
| Overlay hoặc tapjacking | `setHideOverlayWindows(true)`, `filterTouchesWhenObscured`. |
| Bàn phím học hoặc đồng bộ seed | Bàn phím BIP39 riêng trong app; ô văn bản dùng cờ no-learning và no-suggestions. |
| Autofill, content capture, dịch vụ trợ năng độc hại | Tắt autofill và content capture cho toàn cửa sổ. `accessibilityDataSensitive` (Android 14+) chỉ cho dịch vụ trợ năng thật đọc màn hình. |
| Sửa hoặc tráo file bản mã | GCM tag + AAD gắn với loại dữ liệu và `blobId`. |
| Người cầm máy đang mở khoá nhưng không biết PIN | Danh sách cần xác thực trong 60 giây gần nhất; mỗi lần xem seed cần xác thực mới. App tự khoá khi rời app hoặc khi không thao tác. |

## Những gì app KHÔNG chống được

- **Người có máy và biết PIN hoặc mật khẩu máy.** Họ xác thực được như bạn. Mốc M4 sẽ thêm *passphrase lớp 2* cho từng mục để chống trường hợp này.
- **Máy đã bị root hoặc mã độc chiếm quyền hệ điều hành** trong lúc bạn đang xem seed: dữ liệu đã giải mã trên màn hình hay trong RAM có thể bị đọc.
- **Nhìn trộm, camera.**
- **Bản debug.** Bản debug ký bằng key công khai (`app/keystores/debug.keystore`), nên ai cũng có thể cài đè một APK giả và đọc dữ liệu của nó. Bản debug chỉ dành cho dữ liệu giả.
- **Mất dữ liệu.** Mất máy, gỡ app, reset máy hoặc tắt khoá màn hình đều làm khoá Knox Vault mất vĩnh viễn. Hãy giữ bản giấy và tạo file backup mã hoá.
- **File backup không được Knox Vault bảo vệ.** Ai có file đều có thể dò passphrase offline. Mỗi lần thử tốn khoảng 64 MiB bộ nhớ và nhiều vòng Argon2id, nên dò rất chậm, nhưng một passphrase yếu vẫn có thể bị dò ra. Hãy dùng passphrase ngẫu nhiên 7 từ (khoảng 90 bit) và cất file tách khỏi passphrase.
- **Dọn bộ nhớ không triệt để.** JVM, Compose và `String` có thể để lại bản sao trong RAM. App giảm thiểu bằng cách lưu chỉ số từ và ghi đè mảng ngay sau khi dùng, nhưng không thể đảm bảo tuyệt đối.

## Giới hạn đã biết

- Emulator không có StrongBox. Bản debug dùng TEE, kèm cảnh báo.
- Trên đa số Galaxy, nhận diện khuôn mặt là Class 2 (weak) nên không mở được khoá. Chỉ dùng được vân tay hoặc PIN, hình vẽ, mật khẩu máy.
- Màn hình Kiểm tra thiết bị chỉ để bạn yên tâm, không thay được việc phát hiện máy đã bị root. Xác minh attestation (M5) cũng chạy offline và không tra danh sách thu hồi.

## Báo lỗi bảo mật

Đừng tạo issue công khai có kèm seed thật. Hãy liên hệ chủ repo qua GitHub và chỉ mô tả lỗi.
