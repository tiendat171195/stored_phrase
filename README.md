# Stored Phrase

App Android lưu **cụm từ khôi phục ví crypto (seed phrase BIP39, 12–24 từ)**, bảo vệ bằng chip bảo mật **Samsung Knox Vault**.

> ⚠️ Dự án đang được xây dựng. Chưa dùng để lưu seed thật cho tới khi bạn tự kiểm tra trên máy của mình và có bản sao lưu khác (bản giấy).

## Ý tưởng chính

- App bên thứ ba không ghi thẳng dữ liệu vào Knox Vault được. Cách duy nhất Samsung công bố là **Android Keystore + StrongBox**: trên Galaxy có Knox Vault, StrongBox chạy bên trong chip Knox Vault.
- Stored Phrase tạo các khoá **AES-256-GCM trong Knox Vault**. Các khoá này **không bao giờ rời khỏi chip**. Seed được mã hoá và giải mã **ngay trong chip**; app chỉ lưu **bản mã** trong vùng riêng của nó.
- Mỗi lần xem một seed đều phải xác thực bằng vân tay (Class 3) hoặc PIN/mật khẩu máy. Lần xác thực đó gắn với đúng thao tác giải mã trong chip.
- App **không có quyền INTERNET**, chặn chụp và quay màn hình, chặn backup cloud và chặn chuyển dữ liệu qua Smart Switch.

Chi tiết: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · Mô hình mối đe doạ: [docs/SECURITY.md](docs/SECURITY.md) · Test trên máy: [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md)

## Tính năng (bản hiện tại)

- **Kiểm tra thiết bị.** App tạo khoá thử với đúng cấu hình của kho và mã hoá thử ngay trong chip, sau đó kết luận máy có Knox Vault/StrongBox dùng được hay không.
- **Nhập seed bằng bàn phím BIP39 riêng của app**, không qua bàn phím hệ thống:
  - gợi ý theo tiền tố, tự điền khi chỉ còn một từ khớp;
  - kiểm tra checksum ngay khi đủ từ;
  - hỗ trợ 12, 15, 18, 21 và 24 từ;
  - có thể thêm passphrase BIP39 (từ thứ 25) và ghi chú.
- **Xem seed dạng lưới.** Mỗi lần xem phải xác thực một lần. Các từ hiện dạng che, chạm từng ô hoặc giữ nút để xem; sau 30 giây không thao tác thì tự xoá khỏi bộ nhớ.
- **Kiểm tra bản giấy.** App hỏi ngẫu nhiên vài vị trí, bạn gõ từ theo bản giấy; app không bao giờ hiện đáp án.
- **Tự khoá** khi rời app, tắt màn hình hoặc không thao tác (30 giây đến 5 phút, chỉnh được). Có nút **Xoá sạch kho**: huỷ khoá trong chip, toàn bộ bản mã thành rác.

## Trạng thái

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| M0 | Khung dự án, hardening manifest, CI | ✅ |
| M1 | Lõi Knox Vault, màn hình Kiểm tra thiết bị | ✅ |
| M2 | BIP39 + MVP (nhập, xem, quiz bản giấy, cài đặt) | ✅ chờ test trên máy thật |
| M3 | File backup mã hoá + script khôi phục Python | — |
| M4 | Passphrase lớp 2 | — |
| M5 | Xác minh attestation | — |
| M6 | Hoàn thiện, phát hành | — |

## Yêu cầu thiết bị

- Android 12 (API 31) trở lên, đã **đặt khoá màn hình**.
- Có StrongBox. Trên Samsung, StrongBox chạy trong Knox Vault, có từ Galaxy S21 trở về sau, các dòng Z từ 2021, Tab S8 trở đi, và một số dòng A từ 2024. Máy hãng khác có StrongBox (ví dụ Pixel với Titan M) cũng chạy được.
- Bản **release** từ chối chạy nếu không có StrongBox. Bản **debug** cho phép dùng TEE để thử trên emulator.

## Build

Cần JDK 17+ và Android SDK (compileSdk 37).

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK debug nằm ở `app/build/outputs/apk/debug/app-debug.apk`. CI (GitHub Actions) cũng build và đính kèm APK này.

### ⚠️ Bản debug chỉ dùng với dữ liệu giả

Bản debug ký bằng `app/keystores/debug.keystore`. Key này được commit công khai để các bản build từ máy nào cũng cài đè lên nhau được. Vì key công khai, **bất kỳ ai cũng ký được một APK giả cài đè lên bản debug**. Đừng bao giờ lưu seed thật vào bản debug (applicationId `…storedphrase.debug`).

### Bản release

Tạo keystore riêng (không bao giờ commit), rồi tạo file `keystore.properties` ở thư mục gốc (đã nằm trong `.gitignore`):

```properties
storeFile=/đường/dẫn/tới/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
```

## Cảnh báo mất dữ liệu

Khoá trong Knox Vault gắn chặt với máy và không thể xuất ra. Dữ liệu trong app sẽ **mất vĩnh viễn** nếu:

- gỡ app hoặc xoá dữ liệu app,
- reset máy, mất máy hoặc máy hỏng,
- **tắt khoá màn hình** (chuyển về None hoặc Vuốt).

Luôn giữ bản giấy. Từ mốc M3, app có thêm file backup mã hoá để chuyển sang máy mới.

## Cấu trúc

```
core/   Kotlin/JVM thuần: BIP39, định dạng file, codec (test nhanh trên JVM)
app/    Android: Keystore/StrongBox, BiometricPrompt, giao diện Compose
docs/   Kiến trúc, bảo mật, checklist test trên thiết bị
```
