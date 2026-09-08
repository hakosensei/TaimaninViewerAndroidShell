from pathlib import Path
import base64, gzip, hashlib, re

# Mobile v1.0.6 translation-only overlay.
# Exact touch_addon.js is reconstructed from ASCII chunks to avoid accidental
# corruption by the repository transport layer.
parts=[]
for i in range(9):
    p=Path(f'touch_parts/t106x_{i:02d}.b64')
    if not p.is_file():
        raise SystemExit(f'missing payload chunk: {p}')
    parts.append(p.read_text(encoding='ascii').strip())
raw=gzip.decompress(base64.b64decode(''.join(parts)))
sha=hashlib.sha256(raw).hexdigest()
EXPECTED='1952ab6038b16bd56502801e1524632de7c4bfc6a1e235d50510a38af8d5e67f'
if sha!=EXPECTED:
    raise SystemExit(f'touch addon SHA256 mismatch: {sha}')

ASSET=Path('app/src/main/assets/touch_addon.js')
ASSET.parent.mkdir(parents=True,exist_ok=True)
ASSET.write_bytes(raw)
out=raw.decode('utf-8')

required=[
    '/v1/api/translations','hy-mt2-plus','glossary_ids','glossaryId',
    'hy4-preview','trPlusOne','Android 原生 TokenHub 代理连接失败',
    "wrap.style.display='none';b.textContent='确认重新直译'",
    "wrap.style.display='none';b.textContent='确认切句翻译'",
    '只发送给 Hy4 preview 的额外润色提示词',
]
for marker in required:
    if marker not in out:
        raise SystemExit(f'v1.0.6 required marker missing: {marker}')
for forbidden in [
    'hy-mt2-pro','Hy-MT2-Pro','PRO_LOCAL_GLOSSARY','MOBILE_GLOSSARY',
    'trMatchedTerms','trProOne'
]:
    if forbidden in out:
        raise SystemExit(f'v1.0.6 forbidden Pro/local-glossary marker remains: {forbidden}')

SRC=Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
s=SRC.read_text(encoding='utf-8')
s=s.replace('v1.0.4 Mobile · 原生翻译代理 / Translation落盘版',
            'v1.0.6 Mobile · Plus云端术语库 / 原生网络代理版')
s=s.replace('APP: v1.0.4 Mobile','APP: v1.0.6 Mobile')
s=s.replace('MODE: Mobile v1.0.4 + native TokenHub proxy + durable Translation cache + strict input gate',
            'MODE: Mobile v1.0.6 + Hy-MT2-Plus cloud glossary + native TokenHub proxy + durable Translation cache')
s=s.replace('TaimaninRPGXViewer-Mobile/1.0.3','TaimaninRPGXViewer-Mobile/1.0.6')
SRC.write_text(s,encoding='utf-8')

GRADLE=Path('app/build.gradle.kts')
g=GRADLE.read_text(encoding='utf-8')
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 106',g,count=1)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0.6"',g,count=1)
GRADLE.write_text(g,encoding='utf-8')

print('Installed Mobile v1.0.6 Plus cloud glossary translation')
print('touch_addon.js SHA256:',sha)
