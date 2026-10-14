#!/usr/bin/env python3
"""Rebuild original train panel art, item icons and block-display wagon blueprints."""
from pathlib import Path
import json
import os
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources/assets/btr_infrastructure'

def find_font():
 env=os.environ.get('METRO_WORLD_FONT')
 if env and Path(env).is_file():return Path(env)
 for c in ['/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf','/usr/share/fonts/TTF/DejaVuSans-Bold.ttf','/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf','/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf','/usr/share/fonts/truetype/freefont/FreeSansBold.ttf','/System/Library/Fonts/Supplemental/Arial Bold.ttf','/Library/Fonts/Arial Bold.ttf','C:/Windows/Fonts/arialbd.ttf','C:/Windows/Fonts/seguiemb.ttf']:
  p=Path(c)
  if p.is_file():return p
 raise FileNotFoundError('TTF font not found. Please install fonts-dejavu-core or set METRO_WORLD_FONT.')

FONT=find_font()
def save_json(path,data):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def panel():
 # Draw at 2x for legible Cyrillic; bitmap provider scales to logical 176x222.
 im=Image.new('RGBA',(352,444),'#19242c');d=ImageDraw.Draw(im)
 def rect(box,fill,outline=None):d.rectangle(tuple(v*2 for v in box),fill=fill,outline=outline,width=2)
 def text(x,y,s,color='#d7e3df',size=5):d.text((x*2,y*2),s,font=ImageFont.truetype(str(FONT),size*2),fill=color)
 rect((1,1,174,220),'#24353e','#718d95');rect((4,4,171,12),'#101c24')
 text(7,5,'МЕТРО • ПУЛЬТ МАШИНИСТА',size=5)
 for x,label,value in [(8,'СКОРОСТЬ','км/ч'),(64,'ДАВЛЕНИЕ','бар'),(120,'СИСТЕМА','СТАТУС')]:
  rect((x,15,x+47,28),'#101d23','#45616a');text(x+3,17,label,size=4);text(x+3,23,value,'#70d6bd',size=4)
 labels={10:'ПИТАНИЕ',12:'КОМПРЕССОР',14:'РЕВЕРС',16:'ЭКСТР. СТОП',28:'ТЯГА −',29:'ТЯГА +',32:'ТОРМОЗ −',33:'ТОРМОЗ +',37:'ДВЕРИ Л',40:'ДВЕРИ П',43:'СВЕТ'}
 for slot,label in labels.items():
  row,col=divmod(slot,9);x=8+col*18;y=18+row*18
  rect((x-1,y-1,x+16,y+16),'#0b171e','#62818b');text(x-1,y-7,label,'#f0c780',size=3 if slot in (28,29,32,33) else 4)
 text(8,59,'РУКОЯТКИ УПРАВЛЕНИЯ',size=5)
 text(8,115,'ПОДСКАЗКИ И ПОКАЗАНИЯ — НА ПРИБОРАХ',size=4)
 rect((5,125,170,218),'#1c2931','#415760');text(8,128,'ИНВЕНТАРЬ',size=5)
 for row in range(3):
  for col in range(9):
   x=8+18*col;y=140+18*row;rect((x-1,y-1,x+16,y+16),'#101a22','#51616c')
 for col in range(9):
  x=8+18*col;rect((x-1,197,x+16,214),'#101a22','#718391')
 p=ROOT/'textures/gui/train_panel.png';p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
 save_json(ROOT/'font/train_panel.json',{'providers':[{'type':'space','advances':{'\ue001':-8}},{'type':'bitmap','file':'btr_infrastructure:gui/train_panel.png','ascent':13,'height':222,'chars':['\ue000']}]})
def icons():
 colors={'power':'#70deb3','compressor':'#75cdea','reverser':'#f0bf73','emergency':'#ec5e68','traction':'#7ad39d','brake':'#f3b762','doors':'#74bddd','lights':'#f0df8e','speed':'#87dfd1','pressure':'#b4b8ec'}
 for name,color in colors.items():
  im=Image.new('RGBA',(16,16));d=ImageDraw.Draw(im);d.rounded_rectangle((1,1,14,14),radius=2,fill='#23343d',outline='#98b2b6')
  if name=='power':d.arc((4,4,11,12),0,360,fill=color,width=2);d.line((8,3,8,7),fill=color,width=2)
  elif name=='doors':d.rectangle((4,3,11,12),outline=color);d.line((8,3,8,12),fill=color)
  elif name=='reverser':d.line((3,6,12,6),fill=color);d.polygon([(3,6),(6,3),(6,9)],fill=color);d.line((3,10,12,10),fill=color);d.polygon([(12,10),(9,7),(9,13)],fill=color)
  elif name=='emergency':d.rectangle((4,4,11,11),fill=color)
  elif name=='lights':d.ellipse((5,3,10,10),fill=color);d.rectangle((6,10,9,12),fill=color)
  elif name in ('speed','pressure'):d.arc((3,3,12,12),180,360,fill=color,width=2);d.line((8,10,11,5),fill=color,width=2)
  elif name=='compressor':d.rectangle((3,6,12,11),outline=color,width=2);d.line((5,3,5,6),fill=color);d.line((10,3,10,6),fill=color)
  elif name=='traction':d.polygon([(9,2),(4,8),(8,8),(6,13),(12,6),(8,6)],fill=color)
  else:d.ellipse((3,3,12,12),outline=color,width=2);d.line((5,8,10,8),fill=color,width=2)
  p=ROOT/f'textures/item/train_{name}.png';p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
  save_json(ROOT/f'models/item/train_{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'btr_infrastructure:item/train_{name}'}})
def wagon(name,accent,utility=False):
 parts=[]
 def add(material,pos,size,role):parts.append({'block':material,'position':pos,'scale':size,'role':role})
 add('minecraft:polished_deepslate',[-1.4,0,-6],[2.8,.25,12],'underframe')
 add('minecraft:smooth_quartz',[-1.4,.25,-6],[2.8,.2,12],'floor')
 for side in [-1.4,1.2]:
  add(accent,[side,.45,-6],[.2,.65,12],'lower_body')
  add('minecraft:white_concrete',[side,2.1,-6],[.2,.5,12],'upper_body')
  for start in [-5.7,-2.8,.1,3.0]:
   add('minecraft:light_blue_stained_glass',[side,1.1,start],[.2,1,1.8],'window')
   add('minecraft:white_concrete',[side,1.1,start+1.8],[.2,1,1.1],'door_or_pillar')
 add('minecraft:white_concrete',[-1.4,2.6,-6],[2.8,.25,12],'roof')
 for z in [-4.3,3.6]:
  add('minecraft:polished_blackstone',[-1.6,-.35,z],[3.2,.5,1.1],'bogie')
 for z in [-6,5.8]:
  add(accent,[-1.4,.45,z],[2.8,1.4,.2],'cab_end')
  add('minecraft:cyan_stained_glass',[-1.1,1.85,z],[2.2,.65,.2],'windscreen')
  add('minecraft:sea_lantern',[-1.15,.9,z-.02],[.3,.3,.24],'headlight')
  add('minecraft:sea_lantern',[.85,.9,z-.02],[.3,.3,.24],'headlight')
 if utility:
  for z in [-3,-1,1,3]:add('minecraft:barrel',[-1,.45,z],[.7,.9,.7],'equipment')
 else:
  for side in [-1.15,.65]:
   for z in [-4,-1.6,.8,3.2]:add('minecraft:blue_concrete',[side,.45,z],[.5,.6,1.4],'seat')
 add('minecraft:dark_oak_slab',[-.7,.8,-5.65],[1.4,.25,.6],'dashboard')
 save_json(ROOT/f'train_schematics/{name}.json',{'format':'metro-world:block-display-parts/v1','units':'blocks','axis':'X width, Y up, Z longitudinal; cab toward -Z','bounds':[-1.6,-.35,-6.02,1.6,2.85,6.02],'parts':parts})
if __name__=='__main__':
 panel();icons();wagon('passenger_compact','minecraft:cyan_concrete');wagon('utility_compact','minecraft:orange_concrete',True)
 print('Generated original train panel, 10 icons and 2 wagon blueprints')
