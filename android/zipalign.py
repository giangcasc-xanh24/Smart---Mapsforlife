"""zipalign tối giản: căn lề 4 byte cho mọi tệp STORED (bắt buộc với resources.arsc khi targetSdk ≥ 30)."""
import struct, sys, zipfile

def align(src, dst, n=4):
    zin = zipfile.ZipFile(src)
    with open(dst, 'wb') as out:
        zout = zipfile.ZipFile(out, 'w')
        for info in zin.infolist():
            data = zin.read(info.filename)
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type; ni.external_attr = info.external_attr
            if info.compress_type == zipfile.ZIP_STORED:
                off = out.tell() + 30 + len(info.filename.encode('utf-8'))
                pad = (n - off % n) % n
                if pad:
                    if pad < 4:
                        pad += n  # khối extra tối thiểu 4 byte (id + độ dài)
                    ni.extra = struct.pack('<HH', 0xD935, pad - 4) + b'\x00' * (pad - 4)
            zout.writestr(ni, data, compress_type=info.compress_type)
        zout.close()

if __name__ == '__main__':
    align(sys.argv[1], sys.argv[2])
