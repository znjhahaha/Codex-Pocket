"""Generate the original Pocket Outline icon family for Compose and React.

24-unit grid, 1.65-unit rounded strokes, quiet corners, shared geometry.
"""
from pathlib import Path
import json, re

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'design/icons'
OUT.mkdir(parents=True,exist_ok=True)
P={
'ChatBubbleOutline':['M7 4.5h10a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H10l-5.5 3V7.5a3 3 0 0 1 3-3','M8.5 9h7M8.5 13h4.5'],
'NotificationsNone':['M7 10a5 5 0 0 1 10 0v5l1.5 2H5.5L7 15z','M10 20h4M12 3v2'],
'Devices':['M12.5 16H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2M8 16v4M5.5 20h6','M16 10h4a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1M17.5 18.5h1'],
'Computer':['M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 15V7A2.5 2.5 0 0 1 6 4.5z','M3.5 13.5h17M12 17.5v3M8.5 20.5h7'],
'PhoneAndroid':['M8 3.5h8A2.5 2.5 0 0 1 18.5 6v12a2.5 2.5 0 0 1-2.5 2.5H8A2.5 2.5 0 0 1 5.5 18V6A2.5 2.5 0 0 1 8 3.5z','M10 6h4M11 18h2'],
'Add':['M12 5v14M5 12h14'],
'Close':['M6.5 6.5l11 11M17.5 6.5l-11 11'],
'ArrowBack':['M14.5 5.5L8 12l6.5 6.5M8 12h12'],
'ArrowDownward':['M12 4v16M5.5 13.5L12 20l6.5-6.5'],
'ArrowUpward':['M12 20V4M5.5 10.5L12 4l6.5 6.5'],
'ChevronRight':['M9 5.5l6.5 6.5L9 18.5'],
'ExpandLess':['M5.5 15L12 8.5l6.5 6.5'],
'ExpandMore':['M5.5 9L12 15.5 18.5 9'],
'Search':['M16.5 10a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0z','M15 15l5.5 5.5'],
'MoreHoriz':['M5.75 12a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0zM12.75 12a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0zM19.75 12a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0z'],
'Edit':['M14.5 5.5l4 4M5 15l11-11a2.8 2.8 0 0 1 4 4L9 19l-5 1z'],
'ContentCopy':['M9 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z','M15 4H5a2 2 0 0 0-2 2v10'],
'Description':['M7 3.5h7l5.5 5.5v9.5a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z','M14 3.5V9h5.5M8 13h8M8 16.5h5'],
'FolderOpen':['M4 9V6a2 2 0 0 1 2-2h4l2 3h6a2 2 0 0 1 2 2','M5 10h16l-2 9H3L1.5 10H5'],
'Image':['M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z','M4 16l5-5 5 5 3-3 3 3M16 8h.01'],
'PhotoCamera':['M7 6l2-2h6l2 2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z','M16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0z'],
'Terminal':['M5 4.5h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z','M7 9l3 3-3 3M13 15h4'],
'Code':['M8 6l-5 6 5 6M16 6l5 6-5 6M13 4l-2 16'],
'Checklist':['M3.5 7l2 2 3-4M12 7h8M3.5 16l2 2 3-4M12 16h8'],
'PlaylistAdd':['M4 6h16M4 11h10M4 16h6M18 14v7M14.5 17.5h7'],
'PlaylistPlay':['M4 6h16M4 11h10M4 16h6M16 14l6 3.5-6 3.5z'],
'PendingActions':['M8 4H6a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h5M16 4h2a2 2 0 0 1 2 2v3M9 3h6v4H9z','M22 16.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0zM16.5 13v3.5l2.5 1.5'],
'CheckCircle':['M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z','M8 12l3 3 5-6'],
'ErrorOutline':['M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z','M12 7.5v5.5M12 16.5h.01'],
'Info':['M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z','M12 10.5v6M12 7.5h.01'],
'RadioButtonUnchecked':['M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z'],
'PlayCircle':['M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z','M10 8l6 4-6 4z'],
'StopCircle':['M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z','M9 9h6v6H9z'],
'Lock':['M7.5 10V7.5a4.5 4.5 0 0 1 9 0V10','M6 10h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM12 14v3'],
'Link':['M10 8l2-2a4.2 4.2 0 0 1 6 6l-2 2M14 16l-2 2a4.2 4.2 0 0 1-6-6l2-2M8.5 15.5l7-7'],
'CallSplit':['M12 21V11M12 11L6 5M12 11l6-6M3 9V3h6M15 3h6v6'],
'SubdirectoryArrowRight':['M5 4v8a3 3 0 0 0 3 3h12M14 9l6 6-6 6'],
'Refresh':['M20 9a8 8 0 0 0-14-3L3 9M3 3v6h6M4 15a8 8 0 0 0 14 3l3-3M15 15h6v6'],
'Settings':['M5 4v5M5 14v6M12 4v9M12 18v2M19 4v2M19 11v9','M2.5 9h5v5h-5zM9.5 13h5v5h-5zM16.5 6h5v5h-5z'],
'Tune':['M3 7h8M16 7h5M3 17h4M12 17h9','M11 4.5h5v5h-5zM7 14.5h5v5H7z'],
'OpenInFull':['M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5'],
'CloseFullscreen':['M3 8h5V3M16 3v5h5M21 16h-5v5M8 21v-5H3'],
'PushPin':['M8 3h8l-1 7 3 3v2H6v-2l3-3z','M12 15v6'],
'DeleteOutline':['M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7'],
'Inventory2':['M4 4h16v4H4zM5 8v12h14V8M9 12h6'],
'QrCodeScanner':['M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5','M7 7h3v3H7zM14 7h3v3h-3zM7 14h3v3H7zM14 14h3v3h-3z'],
'AutoAwesome':['M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z','M19 3v3M17.5 4.5h3'],
'AlternateEmail':['M16 9v6a2.5 2.5 0 0 0 5 0v-3a9 9 0 1 0-3 6.7','M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z'],
'Check':['M5 12l4.5 4.5L19 7'],
'CheckCheck':['M2 12l4 4 9-9M11 15l2 2 9-9'],
'ArrowDownToLine':['M12 3v12M7 10l5 5 5-5M4 17v4h16v-4'],
'Moon':['M19.5 15.2A8.5 8.5 0 0 1 8.8 4.5a8.5 8.5 0 1 0 10.7 10.7z'],
'Sun':['M16.5 12a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0z','M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19'],
'Power':['M12 3v8M7 5a8 8 0 1 0 10 0'],
'ShieldCheck':['M12 3l8 3v6c0 4-4.5 7.5-8 9-3.5-1.5-8-5-8-9V6z','M8 12l3 3 5-6'],
'Wifi':['M3 8a14 14 0 0 1 18 0M6 12a9 9 0 0 1 12 0M9 16a4 4 0 0 1 6 0M12 20h.01'],
'Unplug':['M5 10l9 9M6.5 11.5l-1 1a4 4 0 0 0 6 6l1-1M11 6l1-1a4 4 0 0 1 6 6l-1 1M15 3l2 2M19 7l2 2M3 21l3-3M8 8l2-2M16 16l2-2M3 3l18 18'],
'LoaderCircle':['M20 12a8 8 0 1 1-8-8'],
}
aliases={'KeyboardArrowDown':'ExpandMore','Command':'Terminal','Monitor':'Computer','Smartphone':'PhoneAndroid','Plus':'Add','X':'Close','Link2':'Link','RefreshCw':'Refresh'}
for name,source in aliases.items(): P[name]=P[source]
(OUT/'paths.json').write_text(json.dumps(P,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
header='''package dev.codexpocket.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original Pocket Outline artwork. Generated by scripts/build-ui-icons.py. */
internal object PocketIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = name == "ArrowBack")
        paths.forEach { builder.addPath(pathData = PathParser().parsePathString(it).toNodes(), fill = null,
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        return builder.build()
    }
'''
for name,paths in P.items():header+=f'    val {name}: ImageVector by lazy {{ icon("{name}", '+', '.join(json.dumps(p) for p in paths)+') }\n'
header+='}\n'
ui=ROOT/'android/app/src/main/java/dev/codexpocket/app/ui'
(ui/'PocketIcons.kt').write_text(header,encoding='utf-8')
for file in ui.glob('*.kt'):
    if file.name=='PocketIcons.kt': continue
    text=file.read_text(encoding='utf-8')
    text=re.sub(r'Icons\.(?:AutoMirrored\.)?Outlined\.([A-Za-z0-9]+)',lambda m:'PocketIcons.'+m[1],text)
    text=re.sub(r'^import androidx\.compose\.material\.icons\..*\n','',text,flags=re.M)
    file.write_text(text,encoding='utf-8')
ts='''import {forwardRef,type ComponentType,type SVGProps} from 'react';
export type PocketIcon = ComponentType<SVGProps<SVGSVGElement> & {size?:number}>;
function icon(paths:string[]):PocketIcon{return forwardRef<SVGSVGElement,SVGProps<SVGSVGElement>&{size?:number}>(({size=24,strokeWidth=1.65,...props},ref)=><svg ref={ref} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths.map((d,i)=><path key={i} d={d}/>)}</svg>)}
'''
for name,paths in P.items():ts+=f'export const {name}=icon({json.dumps(paths)});\n'
(ROOT/'desktop/src/PocketIcons.tsx').write_text(ts,encoding='utf-8')
app=ROOT/'desktop/src/App.tsx';text=app.read_text(encoding='utf-8').replace("from 'lucide-react'","from './PocketIcons'").replace('LucideIcon','PocketIcon');app.write_text(text,encoding='utf-8')
def svg(paths): return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round">'+''.join(f'<path d="{p}"/>' for p in paths)+'</svg>'
names=['ChatBubbleOutline','NotificationsNone','Devices','Search','Edit','Settings','Add','ArrowUpward','StopCircle','PlaylistAdd','CallSplit','ContentCopy','PhotoCamera','Image','Description','FolderOpen','AutoAwesome','Checklist','Terminal','Code','Tune','MoreHoriz','Link','Refresh']
labels=['Chats','Attention','Devices','Search','New chat','Settings','Attach','Send','Stop','Queue','Branch','Copy','Camera','Photos','File','Folder','Model','Plan','Terminal','Code','Options','More','Link','Reconnect']
cards=''.join(f'<div class="cell">{svg(P[n])}<span>{l}</span></div>' for n,l in zip(names,labels))
html='''<!doctype html><meta charset="utf-8"><style>*{box-sizing:border-box}body{margin:0;background:#f7f7f6;color:#202123;font:14px "Segoe UI",sans-serif;padding:56px}h1{font-size:34px;letter-spacing:-1px;margin:0 0 8px;font-weight:600}p{color:#727272;margin:0 0 40px}.grid{display:grid;grid-template-columns:repeat(8,1fr);gap:10px}.cell{display:flex;flex-direction:column;align-items:center;gap:18px;padding:25px 8px;border-radius:16px;background:#fff}.cell svg{width:28px;height:28px}.cell span{font-size:12px;color:#777}.bar{margin-top:34px;padding:24px 32px;border-radius:22px;background:#202123;color:white;display:flex;gap:44px;align-items:center}.bar svg{width:24px;height:24px}.bar .selected{padding:12px;background:#414143;border-radius:14px}.bar b{margin-right:auto;font-weight:500}.sizes{margin-top:26px;display:flex;align-items:center;gap:22px;color:#686868}.sizes svg{width:100%;height:100%}</style><h1>Pocket Outline</h1><p>Original in-app icons · 24-unit grid · 1.65 stroke · shared across Android and desktop</p><div class="grid">'''+cards+'</div><div class="bar"><b>Codex Pocket</b><div class="selected">'+svg(P['ChatBubbleOutline'])+'</div>'+svg(P['NotificationsNone'])+svg(P['Devices'])+svg(P['Settings'])+'</div><div class="sizes">'+''.join(f'<span style="width:{s}px;height:{s}px;display:block">'+svg(P['Terminal'])+'</span>' for s in [16,20,24,32])+'<span>16 / 20 / 24 / 32 px</span></div>'
(OUT/'preview.html').write_text(html,encoding='utf-8')
print(f'Generated {len(P)} original vector icons for Compose/React and preview.')
