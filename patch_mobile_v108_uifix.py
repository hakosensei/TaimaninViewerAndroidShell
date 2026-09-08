from pathlib import Path
p=Path('app/src/main/assets/touch_addon.js')
s=p.read_text(encoding='utf-8')
old='<div id="__ta_translate_status" class="__ta_status" style="color:#facc15;opacity:1">⚠ 在线翻译会调用云端模型并消耗 Token · Pro 主翻译 · Hy4 润色 · Plus 机翻</div>'
new='<div id="__ta_translate_status" class="__ta_status warn">⚠ 在线翻译会调用云端模型并消耗 Token · Pro 主翻译 · Hy4 润色 · Plus 机翻</div>'
if old not in s: raise SystemExit('translation warning marker missing')
s=s.replace(old,new,1)
css='.__ta_status.bad{color:#ff7373;opacity:1;font-weight:bold}'
if css not in s: raise SystemExit('bad status css missing')
s=s.replace(css,'.__ta_status.warn:not(.bad){color:#facc15;opacity:1}\n'+css,1)
p.write_text(s,encoding='utf-8')
print('Mobile V1.0 status colors: warning yellow, errors red')
