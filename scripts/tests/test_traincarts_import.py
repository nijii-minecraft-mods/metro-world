import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

MODULE_SPEC = importlib.util.spec_from_file_location("traincarts_import",Path(__file__).resolve().parents[1]/"import-traincarts.py")
importer=importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(importer)


class TrainCartsImportTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)

    def pack(self,extra=None):
        path=self.root/"pack.zip"
        files={"pack.mcmeta":json.dumps({"pack":{"pack_format":34,"description":"original test pack"}}),
               "assets/minecraft/models/item/golden_pickaxe.json":json.dumps({"parent":"minecraft:item/generated","overrides":[{"predicate":{"custom_model_data":1000001},"model":"test:train/body"},{"predicate":{"custom_model_data":1000002},"model":"test:train/door"}]}),
               "assets/test/models/train/body.json":json.dumps({"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/iron_block"}}),
               "assets/test/models/train/door.json":json.dumps({"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/cyan_concrete"}}),
               "LICENSE.txt":"Original fixture; no third-party assets"}
        files.update(extra or {})
        with zipfile.ZipFile(path,"w") as archive:
            for name,data in files.items():archive.writestr(name,data)
        return path

    def saved(self,text):
        path=self.root/"trains.yml";path.write_text(text,encoding="utf-8");return path

    def fixture(self):
        return self.saved('''metro:
  carts:
    - model:
        type: EMPTY
        physical: {cartLength: 3.25, wheelDistance: 2.8}
        position: {posZ: 0.25, rotY: 30}
        attachments:
          0:
            type: ITEM
            position: {transform: HYBRID_ARMORSTAND_HEAD, posY: 1.5, rotY: 15, sizeX: 1.25}
            item: {type: GOLDEN_PICKAXE, meta: {custom-model-data: 1000001}}
            attachments:
              0:
                type: ITEM
                item: {type: GOLDEN_PICKAXE, meta: {custom-model-data: 1000002}}
                position: {transform: HYBRID_DISPLAY_HEAD, posX: 0.5}
                animations:
                  doors:
                    looped: false
                    nodes: ['t=0.5 x=0 scene=closed', 't=0.5 active=1 x=0.75 yaw=20 scene=open', 't=0 x=0.75']
          1:
            type: SEAT
            position: {posX: 0.2, posY: 0.4, posZ: -0.8}
            lockRotation: true
            ejectPosition: {posX: 1.5, posY: 0.1}
''')

    def test_real_import_preserves_hierarchy_cmd_animation_seat_and_pack(self):
        output=self.root/"out"
        result=importer.import_models(self.fixture(),self.pack(),output,strict=True,license_note="fixture attribution")[0]
        self.assertTrue(result["complete"])
        cart=result["carts"][0]
        self.assertEqual(cart["length"],3.25)
        root,body,door,seat=cart["attachments"]
        self.assertEqual(body["parent"],root["id"])
        self.assertEqual(door["parent"],body["id"])
        self.assertEqual(body["translation"],[0,1.5,0])
        self.assertEqual(body["rotation"],[0,15,0])
        self.assertEqual(body["scale"],[1.25,1,1])
        self.assertEqual(body["item"]["model"],"test:train/body")
        self.assertEqual(body["item"]["displayTranslation"],[0,.25,0])
        self.assertEqual(door["item"]["displayTranslation"],[0,0,0])
        self.assertEqual(door["animations"]["doors"]["frames"][1]["scene"],"open")
        self.assertEqual(door["animations"]["doors"]["frames"][1]["translation"],[.75,0,0])
        self.assertEqual(seat["seat"]["ejectPosition"]["posX"],1.5)
        self.assertTrue((output/"resource-pack/LICENSE.txt").is_file())
        self.assertEqual(json.loads((output/"metro.json").read_text()),result)

    def test_integer_cart_maps_and_last_matching_override(self):
        train={"carts":{2:{"model":{"type":"ITEM","item":{"type":"GOLD_PICKAXE","meta":{"custom-model-data":1000002}}}}}}
        result=importer.normalise_train("metro",train,importer.ResourcePack(self.pack()))
        self.assertEqual(result["carts"][0]["attachments"][0]["item"]["model"],"test:train/door")

    def test_unknown_types_are_diagnostic_and_strict_is_atomic(self):
        saved=self.saved("metro: {carts: [{model: {type: PLATFORM, attachments: [{type: SEAT}]}}]}")
        output=self.root/"out"
        with self.assertRaises(importer.ImportFailure):importer.import_models(saved,self.pack(),output,strict=True)
        self.assertFalse(output.exists())
        result=importer.import_models(saved,self.pack(),output)[0]
        self.assertFalse(result["complete"])
        self.assertEqual(result["diagnostics"][0]["code"],"ATTACHMENT_TYPE")
        self.assertEqual(len(result["carts"][0]["attachments"]),2)

    def test_zip_slip_is_rejected(self):
        with self.assertRaises(importer.ImportFailure):importer.ResourcePack(self.pack({"../escape":"bad"}))
        self.assertFalse((self.root.parent/"escape").exists())

    def test_zip_symlink_is_rejected(self):
        path=self.pack()
        with zipfile.ZipFile(path,"a") as archive:
            entry=zipfile.ZipInfo("assets/test/link");entry.create_system=3;entry.external_attr=(0o120777<<16)
            archive.writestr(entry,"/etc/passwd")
        with self.assertRaises(importer.ImportFailure):importer.ResourcePack(path)

    def test_yaml_alias_duplicates_and_nonfinite_transform_are_rejected(self):
        for text in ("a: &a {carts: []}\nb: *a", "metro: {carts: [], carts: []}"):
            with self.subTest(text=text),self.assertRaises(importer.ImportFailure):importer.load_yaml(self.saved(text))
        train={"carts":[{"model":{"type":"EMPTY","position":{"posX":float("nan")}}}]}
        with self.assertRaises(importer.ImportFailure):importer.normalise_train("metro",train,importer.ResourcePack(self.pack()))

    def test_existing_output_is_never_overwritten(self):
        output=self.root/"out";output.mkdir();sentinel=output/"world";sentinel.write_text("keep")
        with self.assertRaises(importer.ImportFailure):importer.import_models(self.fixture(),self.pack(),output)
        self.assertEqual(sentinel.read_text(),"keep")

    def test_missing_models_and_dynamic_anchors_are_reported(self):
        train={"carts":[{"model":{"type":"ITEM","position":{"anchor":"front wheel"},"item":{"type":"PAPER","meta":{"custom-model-data":123}}}}]}
        result=importer.normalise_train("metro",train,importer.ResourcePack(self.pack()))
        self.assertFalse(result["complete"])
        self.assertEqual({d["code"] for d in result["diagnostics"]},{"ANCHOR","MODEL_NOT_RESOLVED"})

    def test_model_predicate_uses_minecraft_float32_precision(self):
        pack=importer.ResourcePack(self.pack())
        pack.models["minecraft:item/golden_pickaxe"]["overrides"]=[
            {"predicate":{"custom_model_data":100000001},"model":"test:train/body"},
            {"predicate":{"custom_model_data":100000002},"model":"test:train/door"}]
        # Both large integers round to the same Java float, so the last override wins.
        diagnostics=[]
        self.assertEqual(pack.resolve_item("minecraft:golden_pickaxe",100000001,0,False,diagnostics,"root"),"test:train/door")

    def test_durability_resolution_for_hoes_and_shears(self):
        pack=importer.ResourcePack(self.pack())
        pack.models["minecraft:item/diamond_hoe"]={"overrides":[
            {"predicate":{"damage":0.5},"model":"test:train/hoe_half"}]}
        pack.models["test:train/hoe_half"]={}
        diagnostics=[]
        self.assertEqual(pack.resolve_item("minecraft:diamond_hoe",0,800,False,diagnostics,"root"),"test:train/hoe_half")

    def test_animation_active_and_movement_control_are_preserved(self):
        result=importer.normalise_animations({"move":{"nodes":["t=0.25 active=0 yaw=90 scene=stop","t=0 active=1"],"movementControlled":True,"speed":.2,"autoplay":True}},"root",[])
        self.assertFalse(result["move"]["frames"][0]["active"])
        self.assertTrue(result["move"]["movementControlled"])
        self.assertEqual(result["move"]["speed"],.2)

    def test_yaml_executable_tags_are_not_loaded(self):
        saved=self.saved("metro: !!python/object/apply:os.system ['touch bad']")
        with self.assertRaises(Exception):importer.load_yaml(saved)
        self.assertFalse((self.root/"bad").exists())


if __name__=="__main__":unittest.main()
