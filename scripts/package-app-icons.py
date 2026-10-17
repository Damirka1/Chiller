"""macOS-only packaging of the original Chiller PNG into ICNS and Windows PNG-entry ICO."""
from pathlib import Path
import struct
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'app-desktop/src/main/resources/chiller-icon.png'
output = root / 'app-desktop/icons'
output.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='chiller-icons-') as temporary:
    folder = Path(temporary)
    iconset = folder / 'Chiller.iconset'
    iconset.mkdir()
    def resize(size, destination):
        subprocess.run(['sips', '-z', str(size), str(size), str(source), '--out', str(destination)], check=True, stdout=subprocess.DEVNULL)
    for size in [16, 32, 128, 256, 512]:
        for scale in [1, 2]:
            resize(size * scale, iconset / (f'icon_{size}x{size}' + ('@2x' if scale == 2 else '') + '.png'))
    subprocess.run(['iconutil', '-c', 'icns', str(iconset), '-o', str(output / 'Chiller.icns')], check=True)
    entries = []
    for size in [16, 24, 32, 48, 64, 128, 256]:
        png = folder / f'win-{size}.png'
        resize(size, png)
        entries.append((size, png.read_bytes()))
    offset = 6 + 16 * len(entries)
    directory, body = b'', b''
    for size, data in entries:
        directory += struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
        body += data
        offset += len(data)
    (output / 'Chiller.ico').write_bytes(struct.pack('<HHH', 0, 1, len(entries)) + directory + body)
print('Chiller.icns and multi-resolution Chiller.ico generated')
