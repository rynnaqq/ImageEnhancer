"""Bundle previously verified model candidates as checked, Git-sized offline parts."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--candidates', type=Path, default=Path('.tools/model-candidates'))
parser.add_argument('--assets', type=Path, default=Path('app/src/main/assets'))
args = parser.parse_args()
manifest_path = args.assets/'models/manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
models = [model for model in manifest['models'] if model['id'] == 'espcn-x3']
models[0]['label'] = 'ESPCN super-resolution'
specs = [
    dict(id='yunet', label='YuNet face detection', candidate='yunet.onnx', bytes=229738,
         sha256='ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0',
         license='MIT', licenseAsset='licenses/YUNET-MIT.txt',
         source='https://github.com/opencv/opencv_zoo/tree/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_detection_yunet',
         input='float32 BGR NCHW [1,3,H,W], 0..255; preserve aspect and pad right/bottom to multiples of32',
         output='12 classification, objectness, box and five-landmark tensors at strides8,16,32',
         probeInputs=[dict(name='input',shape=[1,3,320,320],range=255)],
         requiredAvailableMemoryBytes=64*1024**2),
    dict(id='face-swinir', label='SwinIR face-region detail', candidate='face-swinir.onnx', bytes=57472188,
         sha256='c9b93e139b7d01e22041f8884cef6b80ff67fbc0f16f7fef21b0dfacc55ce041',
         license='Apache-2.0',licenseAsset='licenses/SWINIR-APACHE-2.0.txt',
         source='https://github.com/JingyunLiang/SwinIR/releases/tag/v0.0',
         upstreamRevision='6545850fbf8df298df73d81f3e8cba638787c8bd',
         checkpointSha256='b9afb61e65e04eb7f8aba5095d070bbe9af28df76acd0c9405aeb33b814bcfc6',
         converter='scripts/export-swinir.py',
         input='float32 RGB NCHW [1,3,96,96], 0..1; five-landmark aligned face region; mean handled inside graph',
         output='float32 RGB NCHW [1,3,384,384], clamp0..1 then inverse warp and feather against source',
         qualityStatus='General real-image neural detail enhancement on face regions; not an identity-aware face GAN',
         probeInputs=[dict(name='input',shape=[1,3,96,96])],
         requiredAvailableMemoryBytes=850*1024**2),
    dict(id='color-ddcolor',label='DDColor learned colorization',candidate='ddcolor-complete.onnx',bytes=135444402,
         sha256='2653da00dc15e54a45e5200b61dbf82ee9ceaf56b02bb9b9657569ac775e82e6',
         license='Apache-2.0',licenseAsset='licenses/DDCOLOR-APACHE-2.0.txt',
         source='https://huggingface.co/edgetools/ddcolor/tree/c4c98361d19eda29908cc960be39e8eeb44fc530',
         upstream='https://huggingface.co/piddnad/DDColor-models',
         input='float32 RGB NCHW [1,3,512,512], 0..1 neutral Lab gray; no ImageNet normalization',
         output='float32 Lab ab NCHW [1,2,512,512]; retain original source L, resize chroma, strength blend, original alpha',
         cpuOptions='Arena and memory pattern disabled; BASIC graph optimization; two threads',
         probeInputs=[dict(name='input',shape=[1,3,512,512])],
         requiredAvailableMemoryBytes=1200*1024**2),
    dict(id='repair-lama',label='LaMa scratch and region repair',candidate='lama-fp32-complete.onnx',bytes=208044816,
         sha256='1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6',
         license='Apache-2.0',licenseAsset='licenses/LAMA-APACHE-2.0.txt',
         source='https://huggingface.co/Carve/LaMa-ONNX/tree/c3c0c9e468934d62e79c329e35d82dd09ff8c444',
         upstream='https://github.com/advimman/lama',
         input='float32 RGB image [1,3,512,512]0..1 and mask [1,1,512,512]1=hole; source-context ROI',
         output='float32 RGB [1,3,512,512]0..255; composite only selected original pixels, retain original alpha',
         qualityStatus='FP32 selected instead of quantized OpenCV derivative after real-output comparison',
         probeInputs=[dict(name='image',shape=[1,3,512,512]),dict(name='mask',shape=[1,1,512,512])],
         requiredAvailableMemoryBytes=1150*1024**2),
]

for spec in specs:
    candidate = args.candidates/spec.pop('candidate')
    with candidate.open('rb') as source:
        actual = hashlib.file_digest(source,'sha256').hexdigest()
    if candidate.stat().st_size != spec['bytes'] or actual != spec['sha256']:
        raise SystemExit('Unverified candidate: '+spec['id'])
    spec['file'] = spec['id']+'.onnx'
    spec['backends'] = ['CPU']
    spec['version'] = 'Bundled restoration bank v0.2.0'
    if spec['bytes'] <= 32*1024**2:
        shutil.copyfile(candidate,args.assets/'models'/spec['file'])
    else:
        parts = []
        with candidate.open('rb') as source:
            index = 0
            while chunk := source.read(32*1024**2):
                filename = spec['id']+f'-{index:02d}.bin'
                (args.assets/'models'/filename).write_bytes(chunk)
                parts.append(dict(file=filename,bytes=len(chunk),sha256=hashlib.sha256(chunk).hexdigest()))
                index += 1
        spec['parts'] = parts
    models.append(spec)
    print('Bundled '+spec['id']+' '+str(spec['bytes'])+' bytes')

manifest_path.write_text(json.dumps(dict(schemaVersion=2,models=models),indent=2)+'\n',encoding='utf-8')
