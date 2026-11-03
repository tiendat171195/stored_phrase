# Kiểm thử trên thiết bị thật

StrongBox (Knox Vault) và vân tay chỉ kiểm được trên máy thật. Emulator không có StrongBox.

## Chuẩn bị

- Máy Galaxy có Knox Vault (S21 trở về sau, Z Fold/Flip 3+, Tab S8+, nhiều dòng A từ 2024), Android 12 trở lên.
- Đã đặt **khoá màn hình** (PIN, hình vẽ hoặc mật khẩu). Nên đăng ký thêm vân tay.
- Cài APK debug `app-debug.apk` (applicationId `io.github.tiendat171195.storedphrase.debug`). Có hai cách:
  - Mở file APK trên máy (cho phép "Cài ứng dụng không rõ nguồn gốc").
  - Qua máy tính:

    ```bash
    adb install -r app-debug.apk
    ```

> ⚠️ **Chỉ dùng seed giả.** Bản debug ký bằng key công khai. Hai seed dưới đây là vector thử nghiệm công khai của BIP39, không chứa tài sản:
>
> - 12 từ: `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about`
> - 24 từ: `abandon` × 23 rồi `art`

App chặn chụp màn hình, nên hãy **ghi kết quả bằng chữ** (hoặc chụp bằng một máy khác).

## Checklist

| # | Bước | Kết quả mong đợi |
|---|---|---|
| 1 | Mở app lần đầu | Màn hình **Kiểm tra thiết bị**: StrongBox ✓, khoá màn hình ✓, vân tay ✓, mã hoá thử trong chip ✓, cấu hình khoá có xác thực ✓, rồi dòng "Samsung Knox Vault sẵn sàng" |
| 2 | Bấm **Tạo kho** | Hộp xác thực hiện ra; sau đó là danh sách trống với huy hiệu "Knox Vault · StrongBox" |
| 3 | Thêm seed 12 từ bằng bàn phím trong app | Gõ `aba` là tự điền `abandon`. Đủ 12 từ thì báo "✓ Checksum BIP39 hợp lệ". Bấm Lưu thì phải xác thực |
| 4 | Đổi từ cuối thành `above` | Báo "✗ Sai checksum", nút Lưu bị khoá |
| 5 | Thêm seed 24 từ, bật passphrase + ghi chú | Lưu được; danh sách hiện "24 từ · có passphrase" |
| 6 | Mở seed, bấm **Hiện các từ** | Phải xác thực. Các từ hiện ở dạng `••••••`; chạm vào từng ô để hiện; giữ nút để hiện tất cả; sau 30 giây không thao tác thì tự ẩn |
| 7 | Chụp hoặc quay màn hình, mở Recents | Bị chặn hoặc ra ảnh đen; Recents không hiện nội dung |
| 8 | Nhấn Home rồi mở lại app | App đã khoá, phải xác thực lại |
| 9 | Để yên trong app 2 phút | App tự khoá |
| 10 | **Kiểm tra bản giấy**: nhập đúng các từ được hỏi | Báo "Bản giấy khớp"; danh sách hiện "Đã kiểm tra bản giấy …" |
| 11 | Kiểm tra bản giấy: nhập sai một từ | Báo vị trí sai, không hiện đáp án |
| 12 | Sửa seed (đổi tên), lưu | Vẫn xem được seed; trạng thái kiểm tra bản giấy được đặt lại |
| 13 | Xoá một seed | Seed biến mất khỏi danh sách |
| 14 | Thêm vân tay mới trong Cài đặt máy, quay lại app | Vẫn mở khoá và xem được seed như cũ |
| 15 | Trong hộp xác thực, chọn dùng PIN thay cho vân tay | Vẫn hoạt động (cả lúc mở khoá, lúc xem và lúc lưu) |
| 16 | Cài đặt → **Xoá sạch kho** → gõ `XOA` | Quay về màn hình Kiểm tra thiết bị, danh sách cũ không còn |
| 17 | *(Chỉ với dữ liệu giả)* Tắt khoá màn hình, rồi mở app | Màn hình "Khoá của kho đã bị huỷ"; bấm "Xoá và tạo kho mới" thì hoạt động (sau khi đặt lại khoá màn hình) |
| 18 | *(Tuỳ chọn, cần ADB)* Chạy `./gradlew connectedDebugAndroidTest` | 3 test pass |
| 19 | Sửa seed 24 từ: bật mục tuỳ chọn, nhập birthday height (ví dụ `2726400`), lưu, rồi hiện seed | Dòng "Birthday height: 2726400" hiện ra; danh sách có thêm nhãn "birthday" |
| 20 | Cài đặt → **Sao lưu ra file**: giữ passphrase ngẫu nhiên, tích ô xác nhận, chọn nơi lưu (ví dụ thư mục Download) | Mỗi seed phải xác thực một lần, rồi báo "Đã lưu file backup" |
| 21 | Chép file `.spbak` sang máy tính, chạy `python3 tools/recover_backup.py <file>` với passphrase vừa ghi | Script in ra đúng các seed, passphrase BIP39, birthday height và ghi chú |
| 22 | Cài đặt → Xoá sạch kho → Tạo kho → **Khôi phục từ file backup** → chọn file → nhập passphrase sai | Báo "Sai passphrase…" |
| 23 | Nhập passphrase đúng → **Nhập vào kho** | Mỗi seed phải xác thực một lần; danh sách hiện lại đủ các seed, xem được và đúng từng từ |
| 24 | Thêm seed mới, bật **mật khẩu lớp 2** (ví dụ `test-lop-hai-123`), lưu | Danh sách có nhãn "lớp 2" |
| 25 | Mở seed đó → Hiện các từ | Xác thực vân tay/PIN, rồi hộp thoại hỏi mật khẩu lớp 2; nhập đúng thì seed hiện ra (sau khoảng 1–2 giây) |
| 26 | Nhập sai mật khẩu lớp 2 năm lần | Báo số lần còn lại; đến lần thứ 5 thì đóng hộp thoại, phải hiện lại (xác thực lại) mới được thử tiếp |
| 27 | Sửa mục lớp 2 | Muốn lưu phải nhập lại mật khẩu lớp 2 |
| 28 | Xuất backup có mục lớp 2, rồi chạy `recover_backup.py` | Script hỏi riêng mật khẩu lớp 2 cho mục đó; để trống thì bỏ qua |
| 29 | Cài đặt → Kiểm tra thiết bị → phần **Xác minh attestation** | Cả 6 dòng ✓ ("Chuỗi chứng chỉ… root của Google" ghi tên root), và dòng "Đã xác minh". Gửi lại cho tôi dòng nhỏ "Attestation phiên bản … · bản vá bảo mật …" |
| 30 | Quay lại danh sách | Huy hiệu hiện "Knox Vault · StrongBox · đã xác minh" |

## Gửi lại kết quả

- Model máy, phiên bản Android và One UI.
- Kết quả từng dòng ở màn hình Kiểm tra thiết bị (✓ / ! / ✗, kèm dòng chữ nhỏ nếu có).
- Bước nào lỗi, kèm thông báo lỗi hiển thị.
