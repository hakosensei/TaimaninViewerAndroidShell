from pathlib import Path
import re

p=Path('app/src/main/assets/touch_addon.js')
s=p.read_text(encoding='utf-8')
replacement=r'''function trReplace(el,zh){if(!el||!zh)return;zh=trClean(zh);const now=trClean(el.innerText||el.textContent||'');if(el.dataset?.taTrZh===zh&&now===zh)return;const source=trSource(el);if(!source)return;if(el.dataset.taTrZh&&now!==el.dataset.taTrZh){delete el.dataset.taTrHtml;delete el.dataset.taTrJa;delete el.dataset.taTrZh;}if(!el.dataset.taTrHtml){el.dataset.taTrHtml=el.innerHTML;el.dataset.taTrJa=source;}el.dataset.taTrZh=zh;el.innerHTML=String(zh).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\r?\n/g,'<br>');}'''
s2,n=re.subn(r'function trReplace\(el,zh\)\{.*?\}(?=\nfunction trRestoreEl)',lambda m: replacement,s,count=1,flags=re.S)
if n!=1:
    raise SystemExit(f'trReplace repair marker count={n}')
p.write_text(s2,encoding='utf-8')
print('Repaired literal JS regex escaping in trReplace')
