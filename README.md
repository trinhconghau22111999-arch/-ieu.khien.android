# Remote Assist v2 — Hướng dẫn build

## Cấu trúc file
```
remote_assist_v2/
├── app/
│   ├── src/main/
│   │   ├── java/Com/hau/name/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ConsentActivity.kt        ← MÃ CỐ ĐỊNH + NÚT ĐỔI MÃ
│   │   │   ├── ControllerActivity.kt     ← LƯU MÃ CŨ, KẾT NỐI 1 BẤM
│   │   │   ├── RemoteHostService.kt      ← THÔNG BÁO PERSISTENT + AUDIO
│   │   │   ├── InputInjectionService.kt  ← TOUCH_DOWN/MOVE/UP + SCREEN_SIZE
│   │   │   ├── ControlCommandBus.kt      ← THÊM publishReply()
│   │   │   ├── ScreenMetrics.kt
│   │   │   ├── SystemAudioBus.kt         ← FILE MỚI: capture âm thanh hệ thống
│   │   │   └── webrtc/
│   │   │       ├── PeerConnectionManager.kt  ← AUDIO TRACK + CUSTOM ADM
│   │   │       └── SignalingClient.kt         ← startListening() + reconnect
│   │   ├── res/layout/
│   │   │   ├── activity_main.xml
│   │   │   ├── activity_consent.xml      ← NÚT TẠO MÃ MỚI
│   │   │   └── activity_controller.xml   ← CARD KẾT NỐI LẠI
│   │   ├── res/values/
│   │   │   ├── colors.xml
│   │   │   ├── strings.xml
│   │   │   └── themes.xml
│   │   ├── res/xml/
│   │   │   └── accessibility_service_config.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── google-services.json              ← BẠN TỰ THÊM (xem bên dưới)
├── build.gradle.kts
└── settings.gradle.kts
```

## Bước bắt buộc trước khi build

### 1. Thêm google-services.json
- Vào https://console.firebase.google.com
- Tạo project (hoặc dùng project cũ từ app gốc)
- Thêm Android app với package name: `Com.hau.name`
- Tải `google-services.json` về
- Đặt vào thư mục `app/` (cùng cấp với `build.gradle.kts`)

### 2. Bật Firebase Realtime Database
- Trong Firebase Console → Realtime Database → Create database
- Chọn "Start in test mode" (hoặc cấu hình rules riêng)

### 3. Bật Anonymous Auth (nếu app gốc dùng)
- Firebase Console → Authentication → Sign-in method → Anonymous → Enable

## Cách dùng

### Điện thoại (Máy B — bị điều khiển):
1. Mở app → chọn "Máy này bị điều khiển"
2. Lần đầu: tick đồng ý → bấm "Tạo mã & Bắt đầu chia sẻ" → cấp quyền màn hình
3. Mã 6 số hiện ra — mã này **cố định**, không đổi khi tắt/mở app
4. Bấm "🔄 Tạo mã mới" nếu muốn đổi mã
5. Thông báo persistent luôn hiện trên thanh thông báo khi đang chạy

### Máy tính bảng (Máy A — điều khiển):
1. Mở app → chọn "Máy này điều khiển"
2. **Nếu đã kết nối lần trước**: bấm vào card xanh "Kết nối lại XXXXXX" → vào luôn
3. **Lần đầu**: nhập mã 6 số → bấm Kết nối
4. Màn hình điện thoại hiện ra → chạm/vuốt để điều khiển
5. Âm thanh từ điện thoại phát qua loa máy tính bảng tự động

## Lưu ý kỹ thuật về Audio
File `google-services.json` từ app gốc vẫn dùng được nếu cùng package name.
Audio hệ thống (Android 10+) yêu cầu quyền `RECORD_AUDIO` — app sẽ xin khi cần.
Trên Android 9 trở xuống: chỉ stream video, không có audio.
