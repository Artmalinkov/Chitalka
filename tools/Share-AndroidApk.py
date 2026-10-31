"""Share only the current APK on the local Wi-Fi, with a time-limited download QR.

Run with the bundled Python runtime and --interface <computer's Wi-Fi IPv4>.
No device debugging or public upload is involved.
"""
import argparse
import hashlib
import http.server
import json
import secrets
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from pathlib import Path

from PIL import Image, ImageDraw
from reportlab.graphics.barcode.qr import QrCodeWidget


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--interface', required=True)
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    if not 30 <= args.timeout <= 1800:
        parser.error('timeout must be between 30 and 1800 seconds')
    root = Path(__file__).resolve().parent.parent
    local = root / '.local'
    local.mkdir(exist_ok=True)
    version = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot().get(
        '{http://schemas.android.com/apk/res/android}versionName')
    apk = root.parent / '_приложение' / f'Chitalka-{version}-debug.apk'
    data = apk.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    route = '/' + secrets.token_urlsafe(15) + '/' + apk.name
    now = lambda: datetime.now(timezone(timedelta(hours=3))).isoformat()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != route:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Content-Disposition', f'attachment; filename="{apk.name}"')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(data)
            except (ConnectionError, OSError):
                print('DOWNLOAD_INTERRUPTED', flush=True)
                return
            record = dict(servedAt=now(), clientAddress=self.client_address[0], version=version,
                          bytesServed=len(data), sha256=digest, installationConfirmed=False)
            (local / 'phone-apk-web-delivery.json').write_text(
                json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f'APK_SERVED: {self.client_address[0]} {apk.name}', flush=True)

        def log_message(self, *unused):
            pass

    server = http.server.ThreadingHTTPServer((args.interface, 0), Handler)
    server.daemon_threads = True
    server.timeout = 1
    url = f'http://{args.interface}:{server.server_port}{route}'
    qr = QrCodeWidget(url).qr
    qr.make()
    size, scale, border = qr.getModuleCount(), 10, 4
    image = Image.new('RGB', ((size + border * 2) * scale,) * 2, 'white')
    draw = ImageDraw.Draw(image)
    for row in range(size):
        for col in range(size):
            if qr.isDark(row, col):
                x, y = (col + border) * scale, (row + border) * scale
                draw.rectangle((x, y, x + scale - 1, y + scale - 1), fill='black')
    qr_path = local / 'android-apk-download-qr.png'
    image.save(qr_path)
    record = dict(startedAt=now(), url=url, version=version, sha256=digest,
                  validForSeconds=args.timeout, status='serving')
    session_path = local / 'apk-download-session.json'
    session_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
    print('DOWNLOAD_URL: ' + url, flush=True)
    print('QR_READY: ' + str(qr_path), flush=True)
    try:
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()
        record.update(status='stopped', stoppedAt=now())
        session_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
        print('SERVER_STOPPED', flush=True)


if __name__ == '__main__':
    main()
