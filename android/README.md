# Xanh24 Kiosk — ứng dụng Android cho màn hình LCD tương tác

Ứng dụng mở bản đồ số **Xanh24 - Maps for Life** từ máy chủ (mặc định `https://www.xanh24.com`) ở chế độ kiosk toàn màn hình.

## Tính năng
- WebView toàn màn hình, ẩn thanh hệ thống, luôn bật màn hình, chặn nút Quay lại và chặn mở trang/ứng dụng bên ngoài.
- **Tự cập nhật dữ liệu:** mỗi phút hỏi `GET /api/public/version`; khi có dữ liệu mới (địa điểm được duyệt, quảng cáo, ranh giới, cấu hình…) trang tự nạp lại lúc kiosk về chế độ chờ — không cắt ngang người đang dùng.
- **Chạy mượt, chịu mất mạng:** bộ nhớ đệm (Service Worker) lưu giao diện, dữ liệu và ô bản đồ đã xem; mất mạng vẫn hiển thị bản đã lưu, có mạng lại tự đồng bộ.
- **Tự phục hồi:** mất kết nối → màn hình chờ kết nối và thử lại mỗi 15 giây; WebView bị dừng/treo → tự tạo lại; tải lại toàn bộ lúc 3 giờ sáng mỗi ngày.
- Tự chạy khi bật máy (đặt làm **Màn hình chính/Home** để chắc chắn trên Android 10+), tuỳ chọn khoá ứng dụng (Lock task).
- **Menu cài đặt ẩn:** chạm nhanh 5 lần vào góc trên bên phải → nhập PIN (mặc định `2424`) → đổi máy chủ, mã màn hình, PIN, xoá bộ nhớ đệm, thoát ứng dụng, cập nhật ứng dụng.
- **Vị trí đặt máy:** ứng dụng xin quyền Vị trí, lấy GPS/Wi-Fi và báo cho bản đồ ("Bạn đang ở đây"). Trong menu cài đặt: *Dùng vị trí GPS hiện tại* để ghim, hoặc nhập toạ độ thủ công (khuyến nghị với màn hình không có GPS); để trống = tự động.
- Cập nhật ứng dụng: khai báo `kiosk_app` trong cấu hình máy chủ (`version_code`, `version`, `apk_url`) → nút "Cập nhật" xuất hiện trong menu cài đặt.

## Cài đặt lên màn hình
1. Chép `Xanh24-Kiosk-1.1.0.apk` vào máy (USB) hoặc tải từ `https://<máy chủ>/downloads/Xanh24-Kiosk.apk`; cho phép *Cài ứng dụng không rõ nguồn gốc*.
2. Mở ứng dụng → **Cho phép** quyền Vị trí → nhập **Địa chỉ máy chủ** (VD `https://www.xanh24.com`) và **Mã màn hình** (VD `HK-01`, tạo ở Dashboard → Màn hình kiosk) → *Lưu & tải lại*.
3. Nhấn nút Home → chọn **Xanh24 Maps for Life** → *Luôn luôn* (đặt làm màn hình chính).
4. Tuỳ chọn: bật "Khoá ứng dụng trên màn hình" trong menu cài đặt (Android sẽ hỏi xác nhận ghim màn hình một lần).

Yêu cầu: Android 6.0+ (khuyến nghị 9+), Android System WebView/Chrome cập nhật (WebGL cho bản đồ), RAM ≥ 2 GB.

## Build
- **Android Studio:** mở thư mục `android/`, chọn *Build → Generate Signed APK* (dùng sẵn `keystore/`).
- **Không cần Gradle:** `./build_apk.sh` (xem biến môi trường ở đầu tệp).

## ⚠️ Khoá ký ứng dụng
`keystore/xanh24-kiosk-release.jks` + `keystore.properties` là **khoá ký phát hành**. Mọi bản cập nhật sau phải ký cùng khoá này (khác khoá sẽ không cài đè được). Lưu trữ an toàn, không đưa lên kho mã công khai (GitHub public) — đã thêm vào `.gitignore`.
