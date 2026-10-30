"""Small disposable decode subprocess; stdout is a sanitized, bounded JSON result."""
import json
import re
import sys
import warnings
from pathlib import Path

from PIL import Image, ImageOps
from pypdf import PdfReader

MAX_TEXT = 60000


def image(source, mime, directory):
    Image.MAX_IMAGE_PIXELS = 24_000_000
    with warnings.catch_warnings():
        warnings.simplefilter('error', Image.DecompressionBombWarning)
        with Image.open(source) as picture:
            if {'JPEG': 'image/jpeg', 'PNG': 'image/png', 'WEBP': 'image/webp'}.get(picture.format) != mime:
                return {'error': 'TYPE_MISMATCH'}
            if picture.width * picture.height > 24_000_000 or max(picture.size) > 8192:
                return {'error': 'PIXEL_LIMIT'}
            if getattr(picture, 'n_frames', 1) != 1:
                return {'error': 'INVALID_MEDIA'}
            picture.verify()
        with Image.open(source) as picture:
            picture.load()
            normalized = ImageOps.exif_transpose(picture)
            # New RGB raster carries no source EXIF, ICC, comments, filenames or PNG metadata.
            clean = Image.new('RGB', normalized.size, 'white')
            if 'A' in normalized.getbands():
                rgba = normalized.convert('RGBA')
                clean.paste(rgba, mask=rgba.getchannel('A'))
            else:
                clean.paste(normalized.convert('RGB'))
            clean.save(directory / 'display.jpg', 'JPEG', quality=88, optimize=False)
            width, height = clean.size
            clean.thumbnail((512, 512))
            clean.save(directory / 'thumbnail.jpg', 'JPEG', quality=80)
    return {'metadata': {'mime_type': mime, 'width': width, 'height': height},
            'artifacts': [['display', 'image/jpeg', 'display.jpg'], ['thumbnail', 'image/jpeg', 'thumbnail.jpg']]}


def document(source, mime, directory):
    truncated = False
    if mime == 'application/pdf':
        with source.open('rb') as raw:
            if raw.read(5) != b'%PDF-':
                return {'error': 'TYPE_MISMATCH'}
        reader = PdfReader(source, strict=True)
        if reader.is_encrypted:
            return {'error': 'INVALID_MEDIA'}
        if len(reader.pages) > 200:
            return {'error': 'PAGE_LIMIT'}
        text = ''
        for page in reader.pages:
            # Reject embedded actions, external references and excessive page content.
            if any(key in page for key in ('/AA', '/AF')):
                return {'error': 'INVALID_MEDIA'}
            page_text = page.extract_text() or ''
            addition = ('\n' if text and page_text else '') + page_text
            remaining = MAX_TEXT - len(text)
            text += addition[:remaining]
            if len(addition) > remaining:
                truncated = True
                break
        text = text[:MAX_TEXT]
    else:
        raw = source.read_bytes()
        text = raw.decode('utf-8-sig')
        if ('\x00' in text or re.search(r'<\s*(?:!doctype\s+html|html|head|body|script|svg|iframe)\b', text, re.I)
                or raw.startswith((b'MZ', b'\x7fELF', b'#!', b'%PDF-', b'PK\x03\x04'))):
            return {'error': 'TYPE_MISMATCH'}
        truncated = len(text) > MAX_TEXT
        text = text[:MAX_TEXT]
    (directory / 'text.txt').write_text(text, encoding='utf-8')
    return {'metadata': {'mime_type': mime, 'text_available': bool(text), 'text_truncated': truncated},
            'artifacts': [['text', 'text/plain', 'text.txt']]}


def main():
    source, mime, directory = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    try:
        result = image(source, mime, directory) if mime.startswith('image/') else document(source, mime, directory)
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        result = {'error': 'PIXEL_LIMIT'}
    except Exception:
        result = {'error': 'INVALID_MEDIA'}
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
