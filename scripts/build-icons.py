"""Render the original Pocket terminal mark to platform icon formats.

Only Pillow is required. SVG/VectorDrawable stay the canonical editable artwork.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "design" / "brand"
RES = ROOT / "android/app/src/main/res"
ART.mkdir(parents=True, exist_ok=True)
BG = "#202123"
FG = "#F8F8F7"
PATHS = ["M6,5.5 L6,14.8 Q6,16.4 7.5,17.2 L12,19.6 L16.5,17.2 Q18,16.4 18,14.8 L18,5.5", "M9,7.8 L12,10.5 L9,13.2", "M13.5,13.2 L15.7,13.2"]
def svg(background=False):
    rect = f'<rect width="24" height="24" rx="5.5" fill="{BG}"/>' if background else ''
    mark = ''.join(f'<path d="{d}"/>' for d in PATHS)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">{rect}<g fill="none" stroke="{FG if background else "currentColor"}" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round">{mark}</g></svg>\n'
(ART / "pocket-mark.svg").write_text(svg(), encoding="utf-8")
(ART / "app-icon.svg").write_text(svg(True), encoding="utf-8")

def points():
    pts=[(6,5.5),(6,14.8)]
    for i in range(1,17):
        t=i/16; pts.append(((1-t)**2*6+2*(1-t)*t*6+t*t*7.5,(1-t)**2*14.8+2*(1-t)*t*16.4+t*t*17.2))
    pts.extend([(12,19.6),(16.5,17.2)])
    for i in range(1,17):
        t=i/16; pts.append(((1-t)**2*16.5+2*(1-t)*t*18+t*t*18,(1-t)**2*17.2+2*(1-t)*t*16.4+t*t*14.8))
    pts.append((18,5.5));return [pts,[(9,7.8),(12,10.5),(9,13.2)],[(13.5,13.2),(15.7,13.2)]]
def raster(size=1024,rounding=True,light=False):
    scale=size*4/24; im=Image.new("RGBA",(size*4,size*4)); d=ImageDraw.Draw(im)
    d.rounded_rectangle((0,0,size*4-1,size*4-1),radius=5.5*scale if rounding else 0,fill=FG if light else BG)
    color=BG if light else FG; w=1.55*scale
    for p in points():
        xy=[(x*scale,y*scale) for x,y in p]
        d.line(xy,fill=color,width=round(w),joint="curve")
        for x,y in xy: d.ellipse((x-w/2,y-w/2,x+w/2,y+w/2),fill=color)
    return im.resize((size,size),Image.Resampling.LANCZOS)
icon=raster();icon.save(ART / "app-icon.png")
icons=ROOT/"desktop/src-tauri/icons";icons.mkdir(parents=True,exist_ok=True)
icon.save(icons/"icon.png");icon.save(icons/"icon.ico",sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
public=ROOT/"desktop/public";public.mkdir(exist_ok=True);(public/"pocket-mark.svg").write_text(svg(),encoding="utf-8")

def vector(paths,background=False,adaptive=False):
    transform='<group android:scaleX="0.625" android:scaleY="0.625" android:translateX="4.5" android:translateY="4.5">' if adaptive else '<group>'
    shape=f'<path android:fillColor="{BG}" android:pathData="M0,0h24v24h-24z"/>' if background else ''
    strokes=''.join(f'<path android:fillColor="@android:color/transparent" android:strokeColor="{FG}" android:strokeWidth="1.55" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{p}"/>' for p in paths)
    return '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="24" android:viewportHeight="24">'+shape+transform+strokes+'</group></vector>\n'
for name,data in [("ic_pocket.xml",vector(PATHS,True)),("ic_pocket_foreground.xml",vector(PATHS,adaptive=True)),("ic_pocket_notification.xml",vector(PATHS))]:
    (RES/"drawable"/name).write_text(data,encoding="utf-8")
for qualifier,mono in [("mipmap-anydpi-v26",False),("mipmap-anydpi-v33",True)]:
    dest=RES/qualifier;dest.mkdir(exist_ok=True)
    text='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/pocket_icon_background"/><foreground android:drawable="@drawable/ic_pocket_foreground"/>'+('<monochrome android:drawable="@drawable/ic_pocket_foreground"/>' if mono else '')+'</adaptive-icon>\n'
    (dest/"ic_launcher.xml").write_text(text,encoding="utf-8")
dest=RES/"mipmap-anydpi";dest.mkdir(exist_ok=True);(dest/"ic_launcher.xml").write_text(vector(PATHS,True),encoding="utf-8")
(RES/"values"/"icon_colors.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="pocket_icon_background">#202123</color></resources>\n',encoding="utf-8")
sheet=Image.new("RGB",(1200,600),"#F5F5F4");sheet.paste(Image.new("RGB",(600,600),"#171717"),(600,0))
for x,light in [(160,False),(760,True)]:sheet.paste(raster(280,light=light),(x,100),raster(280,light=light))
font=ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf",30)
d=ImageDraw.Draw(sheet);d.text((201,415),"Codex Pocket",font=font,fill=BG);d.text((801,415),"Codex Pocket",font=font,fill=FG)
for x in [220,820]:
    for j,s in enumerate([48,32,24]):
        small=raster(s,light=x>600);sheet.paste(small,(x+j*64,494+(48-s)//2),small)
sheet.save(ART/"icon-preview.png")
print("Rendered original Pocket icon: SVG, Android adaptive/monochrome, PNG and Windows ICO.")
