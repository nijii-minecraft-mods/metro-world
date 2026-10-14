#!/usr/bin/env python3
"""Import saved TrainCarts YAML and a local resource-pack ZIP, without executing either."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import stat
import struct
import sys
import tempfile
import zipfile

try:
    import yaml
except ImportError:
    yaml = None

SCHEMA = "metro-world.train-model.v1"
MAX_ZIP_BYTES = 256 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_NODES = 4096
RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")


class ImportFailure(ValueError):
    pass


def number(value, where, limit=1_000_000):
    if isinstance(value, bool):
        raise ImportFailure(f"{where}: expected a finite number")
    try:
        result = float(value)
    except (ValueError, TypeError) as exc:
        raise ImportFailure(f"{where}: expected a finite number") from exc
    if not math.isfinite(result) or abs(result) > limit:
        raise ImportFailure(f"{where}: number outside safe bounds")
    return result


def boolean(value, where):
    if isinstance(value, bool):
        return value
    if value in (0, 1, "0", "1", "true", "false", "TRUE", "FALSE", "on", "off", "ON", "OFF"):
        return str(value).lower() in ("1", "true", "on")
    raise ImportFailure(f"{where}: expected boolean")


def mapping(value, where):
    if not isinstance(value, dict):
        raise ImportFailure(f"{where}: expected mapping")
    return value


def ordered(value, where):
    if value is None:
        return []
    if isinstance(value, list):
        return list(enumerate(value))
    if isinstance(value, dict):
        try:
            keys = sorted(value, key=lambda key: int(key))
            if any(str(key) != str(int(key)) or int(key) < 0 for key in keys):
                raise ValueError()
            return [(str(key), value[key]) for key in keys]
        except (TypeError, ValueError) as exc:
            raise ImportFailure(f"{where}: expected list or nonnegative integer-keyed mapping") from exc
    raise ImportFailure(f"{where}: expected list or integer-keyed mapping")


def load_yaml(path):
    if yaml is None:
        raise ImportFailure("PyYAML is required: python3 -m pip install -r scripts/train-model-requirements.txt")
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ImportFailure("YAML exceeds 16 MiB")
    text = path.read_text(encoding="utf-8-sig")
    # Reject preprocessor source templates: a generated savedTrainModules YAML is required.
    if re.search(r"^\s*#\s*(include|define|if|ifdef|ifndef)\b", text, re.M):
        raise ImportFailure("Preprocessor template detected; provide built saved-train YAML, not .yml.in")
    depth=0
    count=0
    for event in yaml.parse(text, Loader=yaml.SafeLoader):
        count+=1
        if isinstance(event,(yaml.events.MappingStartEvent,yaml.events.SequenceStartEvent)):
            depth+=1
        elif isinstance(event,(yaml.events.MappingEndEvent,yaml.events.SequenceEndEvent)):
            depth-=1
        if depth>72 or count>250000:
            raise ImportFailure("YAML tree size/depth limit exceeded")
        if isinstance(event, yaml.events.AliasEvent):
            raise ImportFailure("YAML aliases are disabled; expand anchors before importing")
    class Loader(yaml.SafeLoader):
        pass
    def unique(loader, node, deep=False):
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise ImportFailure(f"Duplicate YAML key: {key!r}")
            result[key] = loader.construct_object(value_node, deep=deep)
        return result
    Loader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique)
    return mapping(yaml.load(text, Loader=Loader), "saved trains")


def resource_id(value):
    value = str(value)
    if ":" not in value:
        value = "minecraft:" + value
    if not RESOURCE_ID.fullmatch(value) or any(part in (".", "..") for part in value.split(":", 1)[1].split("/")):
        raise ImportFailure(f"Invalid resource identifier: {value}")
    return value


TOOL_DURABILITY = {
    "wooden_sword": 59, "stone_sword": 131, "iron_sword": 250, "golden_sword": 32, "diamond_sword": 1561, "netherite_sword": 2031,
    "wooden_pickaxe": 59, "stone_pickaxe": 131, "iron_pickaxe": 250, "golden_pickaxe": 32, "diamond_pickaxe": 1561, "netherite_pickaxe": 2031,
    "wooden_shovel": 59, "stone_shovel": 131, "iron_shovel": 250, "golden_shovel": 32, "diamond_shovel": 1561, "netherite_shovel": 2031,
    "wooden_axe": 59, "stone_axe": 131, "iron_axe": 250, "golden_axe": 32, "diamond_axe": 1561, "netherite_axe": 2031,
    "wooden_hoe": 59, "stone_hoe": 131, "iron_hoe": 250, "golden_hoe": 32, "diamond_hoe": 1561, "netherite_hoe": 2031,
    "shears": 238, "fishing_rod": 64, "flint_and_steel": 64, "carrot_on_a_stick": 25, "warped_fungus_on_a_stick": 100,
    "shield": 336, "bow": 384, "crossbow": 465, "trident": 250, "mace": 500,
}


class ResourcePack:
    def __init__(self, path):
        if path.stat().st_size > MAX_ZIP_BYTES:
            raise ImportFailure("Resource pack exceeds 256 MiB")
        self.sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        self.files = {}
        self.models = {}
        self.diagnostics = []
        total = 0
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 10000:
                raise ImportFailure("Too many resource-pack entries")
            for entry in archive.infolist():
                name = entry.filename
                p = PurePosixPath(name)
                if p.is_absolute() or "\\" in name or "\x00" in name or any(part in ("", ".", "..") for part in name.rstrip("/").split("/")):
                    raise ImportFailure(f"Unsafe ZIP path: {name!r}")
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise ImportFailure(f"Nonregular ZIP entry: {name}")
                if entry.is_dir():
                    continue
                if name in self.files:
                    raise ImportFailure(f"Duplicate ZIP path: {name}")
                total += entry.file_size
                if entry.file_size > MAX_FILE_BYTES or total > MAX_ZIP_BYTES:
                    raise ImportFailure("Resource-pack decompressed size limit exceeded")
                if entry.file_size > max(1, entry.compress_size) * 1000:
                    raise ImportFailure(f"Excessive compression ratio: {name}")
                data = archive.read(entry)
                self.files[name] = data
                match = re.fullmatch(r"assets/([a-z0-9_.-]+)/models/(.+)\.json", name)
                if match:
                    model = json.loads(data)
                    self.models[resource_id(match[1]+":"+match[2])] = mapping(model, name)
        if "pack.mcmeta" not in self.files:
            raise ImportFailure("ZIP must contain pack.mcmeta and assets at its root")
        metadata = mapping(json.loads(self.files["pack.mcmeta"]), "pack.mcmeta")
        pack_format = mapping(metadata.get("pack"), "pack.mcmeta.pack").get("pack_format")
        if pack_format != 34:
            self.diagnostics.append({"level": "warning", "path": "pack.mcmeta", "code": "PACK_FORMAT", "message": f"Source pack_format={pack_format}; output targets Minecraft 1.21.1 (34). New item-model systems or shader changes are not converted."})
        if any("/shaders/" in name for name in self.files):
            self.diagnostics.append({"level": "warning", "path": "resource-pack", "code": "SHADERS", "message": "Shader assets are preserved but compatibility is not guaranteed; no shader conversion is performed."})

    def resolve_item(self, item_id, cmd, damage, unbreakable, diagnostics, where):
        namespace, name = item_id.split(":", 1)
        base = f"{namespace}:item/{name}"
        selected = base
        model = self.models.get(base)
        if model:
            predicates = {"custom_model_data": cmd, "damaged": 0 if unbreakable or damage == 0 else 1}
            durability = TOOL_DURABILITY.get(name)
            if durability:
                predicates["damage"] = min(1, damage / durability)
            for override in model.get("overrides", []):
                predicate = mapping(override.get("predicate", {}), where+".predicate")
                if any(key not in predicates for key in predicate):
                    diagnostics.append({"level": "error", "path": where, "code": "ITEM_PREDICATE", "message": "Unsupported item override predicate: "+", ".join(key for key in predicate if key not in predicates)})
                    continue
                if all(struct.unpack("!f",struct.pack("!f",predicates[key]))[0] >= struct.unpack("!f",struct.pack("!f",number(value, where+".predicate."+key,2147483647)))[0] for key,value in predicate.items()):
                    selected = resource_id(override["model"])
        if cmd and selected == base:
            diagnostics.append({"level": "error", "path": where, "code": "MODEL_NOT_RESOLVED", "message": f"No custom-model-data override resolved for {item_id} CMD {cmd}"})
        if selected != base and selected not in self.models:
            diagnostics.append({"level": "error", "path": where, "code": "MISSING_MODEL", "message": "Referenced custom model is absent: "+selected})
        return selected


def vector(data, prefix, default, where):
    return [number(data.get(prefix+axis, default), where+"."+prefix+axis) for axis in "XYZ"]


def normalise_animations(data, where, diagnostics):
    result = {}
    for name, animation in mapping(data, where).items():
        animation = mapping(animation, where+"."+str(name))
        frames = []
        nodes = animation.get("nodes", [])
        if not isinstance(nodes, list) or len(nodes) > 4096:
            raise ImportFailure(where+": animation nodes must be a bounded list")
        for index, node in enumerate(nodes):
            fields = {}
            if not isinstance(node, str):
                raise ImportFailure(where+": animation nodes must use TrainCarts key=value strings")
            for token in shlex.split(node):
                if "=" not in token:
                    raise ImportFailure(where+": malformed animation token "+token)
                key,value = token.split("=",1)
                if key in fields:
                    raise ImportFailure(where+": duplicate animation field "+key)
                fields[key] = value
            for key in fields.keys()-{"t","x","y","z","pitch","yaw","roll","active","scene"}:
                diagnostics.append({"level":"error","path":where,"code":"ANIMATION_FIELD","message":"Unsupported animation field: "+key})
            duration=number(fields.get("t",1),where+".duration")
            if duration<0 or (duration==0 and index!=len(nodes)-1):
                raise ImportFailure(where+": only a final animation endpoint may have t=0")
            frames.append({"duration":duration,"translation":[number(fields.get(k,0),where+"."+k) for k in ("x","y","z")],"rotation":[number(fields.get(k,0),where+"."+k) for k in ("pitch","yaw","roll")],"active":boolean(fields.get("active",True),where+".active"),"scene":fields.get("scene")})
        result[str(name)]={"frames":frames,"speed":number(animation.get("speed",1),where+".speed"),"delay":number(animation.get("delay",0),where+".delay"),"looped":boolean(animation.get("looped",False),where+".looped"),"autoplay":boolean(animation.get("autoplay",False),where+".autoplay"),"movementControlled":boolean(animation.get("movementControlled",False),where+".movementControlled")}
    return result


def item_display(transform, diagnostics, where):
    transform = str(transform).upper()
    modes = {"HEAD": ("head",.625,.25),"HYBRID_ARMORSTAND_HEAD":("head",.625,.25),"HYBRID_DISPLAY_HEAD":("head",.625,0),"HYBRID_ARMORSTAND_HEAD_SMALL":("head",.4375,.175),"HYBRID_DISPLAY_HEAD_SMALL":("head",.4375,0),"DISPLAY_HEAD":("head",1,0),"DISPLAY_NONE":("none",1,0),"NONE":("none",1,0),"FIXED":("fixed",1,0),"DISPLAY_FIXED":("fixed",1,0),"GUI":("gui",1,0),"GROUND":("ground",1,0)}
    if transform not in modes:
        diagnostics.append({"level":"error","path":where,"code":"ITEM_TRANSFORM","message":"Unsupported item transform: "+transform})
    display,scale,offset = modes.get(transform,("none",1,0))
    return {"display":display,"displayTranslation":[0,offset,0],"displayScale":[scale]*3}


def normalise_train(train_id, source, pack):
    source = mapping(source,train_id)
    diagnostics = list(pack.diagnostics)
    carts=[]
    node_count=0
    def diag(where,code,message):
        diagnostics.append({"level":"error","path":where,"code":code,"message":message})
    for cart_key,cart in ordered(source.get("carts"),train_id+".carts"):
        cart=mapping(cart,train_id+".cart")
        root=cart.get("model",cart.get("attachments"))
        if root is None:
            diag(str(cart_key),"NO_MODEL","Cart has no model attachment; vanilla carts are not synthesised")
            root={"type":"EMPTY"}
        if isinstance(root,list) or (isinstance(root,dict) and "type" not in root and all(str(k).isdigit() for k in root)):
            root={"type":"EMPTY","attachments":root}
        root=mapping(root,"cart model")
        physical=mapping(root.get("physical",cart.get("physical",{})),"physical")
        attachments=[]
        def visit(node,node_id,parent,depth):
            nonlocal node_count
            node_count+=1
            if depth>64 or node_count>MAX_NODES:
                raise ImportFailure("Attachment tree size/depth limit exceeded")
            node=mapping(node,node_id)
            kind=str(node.get("type","EMPTY")).upper()
            position=mapping(node.get("position",{}),node_id+".position")
            transform=str(position.get("transform","HEAD"))
            anchor=str(position.get("anchor","default"))
            if anchor not in ("default","parent"):
                diag(node_id,"ANCHOR","Unsupported dynamic anchor: "+anchor)
            entry={"id":node_id,"parent":parent,"type":{"EMPTY":"group","ITEM":"item","SEAT":"seat"}.get(kind,"unsupported"),"sourceType":kind,"translation":vector(position,"pos",0,node_id),"rotation":vector(position,"rot",0,node_id),"scale":vector(position,"size",1,node_id),"anchor":anchor,"sourceTransform":transform,"animations":normalise_animations(node.get("animations",{}),node_id+".animations",diagnostics)}
            if any(v<=0 for v in entry["scale"]):
                raise ImportFailure(node_id+": scale must be positive")
            if kind=="ITEM":
                item=mapping(node.get("item",{}),node_id+".item")
                material=str(item.get("type","AIR")).lower()
                material={"gold_pickaxe":"golden_pickaxe","gold_spade":"golden_shovel","gold_sword":"golden_sword","wood_pickaxe":"wooden_pickaxe","wood_spade":"wooden_shovel","wood_sword":"wooden_sword"}.get(material,material)
                item_id=resource_id(material)
                meta=mapping(item.get("meta",{}),node_id+".meta")
                cmd=number(meta.get("custom-model-data",item.get("custom-model-data",0)),node_id+".cmd",2147483647)
                if cmd<0 or cmd!=int(cmd) or cmd>2147483647:
                    raise ImportFailure(node_id+": custom-model-data must be a nonnegative 32-bit integer")
                damage=number(meta.get("Damage",item.get("damage",0)),node_id+".damage")
                unbreakable=boolean(meta.get("Unbreakable",False),node_id+".unbreakable")
                model=pack.resolve_item(item_id,int(cmd),damage,unbreakable,diagnostics,node_id)
                entry["item"]={"id":item_id,"customModelData":int(cmd),"damage":damage,"unbreakable":unbreakable,"model":model,**item_display(transform,diagnostics,node_id)}
            elif kind=="SEAT":
                seat={key:node[key] for key in ("displayMode","lockRotation","firstPersonViewMode","firstPersonViewLockMode","permission") if key in node}
                seat["ejectPosition"]=mapping(node.get("ejectPosition",{}),node_id+".eject")
                seat["firstPersonViewPosition"]=mapping(node.get("firstPersonViewPosition",{}),node_id+".eye")
                if mapping(node.get("displayItem",{}),node_id+".displayItem").get("enabled",False):
                    diag(node_id,"SEAT_DISPLAY_ITEM","Seat displayItem is retained only in source; not rendered")
                entry["seat"]=seat
            elif kind!="EMPTY":
                diag(node_id,"ATTACHMENT_TYPE","Unsupported attachment type: "+kind+"; children are retained")
            attachments.append(entry)
            for child_key,child in ordered(node.get("attachments"),node_id+".attachments"):
                visit(child,node_id+"/"+str(child_key),node_id,depth+1)
        visit(root,"cart"+str(cart_key)+"/root",None,0)
        length=number(physical.get("cartLength",cart.get("length",.98)),"cart length")
        if length<=0:
            raise ImportFailure("Cart length must be positive")
        carts.append({"length":length,"wheelDistance":number(physical.get("wheelDistance",0),"wheel distance"),"wheelCenter":number(physical.get("wheelCenter",0),"wheel center"),"flipped":boolean(cart.get("flipped",False),"cart flipped"),"attachments":attachments})
    if not carts:
        raise ImportFailure(f"{train_id}: no carts in saved train")
    return {"schema":SCHEMA,"id":str(train_id),"coordinateSystem":{"units":"blocks","rotation":"TrainCarts local Euler [pitch,yaw,roll] degrees","hierarchy":"parent transform composed before local transform; display correction affects only item, not children"},"carts":carts,"diagnostics":diagnostics,"complete":not any(d["level"]=="error" for d in diagnostics),"resourcePack":{"sha256":pack.sha256,"assetsPath":"resource-pack","targetMinecraft":"1.21.1"}}


def import_models(yaml_path,pack_path,output,train_names=None,strict=False,license_note=None):
    source=load_yaml(Path(yaml_path))
    pack=ResourcePack(Path(pack_path))
    names=train_names or [str(key) for key,value in source.items() if isinstance(value,dict) and "carts" in value]
    if not names:
        raise ImportFailure("No saved trains found (expected named train mappings with carts)")
    manifests=[]
    filenames=set()
    for name in names:
        if name not in source:
            raise ImportFailure("Saved train not found: "+name)
        manifest=normalise_train(name,source[name],pack)
        filename=re.sub(r"[^a-zA-Z0-9_.-]","_",name).strip(".")
        if not filename or filename in filenames:
            raise ImportFailure("Unsafe or colliding train file name: "+name)
        filenames.add(filename)
        manifest["source"]={"savedTrainSha256":hashlib.sha256(Path(yaml_path).read_bytes()).hexdigest(),"licenseNote":license_note,"sourceTrain":name}
        if strict and not manifest["complete"]:
            raise ImportFailure("Unsupported features in "+name+": "+"; ".join(d["code"]+" "+d["path"] for d in manifest["diagnostics"] if d["level"]=="error"))
        manifests.append((filename,manifest))
    output=Path(output)
    if output.exists():
        raise ImportFailure("Output already exists; choose a new directory to avoid overwriting assets")
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".train-import-",dir=output.parent) as temp:
        stage=Path(temp)/"result"
        stage.mkdir()
        for name,data in pack.files.items():
            # Preserve source licences and assets, but never execute or install server files.
            if not (name.startswith("assets/") or name in ("pack.mcmeta","pack.png") or re.search(r"(^|/)(licen[cs]e|copying|notice|authors|credits)(\.|$)",name,re.I)):
                continue
            destination=stage/"resource-pack"/name
            destination.parent.mkdir(parents=True,exist_ok=True)
            if name=="pack.mcmeta":
                metadata=json.loads(data)
                metadata["pack"]["pack_format"]=34
                metadata["pack"].pop("supported_formats",None)
                data=(json.dumps(metadata,indent=2)+"\n").encode()
            destination.write_bytes(data)
        for filename,manifest in manifests:
            (stage/(filename+".json")).write_text(json.dumps(manifest,ensure_ascii=False,indent=2,allow_nan=False)+"\n",encoding="utf-8")
        os.rename(stage,output)
    return [manifest for _,manifest in manifests]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("saved_trains",type=Path)
    parser.add_argument("resource_pack",type=Path)
    parser.add_argument("--train",action="append",help="Saved train name; repeatable, default imports all trains")
    parser.add_argument("--output",type=Path,default=Path("config/metro-world/train-models"))
    parser.add_argument("--strict",action="store_true",help="Fail before writing if any unsupported attachment/model feature exists")
    parser.add_argument("--license-note",help="Attribution/licensing statement supplied by the pack owner; not a licence verifier")
    args=parser.parse_args()
    try:
        manifests=import_models(args.saved_trains,args.resource_pack,args.output,args.train,args.strict,args.license_note)
    except (ImportFailure,OSError,zipfile.BadZipFile,json.JSONDecodeError,ValueError,TypeError,RecursionError)+( (yaml.YAMLError,) if yaml else () ) as exc:
        print("TRAIN_IMPORT_FAILED: "+str(exc),file=sys.stderr)
        return 1
    for manifest in manifests:
        print(f"TRAIN_IMPORTED: {manifest['id']} carts={len(manifest['carts'])} complete={manifest['complete']}")
        for diagnostic in manifest["diagnostics"]:
            print(f"{diagnostic['level'].upper()} {diagnostic['code']} {diagnostic['path']}: {diagnostic['message']}",file=sys.stderr)
    return 0


if __name__=="__main__":
    sys.exit(main())
