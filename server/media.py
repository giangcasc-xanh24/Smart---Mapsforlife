"""Lưu trữ tệp tải lên: ảnh (tự tạo bản thu nhỏ), video, ảnh 360."""
import io
import os
import uuid

from PIL import Image, ImageOps

from db import UPLOAD_DIR

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
VIDEO_EXT = {".mp4", ".webm", ".m4v", ".mov"}
MAX_IMAGE_SIDE = 2400
MAX_PANO_SIDE = 8192
THUMB_SIDE = 480


def _paths(ext, sub=""):
    from datetime import datetime
    d = datetime.now()
    rel = os.path.join(sub or f"{d:%Y}", f"{d:%m}")
    os.makedirs(os.path.join(UPLOAD_DIR, rel), exist_ok=True)
    name = uuid.uuid4().hex[:16]
    return rel, name


def save_image_bytes(data, kind="image", sub=""):
    """Chuẩn hoá ảnh: xoay theo EXIF, giới hạn kích thước, JPEG/PNG, tạo thumbnail.
    kind='pano' giữ độ phân giải cao cho ảnh 360 (tỉ lệ 2:1)."""
    im = Image.open(io.BytesIO(data))
    im = ImageOps.exif_transpose(im)
    has_alpha = im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info)
    limit = MAX_PANO_SIDE if kind == "pano" else MAX_IMAGE_SIDE
    if max(im.size) > limit:
        im.thumbnail((limit, limit), Image.LANCZOS)
    rel, name = _paths(".jpg", sub)
    if has_alpha and kind != "pano":
        fn = name + ".png"
        im.save(os.path.join(UPLOAD_DIR, rel, fn), optimize=True)
    else:
        fn = name + ".jpg"
        im.convert("RGB").save(os.path.join(UPLOAD_DIR, rel, fn), quality=85, optimize=True, progressive=True)
    th = im.copy()
    th.thumbnail((THUMB_SIDE, THUMB_SIDE), Image.LANCZOS)
    tfn = name + "_t.jpg"
    th.convert("RGB").save(os.path.join(UPLOAD_DIR, rel, tfn), quality=80)
    base = "/uploads/" + rel.replace(os.sep, "/") + "/"
    return {"url": base + fn, "thumb": base + tfn, "w": im.size[0], "h": im.size[1]}


def save_file_bytes(data, ext, sub=""):
    rel, name = _paths(ext, sub)
    fn = name + ext
    with open(os.path.join(UPLOAD_DIR, rel, fn), "wb") as f:
        f.write(data)
    return {"url": "/uploads/" + rel.replace(os.sep, "/") + "/" + fn}


def save_upload(data, filename, kind="image"):
    ext = os.path.splitext(filename or "")[1].lower()
    if ext in IMAGE_EXT:
        return save_image_bytes(data, kind=kind)
    if ext in VIDEO_EXT:
        return save_file_bytes(data, ext)
    raise ValueError("Định dạng tệp không được hỗ trợ: " + (ext or "?"))
