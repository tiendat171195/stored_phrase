# Định dạng file backup `.spbak` (phiên bản 1)

File backup chứa toàn bộ seed phrase trong kho, được mã hoá bằng **passphrase của file backup**. File này **không** dùng Knox Vault: khoá Knox Vault không thể rời khỏi máy, nên đây là cách duy nhất để chuyển seed sang máy mới. Vì vậy độ an toàn của file phụ thuộc hoàn toàn vào passphrase. Hãy dùng passphrase ngẫu nhiên 7 từ do app tạo (khoảng 90 bit).

Nếu không còn app, có thể giải mã file trên máy tính bằng `tools/recover_backup.py`:

```bash
python3 -m pip install -r tools/requirements.txt
```

```bash
python3 tools/recover_backup.py stored-phrase-backup-2026-11-03.spbak
```

Lệnh thứ hai hỏi passphrase rồi in ra các seed, passphrase BIP39, birthday height và ghi chú. Thêm `--json` nếu muốn kết quả dạng JSON. Chỉ chạy trên máy tính offline mà bạn tin tưởng.

## Header (45 byte, không mã hoá, được xác thực làm AAD)

| Offset | Kích thước | Trường |
|---|---|---|
| 0 | 4 | magic `SPBK` |
| 4 | 1 | phiên bản định dạng = 1 |
| 5 | 1 | KDF = 1 (Argon2id, phiên bản 0x13, RFC 9106) |
| 6 | 4 | bộ nhớ Argon2 tính bằng KiB (u32 big-endian), mặc định 65536 (64 MiB) |
| 10 | 4 | số vòng lặp Argon2 (u32 BE), mặc định 3 |
| 14 | 1 | độ song song Argon2, mặc định 1 |
| 15 | 1 | độ dài salt = 16 |
| 16 | 16 | salt ngẫu nhiên |
| 32 | 1 | độ dài nonce = 12 |
| 33 | 12 | nonce ngẫu nhiên |
| 45 | n | `AES-256-GCM(key, nonce, payload, aad = byte 0..44)`, tag 128 bit ở cuối |

- Khoá: `key = Argon2id(password, salt, m, t, p, length = 32)`.
- `password` là passphrase dạng UTF-8 sau khi chuẩn hoá **NFKD**. Passphrase toàn ASCII được dùng nguyên văn, không cần chuẩn hoá.
- Khi đọc file, app và script đều từ chối tham số ngoài giới hạn: bộ nhớ 8–256 MiB, vòng lặp 1–16, song song 1–8. Nhờ đó một file độc hại không thể bắt máy cấp phát hàng GB bộ nhớ.
- Sai passphrase và file bị sửa (kể cả sửa header) đều làm kiểm tra tag GCM thất bại. App không phân biệt được hai trường hợp này.

## Payload (sau khi giải mã)

```
u8         payload version = 1
u32 BE + … manifest JSON (UTF-8): {"createdAt": <ms>, "generator": "Stored Phrase x.y.z"}
u16 BE     số mục N
lặp N lần:
  u8         loại: 1 = plain, 2 = layer-2 (mục có mật khẩu lớp 2, từ M4)
  u32 BE + … metadata JSON (UTF-8)
  u32 BE + … nội dung
```

Metadata JSON:

```json
{"label": "Zodl", "wordCount": 24, "hasPassphrase": false, "hasNotes": true,
 "hasBirthday": true, "createdAt": 0, "updatedAt": 0, "verifiedAt": null}
```

Với **loại 1**, nội dung là bản rõ của mục, đúng định dạng app dùng bên trong kho (*EntryCodec*):

```
u8          version (2; version 1 không có birthday)
u8          số từ n ∈ {12, 15, 18, 21, 24}
n × u16 BE  chỉ số từ trong wordlist BIP39 tiếng Anh (0..2047)
u16 BE + …  passphrase BIP39 (UTF-8)
u16 BE + …  ghi chú (UTF-8)
u8          1 nếu có birthday height, ngược lại 0          (version 2)
u32 BE      birthday height                                 (version 2, nếu có)
```

Với **loại 2**, nội dung là phong bì mật khẩu lớp 2, được mô tả ở mốc M4. Nội dung này vẫn đang được mã hoá bằng mật khẩu riêng của mục đó.

## Kiểm thử chéo

`tools/testdata/sample-v1.spbak` do code Kotlin (`BackupFormat`) tạo ra, với passphrase `correct-horse-battery-staple`. CI giải mã file này bằng cả hai bên:

- Kotlin: `BackupFormatTest.committedFixtureStillDecrypts`.
- Python: `tools/test_recover_backup.py`.

Hàm Argon2id của app còn được so với giá trị tham chiếu tính bằng argon2-cffi (bản cài đặt C chuẩn): `Argon2idKdfTest`.
