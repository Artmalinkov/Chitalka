"""Pair the owner's Android device using the standard Android Studio QR format.

Run with the bundled Python runtime. zeroconf is installed locally in .local/qr-deps.
The QR secret stays in memory and the temporary PNG; logs never contain it.
"""
import argparse
import ipaddress
import json
import queue
import secrets
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw
from reportlab.graphics.barcode.qr import QrCodeWidget

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / '.local' / 'qr-deps'))
from zeroconf import IPVersion, ServiceBrowser, ServiceStateChange, Zeroconf

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PAIR_TYPE = '_adb-tls-pairing._tcp.local.'
CONNECT_TYPE = '_adb-tls-connect._tcp.local.'


def adb(*args, secret=None, timeout=12):
    return subprocess.run(
        [ADB, *args], input=(secret + '\n') if secret else None,
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--interface', required=True)
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--update', action='store_true')
    args = parser.parse_args()
    ipaddress.IPv4Address(args.interface)
    service_name = 'studio-' + secrets.token_hex(5)
    password = secrets.token_urlsafe(12)
    payload = f'WIFI:T:ADB;S:{service_name};P:{password};;'
    qr = QrCodeWidget(payload).qr
    qr.make()
    size, scale, border = qr.getModuleCount(), 12, 4
    image = Image.new('RGB', ((size + border * 2) * scale,) * 2, 'white')
    draw = ImageDraw.Draw(image)
    for row in range(size):
        for col in range(size):
            if qr.isDark(row, col):
                x, y = (col + border) * scale, (row + border) * scale
                draw.rectangle((x, y, x + scale - 1, y + scale - 1), fill='black')
    local = ROOT / '.local'
    local.mkdir(exist_ok=True)
    qr_path = local / 'android-pair-qr.png'
    image.save(qr_path)
    print(f'QR_READY: {qr_path}', flush=True)

    events = queue.Queue()

    def on_service(zeroconf, service_type, name, state_change):
        if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
            events.put((service_type, name))

    zc = Zeroconf(interfaces=[args.interface], ip_version=IPVersion.V4Only)
    browser = ServiceBrowser(zc, [PAIR_TYPE, CONNECT_TYPE], handlers=[on_service])
    paired_ip = None
    endpoints = set()
    attempted_pairs = set()
    deadline = time.monotonic() + args.timeout
    next_progress = time.monotonic() + 30
    record = {'status': 'waiting', 'paired': False, 'connected': False}
    try:
        while time.monotonic() < deadline:
            try:
                service_type, name = events.get(timeout=1)
                if service_type == PAIR_TYPE and name != service_name + '.' + PAIR_TYPE:
                    continue
                info = zc.get_service_info(service_type, name, timeout=2000)
                if not info:
                    continue
                for address in info.parsed_addresses(IPVersion.V4Only):
                    endpoint = f'{address}:{info.port}'
                    if service_type == CONNECT_TYPE:
                        endpoints.add((address, endpoint))
                    elif endpoint not in attempted_pairs:
                        attempted_pairs.add(endpoint)
                        print(f'QR_SCANNED: {endpoint}', flush=True)
                        try:
                            result = adb('pair', endpoint, secret=password)
                        except subprocess.TimeoutExpired:
                            print('PAIR_TIMEOUT: телефон не ответил.', flush=True)
                            continue
                        if result.returncode == 0 and 'Successfully paired' in result.stdout:
                            paired_ip = address
                            record.update(status='paired', paired=True)
                            print('PAIRED: сопряжение подтверждено.', flush=True)
                        else:
                            print('PAIR_FAILED: ' + result.stdout.strip() + result.stderr.strip(), flush=True)
            except queue.Empty:
                pass

            if paired_ip:
                for address, endpoint in sorted(endpoints):
                    if address != paired_ip:
                        continue
                    try:
                        adb('connect', endpoint)
                        state = adb('-s', endpoint, 'get-state', timeout=5)
                    except subprocess.TimeoutExpired:
                        continue
                    if state.returncode != 0 or state.stdout.strip() != 'device':
                        continue
                    model = adb('-s', endpoint, 'shell', 'getprop', 'ro.product.model').stdout.strip()
                    record.update(status='connected', connected=True, endpoint=endpoint, model=model)
                    print(f'CONNECTED: {model} ({endpoint})', flush=True)
                    if args.update:
                        update = subprocess.run([
                            'powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                            '-File', str(ROOT / 'tools' / 'Update-Android.ps1'),
                            '-DeviceSerial', endpoint, '-Build',
                        ])
                        record['updateExitCode'] = update.returncode
                        record['status'] = 'updated' if update.returncode == 0 else 'update_failed'
                    return 0 if record.get('updateExitCode', 0) == 0 else 1
            if time.monotonic() >= next_progress:
                print('WAITING: ожидается сканирование QR и доступность телефона в сети.' if not paired_ip
                      else 'WAITING: сопряжение выполнено, ожидается адрес подключения.', flush=True)
                next_progress = time.monotonic() + 30
        record['status'] = 'connection_timeout' if paired_ip else 'scan_timeout'
        print('TIMEOUT: соединение с телефоном не подтверждено.', flush=True)
        return 1
    finally:
        browser.cancel()
        zc.close()
        (local / 'qr-pair-last.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
        # QR credentials expire with this waiting session. Delete only our own PNG.
        qr_path.unlink(missing_ok=True)


if __name__ == '__main__':
    raise SystemExit(main())
