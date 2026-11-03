# Kiến trúc

## Tổng quan

```
┌──────────────── App (sandbox riêng) ────────────────┐        ┌──────── Knox Vault ────────┐
│ Compose UI → AppViewModel → VaultRepository         │        │ (chip bảo mật riêng)        │
│                    │                                │        │                             │
│                    ├─ VaultCrypto ─── Android ──────┼──────► │ sp_index_v1  AES-256-GCM    │
│                    │  (Keystore)      Keystore      │ ◄───── │ sp_secret_v1 AES-256-GCM    │
│                    │                  StrongBox     │        │ mã hoá / giải mã diễn ra ở  │
│                    ├─ PromptAuthenticator           │        │ đây; khoá không rời chip    │
│                    │  (BiometricPrompt: vân tay/PIN)│        └─────────────────────────────┘
│                    └─ VaultFileStore                │
│                       no_backup/vault/*.bin (bản mã)│
└─────────────────────────────────────────────────────┘
```

- `:core` là Kotlin/JVM thuần, không phụ thuộc Android. Chứa BIP39, định dạng file, codec, test trên JVM.
- `:app` là phần Android: Keystore/StrongBox, BiometricPrompt, lưu file, giao diện Compose, tự khoá.

## Khoá trong Knox Vault

| Alias | Loại | Bảo vệ | Điều kiện dùng |
|---|---|---|---|
| `sp_index_v1` | AES-256-GCM, StrongBox | Danh sách: nhãn, số từ, ngày | Đã xác thực (vân tay Class 3 hoặc PIN máy) trong **60 giây** gần nhất |
| `sp_secret_v1` | AES-256-GCM, StrongBox | Nội dung từng seed | Xác thực cho **từng thao tác** (BiometricPrompt + CryptoObject) |

Tham số chung:

- `setIsStrongBoxBacked(true)`.
- `setUserAuthenticationParameters(…, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)`.
- `setInvalidatedByBiometricEnrollment(false)`, để thêm hoặc xoá vân tay không làm mất dữ liệu.
- IV do Keystore sinh ngẫu nhiên, GCM tag 128 bit.
- `setUnlockedDeviceRequired(true)` nếu StrongBox của máy hỗ trợ. Nếu không, app tạo khoá không có tham số này và ghi lại; màn hình Kiểm tra thiết bị sẽ hiển thị điều đó.

Thứ tự thử khi tạo kho:

1. StrongBox kèm unlocked-device-required.
2. StrongBox không kèm.
3. Chỉ ở bản debug: TEE.

Bản release dừng ở bước 2 nếu thất bại.

Sau khi tạo, app kiểm tra `KeyInfo.securityLevel == SECURITY_LEVEL_STRONGBOX` cho cả hai khoá.

## Luồng xác thực

| Thao tác | Xác thực |
|---|---|
| Mở app | 1 lần, rồi giải mã danh sách bằng `sp_index_v1` |
| Xem seed | 1 lần, gắn với thao tác giải mã `sp_secret_v1`. Hiện lưới từ; sau 30 giây không thao tác thì xoá khỏi bộ nhớ |
| Thêm hoặc sửa | 1 lần để mã hoá. Việc cập nhật danh sách thường dùng lại lần xác thực vừa rồi; nếu đã quá 60 giây thì hỏi lại |
| Xoá, đánh dấu đã kiểm tra bản giấy | Cập nhật danh sách. Hỏi xác thực nếu đã quá 60 giây |
| Rời app, tắt màn hình, không thao tác (mặc định 2 phút) | Khoá lại và xoá mọi dữ liệu đã giải mã khỏi bộ nhớ |

## Lưu trữ

Mọi file nằm trong `noBackupFilesDir/vault/`, nên bị loại khỏi Auto Backup. `dataExtractionRules` còn chặn thêm cả cloud lẫn device-transfer.

- `index.bin`: danh sách đã mã hoá.
- `entries/<blobId>.bin`: mỗi seed một file.

Tất cả đều ghi bằng `AtomicFile`, nên không bao giờ có file ghi dở.

**Sửa theo kiểu copy-on-write.** Mỗi lần lưu tạo `blobId` mới:

1. Ghi blob mới.
2. Ghi danh sách mới. Đây là bước "commit".
3. Xoá blob cũ.

Nếu bước 2 lỗi, blob mới bị xoá và dữ liệu cũ vẫn nguyên vẹn.

### Blob v1

```
offset  size  field
0       4     magic "SPV1"
4       1     key slot (1 = index, 2 = secret)
5       1     độ dài IV (12)
6       12    IV
18      n     AES-256-GCM ciphertext || tag 128 bit
```

AAD là `storedphrase/v1|<slot>|<context>` (UTF-8), với `context` là `index` hoặc `entry/<blobId>`. Nhờ đó không thể tráo blob giữa các mục, hay giữa danh sách và nội dung, mà không bị phát hiện.

### Nội dung seed v2 (nhị phân, trước khi mã hoá)

```
u8          version = 2 (bản 0.1.0 dùng version 1, không có birthday; vẫn đọc được)
u8          số từ n ∈ {12, 15, 18, 21, 24}
n × u16 BE  chỉ số từ BIP39 (0..2047)
u16 BE + …  passphrase BIP39 (UTF-8)
u16 BE + …  ghi chú (UTF-8)
u8          1 nếu có birthday height, ngược lại 0
u32 BE      birthday height (ví Zcash, ví dụ Zodl), nếu có
```

App lưu **chỉ số** chứ không lưu chuỗi. Khi hiển thị, nó dùng chuỗi hằng có sẵn trong wordlist công khai, nên seed không tạo ra `String` bí mật nào trong RAM. Mảng chỉ số được ghi đè sau khi dùng.

### Danh sách (JSON, trước khi mã hoá)

```json
{"version":1,"entries":[{"id":"…","blobId":"…","label":"Ledger","wordCount":24,
  "hasPassphrase":false,"hasNotes":true,"createdAt":0,"updatedAt":0,"verifiedAt":null}]}
```

## Bàn phím BIP39

Từ được nhập bằng bàn phím riêng của app, **không qua bàn phím hệ thống**. Nhờ vậy Samsung Keyboard hay Gboard không học, đồng bộ hay gợi ý seed. Cách bàn phím hoạt động:

- Chỉ bật những chữ cái còn khớp với ít nhất một từ BIP39.
- Gợi ý theo tiền tố.
- Khi chỉ còn đúng một từ khớp thì tự điền.
- Checksum BIP39 được kiểm tra ngay khi đủ từ; seed sai checksum không lưu được.

Ô passphrase BIP39 và ghi chú buộc phải dùng bàn phím hệ thống. Các ô này là `EditText` với `IME_FLAG_NO_PERSONALIZED_LEARNING`, `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, và tắt autofill.

## File backup

Xem [BACKUP_FORMAT.md](BACKUP_FORMAT.md).

- **Xuất:** giải mã từng mục bên trong StrongBox (mỗi mục một lần xác thực, vì khoá nội dung gắn với từng thao tác). Sau đó đóng gói, rồi mã hoá bằng Argon2id(passphrase) + AES-256-GCM, và ghi file qua Storage Access Framework.
- **Nhập:** giải mã file bằng passphrase. App kiểm tra từng mục (định dạng, số từ, checksum BIP39), rồi mã hoá lại từng mục bằng khoá Knox Vault của máy hiện tại (mỗi mục một lần xác thực). Danh sách được commit sau mỗi mục.
- Bộ chọn file là một activity bên ngoài, nên app đánh dấu "external flow" trong lúc chọn để không tự khoá giữa chừng.

## Mật khẩu lớp 2

Mục có `layer2 = true` trong danh sách được bọc thêm một lớp:

```
Knox Vault (sp_secret_v1) ── AES-256-GCM ──► phong bì SPL2 ── Argon2id(mật khẩu lớp 2) + AES-256-GCM ──► EntryCodec
```

- Phong bì SPL2 dùng cùng header 45 byte với file backup (`PassphraseBox`, magic `SPL2`). Xem [BACKUP_FORMAT.md](BACKUP_FORMAT.md).
- **Xem:**
  1. Xác thực Knox Vault để ra phong bì.
  2. Hộp thoại hỏi mật khẩu lớp 2.
  3. Argon2id chạy khoảng 1–2 giây.
  4. Hiện seed.
- **Sai mật khẩu 5 lần:** app bỏ phong bì khỏi bộ nhớ, muốn thử tiếp phải xác thực Knox Vault lại. Mỗi lần thử tốn khoảng 64 MiB RAM và vài giây, nên dò mật khẩu ngay trong app rất chậm.
- **Sửa mục:** app không giữ mật khẩu lớp 2 trong bộ nhớ, nên muốn lưu phải nhập lại (giữ như cũ hoặc đổi mới).
- **Backup:** mục lớp 2 được xuất nguyên phong bì (loại 2), không cần biết mật khẩu lớp 2. Khi khôi phục vẫn cần đúng mật khẩu đó.
