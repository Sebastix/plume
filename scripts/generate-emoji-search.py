#!/usr/bin/env python3
"""Regenerate the offline English emoji index (Unicode 17 / CLDR 48)."""
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CLDR = 'https://raw.githubusercontent.com/unicode-org/cldr/release-48/'
def fetch(url):
    with urlopen(url) as response:
        return response.read()

def key(text):
    return text.replace('\ufe0f', '')

names, keywords = {}, {}
for folder in ['annotations', 'annotationsDerived']:
    for item in ET.fromstring(fetch(CLDR + f'common/{folder}/en.xml')).iter('annotation'):
        target = names if item.get('type') == 'tts' else keywords
        target[key(item.attrib['cp'])] = item.text or ''

rows = []
for line in fetch('https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt').decode().splitlines():
    if '; fully-qualified' not in line:
        continue
    sequence, description = line.split('#', 1)
    emoji = ''.join(chr(int(cp, 16)) for cp in sequence.split(';')[0].split())
    fallback = description.strip().split(' ', 2)[2]
    name = names.get(key(emoji), fallback)
    words = keywords.get(key(emoji), '')
    rows.append('\t'.join([emoji, name, words]))

assets = ROOT / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
(assets / 'emoji-search.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
(assets / 'UNICODE-LICENSE.txt').write_bytes(fetch(CLDR + 'LICENSE'))
print(f'Wrote {len(rows)} emoji search entries')
