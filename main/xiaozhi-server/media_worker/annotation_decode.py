"""Disposable Pillow renderer for immutable annotation-v1 scenes. No network/URL APIs."""
import json
import math
import re
import sys
import warnings
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = {'format', 'frame_id', 'width', 'height', 'elements'}
FIELDS = {
    'arrow': {'id', 'type', 'color', 'stroke_width', 'points'},
    'rect': {'id', 'type', 'color', 'stroke_width', 'x', 'y', 'width', 'height'},
    'circle': {'id', 'type', 'color', 'stroke_width', 'cx', 'cy', 'radius'},
    'freehand': {'id', 'type', 'color', 'stroke_width', 'points'},
    'text': {'id', 'type', 'color', 'x', 'y', 'text', 'font_size'},
}


def invalid():
    raise ValueError('invalid annotation')


def number(value, lower=0, upper=1):
    if type(value) not in (int, float) or not math.isfinite(value) or not lower <= value <= upper:
        invalid()
    return value


def dimensions(metadata):
    width, height = metadata.get('expected_width'), metadata.get('expected_height')
    if (type(width) is not int or type(height) is not int or not 0 < width <= 8192
            or not 0 < height <= 8192 or width * height > 24_000_000):
        invalid()
    return width, height


def identifier(value):
    return (isinstance(value, str) and bool(re.fullmatch(r'[1-9][0-9]{0,18}', value))
            and int(value) <= 9223372036854775807)


def validate_scene(scene, metadata, font_path):
    """Independent second validation, including actual fixed-font pixel bounds."""
    width, height = dimensions(metadata)
    if (not isinstance(scene, dict) or set(scene) != ROOT or scene.get('format') != 'annotation-v1'
            or not identifier(scene.get('frame_id')) or scene['frame_id'] != metadata.get('frame_id')
            or type(scene.get('width')) is not int or type(scene.get('height')) is not int
            or (scene['width'], scene['height']) != (width, height)):
        invalid()
    if len(json.dumps(scene, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')) > 256 << 10:
        invalid()
    elements = scene['elements']
    if not isinstance(elements, list) or len(elements) > 200:
        invalid()
    used, points, chars = set(), 0, 0
    text_layouts, fonts = {}, {}
    for index, element in enumerate(elements):
        if not isinstance(element, dict) or element.get('type') not in FIELDS or set(element) != FIELDS[element['type']]:
            invalid()
        name, kind = element['id'], element['type']
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name) or name in used:
            invalid()
        used.add(name)
        if not isinstance(element['color'], str) or not re.fullmatch(r'#[0-9A-Fa-f]{6}', element['color']):
            invalid()
        if kind != 'text':
            number(element['stroke_width'], .001, .02)
        if kind in ('arrow', 'freehand'):
            shape_points = element['points']
            if not isinstance(shape_points, list) or not 2 <= len(shape_points) <= (2 if kind == 'arrow' else 2000):
                invalid()
            points += len(shape_points)
            for point in shape_points:
                if not isinstance(point, dict) or set(point) != {'x', 'y'}:
                    invalid()
                number(point['x']); number(point['y'])
        elif kind == 'rect':
            x, y = number(element['x']), number(element['y'])
            w, h = number(element['width']), number(element['height'])
            if w <= 0 or h <= 0 or x + w > 1 or y + h > 1:
                invalid()
        elif kind == 'circle':
            x, y = number(element['cx']) * width, number(element['cy']) * height
            radius = number(element['radius']) * min(width, height)
            if radius <= 0 or x - radius < 0 or y - radius < 0 or x + radius > width or y + radius > height:
                invalid()
        else:
            x, y = number(element['x']) * width, number(element['y']) * height
            size = max(1, round(number(element['font_size'], .005, .1) * min(width, height)))
            text = element['text']
            if not isinstance(text, str) or not 1 <= len(text) <= 1000 or any(0xD800 <= ord(c) <= 0xDFFF for c in text):
                invalid()
            chars += len(text)
            # A local configured font is the only font source. Scene data cannot select paths.
            if size not in fonts:
                fonts[size] = ImageFont.truetype(str(font_path), size=size)
            font = fonts[size]
            lines = text.split('\n')
            advance = size * 1.25
            for line_index, line in enumerate(lines):
                left, top, right, bottom = font.getbbox(line, anchor='lt')
                if x + left < 0 or y + line_index * advance + top < 0 or x + right > width or y + line_index * advance + bottom > height:
                    invalid()
            # Include blank lines in the line-box boundary check.
            if y + len(lines) * advance > height:
                invalid()
            text_layouts[index] = (font, lines, advance)
        if points > 20000 or chars > 8000:
            invalid()
    return text_layouts


def clean_image(source, expected):
    Image.MAX_IMAGE_PIXELS = 24_000_000
    with warnings.catch_warnings():
        warnings.simplefilter('error', Image.DecompressionBombWarning)
        with Image.open(source) as picture:
            if picture.format != 'JPEG' or getattr(picture, 'n_frames', 1) != 1:
                invalid()
            if picture.width * picture.height > 24_000_000 or max(picture.size) > 8192:
                invalid()
            picture.verify()
        with Image.open(source) as picture:
            picture.load()
            normalized = ImageOps.exif_transpose(picture)
            if normalized.size != expected:
                invalid()
            clean = Image.new('RGB', expected, 'white')
            clean.paste(normalized.convert('RGB'))
    return clean


def render(source, metadata, directory, font_path, annotate):
    expected = dimensions(metadata)
    layouts = validate_scene(metadata.get('scene'), metadata, font_path) if annotate else {}
    image = clean_image(source, expected)
    width, height = expected
    if annotate:
        draw = ImageDraw.Draw(image)
        for index, element in enumerate(metadata['scene']['elements']):
            kind, color = element['type'], element['color']
            stroke = max(1, round(element.get('stroke_width', .001) * min(expected)))
            if kind in ('arrow', 'freehand'):
                points = [(point['x'] * width, point['y'] * height) for point in element['points']]
                draw.line(points, fill=color, width=stroke, joint='curve')
                if kind == 'arrow':
                    start, end = points
                    dx, dy = end[0] - start[0], end[1] - start[1]
                    length = math.hypot(dx, dy)
                    if length:
                        unit_x, unit_y = dx / length, dy / length
                        head = min(length * .4, max(stroke * 4, min(expected) * .025))
                        left = (end[0] - unit_x * head + unit_y * head * .5,
                                end[1] - unit_y * head - unit_x * head * .5)
                        right = (end[0] - unit_x * head - unit_y * head * .5,
                                 end[1] - unit_y * head + unit_x * head * .5)
                        draw.polygon([end, left, right], fill=color)
            elif kind == 'rect':
                x, y = element['x'] * width, element['y'] * height
                draw.rectangle((x, y, x + element['width'] * width, y + element['height'] * height), outline=color, width=stroke)
            elif kind == 'circle':
                x, y = element['cx'] * width, element['cy'] * height
                radius = element['radius'] * min(expected)
                draw.ellipse((x - radius, y - radius, x + radius, y + radius), outline=color, width=stroke)
            else:
                font, lines, advance = layouts[index]
                x, y = element['x'] * width, element['y'] * height
                for line_index, line in enumerate(lines):
                    draw.text((x, y + line_index * advance), line, font=font, fill=color, anchor='lt')
    image.save(directory / 'display.jpg', 'JPEG', quality=90)
    image.thumbnail((512, 512))
    image.save(directory / 'thumbnail.jpg', 'JPEG', quality=80)
    return {'metadata': {'mime_type': 'image/jpeg', 'width': width, 'height': height},
            'artifacts': [['display', 'image/jpeg', 'display.jpg'], ['thumbnail', 'image/jpeg', 'thumbnail.jpg']]}


def main():
    try:
        mode, source, metadata_path, output, font_path = sys.argv[1:]
        if mode not in ('capture', 'annotation'):
            invalid()
        metadata = json.loads(Path(metadata_path).read_text(encoding='utf-8'))
        result = render(Path(source), metadata, Path(output), Path(font_path), mode == 'annotation')
    except Exception:
        result = {'error': 'INVALID_MEDIA'}
    print(json.dumps(result))


if __name__ == '__main__':
    main()
