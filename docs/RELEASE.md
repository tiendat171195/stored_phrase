# Build bản release (để lưu seed thật)

Bản **debug** (`…storedphrase.debug`) ký bằng một key công khai nằm trong repo, nên **chỉ dùng với dữ liệu giả**. Seed thật phải nằm trong bản **release**, ký bằng key riêng mà chỉ bạn giữ.

## 1. Tạo key ký (chỉ làm một lần)

Cần JDK 17 trở lên. Chạy trên máy tính của bạn, **không** chạy trên máy cloud:

```bash
keytool -genkeypair -v -keystore ~/stored-phrase-release.jks -storetype PKCS12 -alias storedphrase -keyalg EC -groupname secp256r1 -validity 36500
```

- Đặt một mật khẩu mạnh cho keystore.
- **Sao lưu file `.jks` và mật khẩu** ra nơi an toàn (ví dụ USB offline). Nếu mất key này, bạn sẽ không bao giờ cập nhật được app. Khi đó buộc phải gỡ app, mà gỡ app thì mất khoá Knox Vault, và chỉ còn cách khôi phục từ file backup.
- Tuyệt đối không commit file `.jks`. `.gitignore` đã chặn `*.jks` và `keystore.properties`.

## 2. Khai báo key cho Gradle

Tạo file `keystore.properties` ở thư mục gốc repo:

```properties
storeFile=/home/ban/stored-phrase-release.jks
storePassword=mat-khau-keystore
keyAlias=storedphrase
keyPassword=mat-khau-keystore
```

## 3. Build và kiểm tra

```bash
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

Kiểm tra chữ ký: chứng chỉ in ra phải là của bạn, không phải `Stored Phrase Debug`.

```bash
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Kiểm tra quyền: kết quả không được có `android.permission.INTERNET`.

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer manifest permissions app/build/outputs/apk/release/app-release.apk
```

## 4. Cài lên máy

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Bạn cũng có thể chép file APK sang máy rồi mở nó.

- Bản release (`io.github.tiendat171195.storedphrase`) và bản debug là **hai app khác nhau**, cài song song được.
- Lần chạy đầu: chạy hết phần **Kiểm tra thiết bị**. Bản release từ chối tạo kho nếu không có StrongBox (Knox Vault).
- Sau khi nhập seed: tạo **file backup** và thử khôi phục bằng `tools/recover_backup.py`. Đồng thời vẫn giữ bản giấy.

## 5. Cập nhật lên phiên bản mới

1. Tăng `versionCode` và `versionName` trong `app/build.gradle.kts`.
2. Build bằng **đúng key cũ**.
3. Cài đè bằng `adb install -r`. Dữ liệu và khoá Knox Vault được giữ nguyên.

Nếu Android báo chữ ký không khớp, **đừng gỡ app** khi chưa có file backup mới nhất.

## Ghi chú

- Không có bản phát hành trên Google Play. Manifest có khai báo `uses-feature android.hardware.strongbox_keystore` (required), nên nếu sau này đưa lên Play thì Play sẽ chỉ hiện app cho máy có StrongBox.
- Có thể cho CI tự ký bản release bằng GitHub Secrets, nhưng khi đó GitHub giữ key của bạn. Với một kho seed, nên ký trên máy của chính mình.
