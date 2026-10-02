"""CPU artifact check. Host-only dependencies: onnxruntime, numpy, Pillow, psutil."""
import argparse
import gc
import hashlib
import json
import threading
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import psutil
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('model', type=Path)
parser.add_argument('--kind', choices=('repair', 'color', 'detector', 'face'), required=True)
parser.add_argument('--photo', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
photo = np.asarray(Image.open(args.photo).convert('RGB').resize((512, 512)), np.float32) / 255
mask = np.zeros((1, 1, 512, 512), np.float32)
mask[:, :, 318:364, 294:340] = 1
mask[:, :, 140:440, 400:404] = 1

def rgb_to_lab(rgb):
    linear = np.where(rgb > .04045, ((rgb + .055) / 1.055) ** 2.4, rgb / 12.92)
    xyz = linear @ np.array([[.4124564, .2126729, .0193339], [.3575761, .7151522, .1191920], [.1804375, .0721750, .9503041]], np.float32)
    xyz /= np.array([.95047, 1., 1.08883], np.float32)
    f = np.where(xyz > 216/24389, np.cbrt(xyz), (24389/27 * xyz + 16) / 116)
    return np.stack((116*f[..., 1]-16, 500*(f[..., 0]-f[..., 1]), 200*(f[..., 1]-f[..., 2])), axis=-1)

def lab_to_rgb(lab):
    fy = (lab[..., 0] + 16) / 116
    f = np.stack((fy + lab[..., 1] / 500, fy, fy - lab[..., 2] / 200), axis=-1)
    xyz = np.where(f**3 > 216/24389, f**3, (116*f-16)/(24389/27)) * np.array([.95047,1.,1.08883],np.float32)
    linear = xyz @ np.array([[3.2404542,-.9692660,.0556434],[-1.5371385,1.8760108,-.2040259],[-.4985314,.0415560,1.0572252]],np.float32)
    rgb = np.where(linear > .0031308, 1.055*np.maximum(linear,0)**(1/2.4)-.055, linear*12.92)
    return np.clip(rgb,0,1)

def save(name, values):
    Image.fromarray(np.uint8(np.clip(values,0,1)*255+.5)).save(args.output / name)

if args.kind == 'repair':
    feed = {'image': np.ascontiguousarray(photo.transpose(2,0,1)[None] * (1-mask)), 'mask': mask}
    damaged = photo.copy()
    damaged[mask[0,0].astype(bool)] = 1
    save('damaged.png', damaged)
elif args.kind == 'color':
    lab = rgb_to_lab(photo)
    neutral = lab.copy()
    neutral[...,1:] = 0
    gray = lab_to_rgb(neutral)
    feed = {'input': np.ascontiguousarray(gray.transpose(2,0,1)[None])}
    save('gray.png', gray)
elif args.kind == 'face':
    crop = Image.open(args.photo).convert('RGB').crop((166,42,294,186)).resize((96,96))
    feed = {'input': np.ascontiguousarray(np.asarray(crop,np.float32).transpose(2,0,1)[None] / 255)}
else:
    resized = np.asarray(Image.open(args.photo).convert('RGB').resize((320,320)),np.float32)
    feed = {'input': np.ascontiguousarray(resized[...,::-1].transpose(2,0,1)[None])}

process = psutil.Process()
peak = [process.memory_info().rss]
stopped = threading.Event()
def watch_memory():
    while not stopped.wait(.02):
        peak[0] = max(peak[0], process.memory_info().rss)
watcher = threading.Thread(target=watch_memory, daemon=True)
watcher.start()
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
options.add_session_config_entry('session.intra_op.allow_spinning','0')
options.log_severity_level = 3
if args.kind == 'color':
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
started = time.perf_counter()
try:
    session = ort.InferenceSession(str(args.model), options, providers=['CPUExecutionProvider'])
    created = time.perf_counter()-started
    timings = []
    results = []
    for _ in range(2):
        start = time.perf_counter()
        results = session.run(None, feed)
        timings.append(time.perf_counter()-start)
        assert all(np.isfinite(value).all() for value in results), 'Non-finite model output'
    if args.kind == 'repair':
        assert results[0].shape == (1,3,512,512)
        rgb = np.clip(results[0][0].transpose(1,2,0) / 255,0,1)
        composite = photo.copy()
        selected = mask[0,0].astype(bool)
        composite[selected] = rgb[selected]
        assert np.array_equal(composite[~selected],photo[~selected])
        assert np.abs(composite[selected]-photo[selected]).mean() > .0001
        save('repaired.png', composite)
        np.save(args.output/'raw-output.npy',results[0])
    elif args.kind == 'color':
        assert results[0].shape == (1,2,512,512)
        composed = lab.copy()
        composed[...,1:] = results[0][0].transpose(1,2,0)
        assert np.abs(composed[...,1:]).mean() > 1, 'Model did not predict chroma'
        save('colorized.png',lab_to_rgb(composed))
    elif args.kind == 'face':
        assert results[0].shape == (1,3,384,384)
        save('face-detail.png',results[0][0].transpose(1,2,0))
    else:
        named = dict(zip([output.name for output in session.get_outputs()],results))
        scores = np.sqrt(np.clip(named['cls_8'],0,1)*np.clip(named['obj_8'],0,1))
        assert float(scores.max()) > .8, 'Real portrait must be detected'
    report = dict(model=args.model.name,sha256=hashlib.file_digest(args.model.open('rb'),'sha256').hexdigest(),
        runtime=ort.__version__,provider=session.get_providers(),threads=2,session_seconds=created,
        cpu_options='arena/pattern off, BASIC' if args.kind == 'color' else 'arena/pattern on, ALL',
        run_seconds=timings,peak_process_rss_bytes=peak[0],inputs={key:list(v.shape) for key,v in feed.items()},
        outputs=[dict(shape=list(value.shape),minimum=float(value.min()),maximum=float(value.max()),mean=float(value.mean())) for value in results])
    (args.output/'benchmark.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report),flush=True)
finally:
    stopped.set()
    watcher.join()
    gc.collect()
