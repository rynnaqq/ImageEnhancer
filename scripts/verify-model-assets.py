"""Verify the actual offline artifact bank, including checked split assets."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('assets', nargs='?', type=Path, default=Path('app/src/main/assets'))
args = parser.parse_args()
manifest = json.loads((args.assets/'models/manifest.json').read_text(encoding='utf-8'))
required = {'espcn-x3', 'yunet', 'face-swinir', 'color-ddcolor', 'repair-lama'}
actual = {model['id'] for model in manifest['models']}
missing = required-actual
if missing:
    raise SystemExit('Missing bundled restoration models: ' + ', '.join(sorted(missing)))

def owned(path):
    target = (args.assets/path).resolve()
    if not target.is_relative_to(args.assets.resolve()):
        raise ValueError('Asset path escapes bundle')
    return target

for model in manifest['models']:
    total = 0
    digest = hashlib.sha256()
    for part in model.get('parts', [model]):
        path = owned('models/'+part['file'])
        part_digest = hashlib.sha256()
        count = 0
        with path.open('rb') as stream:
            for buffer in iter(lambda: stream.read(1024*1024), b''):
                count += len(buffer)
                part_digest.update(buffer)
                digest.update(buffer)
        if count != part['bytes'] or part_digest.hexdigest() != part['sha256']:
            raise SystemExit('Corrupt/missing model part: ' + model['id'])
        total += count
    if total != model['bytes'] or digest.hexdigest() != model['sha256']:
        raise SystemExit('Corrupt assembled model: ' + model['id'])
    if not owned(model['licenseAsset']).is_file():
        raise SystemExit('Missing bundled license: ' + model['id'])
    print(f"Verified {model['id']}: {total} bytes, SHA-256 {digest.hexdigest()}")
print('All enhancement/restoration models and licenses are bundled.')
