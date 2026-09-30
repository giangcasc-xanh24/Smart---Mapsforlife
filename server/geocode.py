"""Tra địa chỉ theo toạ độ (reverse geocoding) cho tính năng chọn điểm bất kỳ trên bản đồ.
Mặc định dùng Nominatim (OpenStreetMap) — có bộ nhớ đệm và giới hạn 1 yêu cầu/giây theo chính sách sử dụng.
Có thể thay bằng máy chủ Nominatim riêng / dịch vụ tương thích tại Cấu hình → geocode.reverse_url."""
import json
import threading
import time
import urllib.parse
import urllib.request
from collections import OrderedDict

_cache = OrderedDict()
_lock = threading.Lock()
_last = [0.0]
CACHE_MAX = 3000
DEFAULT_URL = "https://nominatim.openstreetmap.org/reverse"


def _addr_text(a, display):
    parts = []
    hn, road = a.get("house_number"), a.get("road") or a.get("pedestrian") or a.get("footway") or a.get("path")
    if road:
        parts.append(f"{hn} {road}" if hn else road)
    for k in ("neighbourhood", "quarter", "suburb", "city_district"):
        if a.get(k) and a[k] not in parts:
            parts.append(a[k])
            break
    city = a.get("city") or a.get("town") or a.get("state")
    if city:
        parts.append(city)
    return ", ".join(parts) or (display or "")


def reverse(settings, lat, lng, lang="vi"):
    cfg = (settings.get("geocode") or {})
    if cfg.get("enabled") is False:
        return None
    key = (round(lat, 4), round(lng, 4), lang)
    with _lock:
        if key in _cache:
            _cache.move_to_end(key)
            return _cache[key]
    url = cfg.get("reverse_url") or DEFAULT_URL
    q = urllib.parse.urlencode({"format": "jsonv2", "lat": f"{lat:.6f}", "lon": f"{lng:.6f}", "zoom": 18,
                                "addressdetails": 1, "accept-language": "vi,en" if lang == "vi" else "en,vi"})
    with _lock:  # tối đa 1 yêu cầu/giây tới máy chủ công cộng
        wait = 1.05 - (time.time() - _last[0])
        if wait > 0:
            time.sleep(wait)
        _last[0] = time.time()
    try:
        req = urllib.request.Request(f"{url}?{q}", headers={"User-Agent": cfg.get("user_agent") or "Xanh24-MapsForLife/1.1 (+https://www.xanh24.com)"})
        with urllib.request.urlopen(req, timeout=float(cfg.get("timeout", 6))) as r:
            d = json.loads(r.read().decode("utf-8"))
    except Exception:
        return None
    if not isinstance(d, dict) or d.get("error"):
        res = {"name": "", "address": "", "road": ""}
    else:
        a = d.get("address") or {}
        name = d.get("name") or a.get("amenity") or a.get("shop") or a.get("building") or a.get("tourism") or ""
        res = {"name": name, "address": _addr_text(a, d.get("display_name")), "road": a.get("road") or "",
               "category": d.get("category") or "", "type": d.get("type") or "", "osm": f"{d.get('osm_type', '')}/{d.get('osm_id', '')}"}
    with _lock:
        _cache[key] = res
        while len(_cache) > CACHE_MAX:
            _cache.popitem(last=False)
    return res
