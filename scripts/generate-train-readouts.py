#!/usr/bin/env python3
"""Original numeric instrument textures; the actual readings are selected by the server."""
from pathlib import Path
import json
import os
from PIL import Image,ImageDraw,ImageFont
root=Path(__file__).resolve().parents[1]/'src/main/resources/assets/btr_infrastructure'

def find_font():
 env=os.environ.get('METRO_WORLD_FONT')
 if env and Path(env).is_file():return Path(env)
 for c in ['/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf','/usr/share/fonts/TTF/DejaVuSans-Bold.ttf','/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf','/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf','/usr/share/fonts/truetype/freefont/FreeSansBold.ttf','/System/Library/Fonts/Supplemental/Arial Bold.ttf','/Library/Fonts/Arial Bold.ttf','C:/Windows/Fonts/arialbd.ttf','C:/Windows/Fonts/seguiemb.ttf']:
  p=Path(c)
  if p.is_file():return p
 raise FileNotFoundError('TTF font not found. Please install fonts-dejavu-core or set METRO_WORLD_FONT.')

font=ImageFont.truetype(str(find_font()),18)
for instrument,maximum in [('speed',44),('pressure',8),('traction',5),('brake',7)]:
 for value in range(maximum+1):
  name=f'train_{instrument}_{value}'
  image=Image.new('RGBA',(32,32),(12,20,25,255));draw=ImageDraw.Draw(image)
  draw.rounded_rectangle((0,0,31,31),radius=4,outline=(76,142,145,255),width=2)
  label=str(value);box=draw.textbbox((0,0),label,font=font);draw.text(((32-(box[2]-box[0]))/2,(32-(box[3]-box[1]))/2-box[1]),label,font=font,fill=(185,246,216,255))
  image.save(root/'textures/item'/f'{name}.png')
  (root/'models/item'/f'{name}.json').write_text(json.dumps({'parent':'minecraft:item/generated','textures':{'layer0':f'btr_infrastructure:item/{name}'}},indent=2)+'\n')
