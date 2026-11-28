#!/usr/bin/env python3
"""Export the eight editable Blockbench projects to the NeoForge resource overlay."""
from argparse import ArgumentParser
from base64 import b64decode, b64encode
from math import isfinite
from pathlib import Path
from struct import unpack_from
import json

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "resources"
MANIFEST = json.loads((ROOT / "Blockbench" / "manifest.json").read_text(encoding="utf-8"))
ATLAS = MANIFEST["atlas"]
MODELS = MANIFEST["models"]
DIRECTIONS = ("north", "east", "south", "west", "up", "down")
PNG_PREFIX = "data:image/png;base64,"
FACE_LIGHT = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
DISPLAY_SLOTS = {
    "gui", "ground", "fixed", "head", "firstperson_righthand", "firstperson_lefthand",
    "thirdperson_righthand", "thirdperson_lefthand",
}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def numbers(value, size, label):
    check(isinstance(value, list) and len(value) == size
          and all(type(n) in (int, float) and isfinite(n) for n in value), label)
    return value


def check_png(data, width, height, label):
    check(len(data) >= 33 and data.startswith(b"\x89PNG\r\n\x1a\n")
          and data[12:16] == b"IHDR", f"{label}: expected a PNG")
    check(unpack_from(">II", data, 16) == (width, height), f"{label}: expected {width}×{height} pixels")


def image_source(texture, label):
    source = texture.get("source", "")
    check(isinstance(source, str) and source.startswith(PNG_PREFIX),
          f"{label}: no embedded PNG. Use sync-texture from the shared file.")
    return b64decode(source[len(PNG_PREFIX):], validate=True)


def texture_entry(project, label, spec):
    textures = project.get("textures", [])
    secondary = spec.get("emissive_native_font") or spec.get("pulse_texture", {}).get("name")
    count = 2 if secondary else 1
    check(len(textures) == count and textures[0].get("name") == "tools.png",
          f"{label}: expected tools.png and any declared secondary texture")
    if count == 2:
        check(textures[1].get("name") == secondary, f"{label}: invalid secondary texture")
    return textures[0]


def atlas_bytes():
    data = (ROOT / ATLAS["source"]).read_bytes()
    check_png(data, ATLAS["width"], ATLAS["height"], ATLAS["source"])
    return data


def sync_texture(from_project):
    projects = {
        id: read_json(ROOT / spec["project"])
        for id, spec in MODELS.items()
    }
    textures = {id: texture_entry(project, id, MODELS[id]) for id, project in projects.items()}
    data = image_source(textures[from_project], from_project) if from_project else atlas_bytes()
    check_png(data, ATLAS["width"], ATLAS["height"], "tools.png")
    source = PNG_PREFIX + b64encode(data).decode("ascii")
    for id, project in projects.items():
        texture = textures[id]
        texture["source"] = source
        texture["relative_path"] = "贴图/tools.png"
        texture["width"] = texture["uv_width"] = ATLAS["width"]
        texture["height"] = texture["uv_height"] = ATLAS["height"]
        texture.pop("path", None)
        write_json(ROOT / MODELS[id]["project"], project)
    (ROOT / ATLAS["source"]).write_bytes(data)
    print("Synced the shared PNG and all eight Blockbench projects. Run export next.")


def check_group_rotations(groups, label):
    for group in groups:
        if isinstance(group, dict):
            check(group.get("rotation", [0, 0, 0]) == [0, 0, 0],
                  f"{label}: bake group rotations into cubes before export")
            check_group_rotations(group.get("children", []), label)


def export_model(model_id, spec, atlas, motion_models):
    project_name = spec["project"]
    project = read_json(ROOT / project_name)
    check(project.get("meta", {}).get("model_format") == "java_block",
          f"{project_name}: use Blockbench Java Block/Item")
    w, h = ATLAS["width"], ATLAS["height"]
    check(project.get("resolution") == {"width": w, "height": h},
          f"{project_name}: expected {w}×{h} UV canvas")
    texture = texture_entry(project, project_name, spec)
    check(image_source(texture, project_name) == atlas,
          f"{project_name}: embedded atlas differs from the shared PNG. Run sync-texture.")
    pulse_spec = spec.get("pulse_texture")
    secondary_size = None
    if len(project["textures"]) == 2:
        secondary = project["textures"][1]
        secondary_size = [secondary.get("uv_width"), secondary.get("uv_height")]
        check(all(type(n) is int and n > 0 for n in secondary_size),
              f"{project_name}: invalid secondary texture dimensions")
        if pulse_spec:
            check(secondary_size == pulse_spec["frame_size"], f"{project_name}: incorrect pulse frame size")
    check_group_rotations(project.get("outliner", []), project_name)
    check_group_rotations(project.get("groups", []), project_name)
    display = project.get("display", {})
    check(set(display) == DISPLAY_SLOTS, f"{project_name}: retain the eight display positions")
    for slot, pose in display.items():
        for key in ("rotation", "translation", "scale"):
            numbers(pose.get(key, [1, 1, 1] if key == "scale" else [0, 0, 0]), 3,
                    f"{project_name}: invalid {slot} {key}")

    output = {"ambientocclusion": False, "textures": spec["textures"], "display": display, "elements": []}
    if spec["render_type"]:
        output["render_type"] = spec["render_type"]
    fullbright_count = 0
    parts = {}
    shell_part = model_id + "_shell"
    translucent_prefix = spec.get("translucent_name_prefix")
    gears = {}
    if model_id == "client_settings":
        for index in range(1, 4):
            group = next((g for g in project["outliner"]
                          if isinstance(g, dict) and g["name"] == f"短边齿轮 {index}"), None)
            check(group and group["children"], f"{project_name}: missing gear {index}")
            gears.update((uuid, f"settings_gear_{index}") for uuid in group["children"])
    for cube in project.get("elements", []):
        if cube.get("export", True) is False:
            continue
        name = cube.get("name", "")
        label = f"{project_name} / {name}"
        check(cube.get("type", "cube") == "cube", f"{label}: only Java cubes are supported")
        check(not cube.get("box_uv", False) and not cube.get("inflate", 0),
              f"{label}: use face UVs and explicit cube bounds")
        start = numbers(cube.get("from"), 3, f"{label}: invalid from")
        end = numbers(cube.get("to"), 3, f"{label}: invalid to")
        check(all(-16 <= a <= b <= 32 for a, b in zip(start, end))
              and sum(a < b for a, b in zip(start, end)) >= 2,
              f"{label}: cube outside Java bounds [-16, 32] or has no area")
        if spec["registry_id"] == "bustling:diffusion_device":
            check(all(0 <= a <= b <= 16 for a, b in zip(start, end)),
                  f"{label}: the device must fit a single block")

        faces = {}
        native_rune = bool(spec.get("emissive_name_prefix") and name.startswith(spec["emissive_name_prefix"]))
        crystal_core = bool(translucent_prefix and name == "实心内芯")
        crystal_shell = bool(translucent_prefix and name.startswith(translucent_prefix))
        pulsing_device = bool(pulse_spec and name.startswith(tuple(pulse_spec["element_prefixes"])))
        settings_lamp = model_id == "client_settings" and name.startswith("灯芯 ")
        scanner_lens = model_id == "object_editor" and name.startswith("扫描光芯 ")
        emitted = (native_rune or crystal_core or crystal_shell or pulsing_device
                   or settings_lamp or scanner_lens)
        for direction in DIRECTIONS:
            face = cube.get("faces", {}).get(direction)
            if not face or face.get("texture") is None or face.get("texture") is False:
                continue
            slot = 1 if native_rune or pulsing_device else 0
            check(face["texture"] in (slot, str(slot)), f"{label}/{direction}: unexpected texture slot")
            uv = numbers(face.get("uv"), 4, f"{label}/{direction}: invalid UV")
            u_size, v_size = secondary_size if slot else (w, h)
            check(all(0 <= uv[i] <= (u_size if i % 2 == 0 else v_size) for i in range(4)),
                  f"{label}/{direction}: UV is outside its texture")
            value = {"texture": "#runes" if native_rune else "#pulse" if pulsing_device else "#atlas",
                     "uv": [round(n * 16 / (u_size if i % 2 == 0 else v_size), 6) for i, n in enumerate(uv)]}
            if face.get("rotation", 0):
                check(face["rotation"] in (90, 180, 270), f"{label}/{direction}: invalid face rotation")
                value["rotation"] = face["rotation"]
            if face.get("cullface"):
                check(face["cullface"] in DIRECTIONS, f"{label}/{direction}: invalid cullface")
                value["cullface"] = face["cullface"]
            if face.get("tintindex", -1) >= 0:
                value["tintindex"] = face["tintindex"]
            if native_rune:
                check(direction == "up", f"{label}: native runes must face up")
                value["neoforge_data"] = FACE_LIGHT
            elif emitted:
                value["neoforge_data"] = FACE_LIGHT
            faces[direction] = value
        check(faces, f"{label}: has no textured faces")
        element = {"from": start, "to": end, "faces": faces}
        if emitted or cube.get("shade") is False:
            element["shade"] = False
        fullbright_count += emitted
        angles = numbers(cube.get("rotation", [0, 0, 0]), 3, f"{label}: invalid rotation")
        rotations = [(axis, angle) for axis, angle in zip("xyz", angles) if angle]
        if rotations:
            check(len(rotations) == 1 and rotations[0][1] in (-45, -22.5, 22.5, 45),
                  f"{label}: use a single axis and ±22.5° or ±45°")
            axis, angle = rotations[0]
            origin = numbers(cube.get("origin"), 3, f"{label}: invalid pivot")
            element["rotation"] = {"origin": origin, "axis": axis, "angle": angle,
                                   "rescale": bool(cube.get("rescale", False))}
        part = gears.get(cube.get("uuid"))
        if crystal_shell:
            part = shell_part
        elif crystal_core:
            part = model_id + "_core"
        elif native_rune:
            part = "copy_runes"
        elif settings_lamp:
            part = "settings_lamps"
        elif scanner_lens:
            part = "object_editor_lens"
        if part:
            parts.setdefault(part, []).append(element)
        else:
            output["elements"].append(element)
    check(output["elements"], f"{project_name}: no exported geometry")
    if spec.get("emissive_name_prefix"):
        check(fullbright_count, f"{project_name}: missing named emissive page cubes")
    if pulse_spec:
        check(fullbright_count, f"{project_name}: missing animated diffuser surfaces")
    if translucent_prefix:
        check(shell_part in parts and model_id + "_core" in parts,
              f"{project_name}: missing floating crystal parts")
        output["render_type"] = "minecraft:solid"
    if model_id == "client_settings":
        check(set(parts) == {"settings_gear_1", "settings_gear_2", "settings_gear_3", "settings_lamps"}
              and len(parts["settings_lamps"]) == 3, f"{project_name}: expected three gears and three lamps")
    if model_id == "object_editor":
        check(set(parts) == {"object_editor_lens"} and len(parts["object_editor_lens"]) == 3,
              f"{project_name}: expected a front lens and two side windows")
    for name, elements in parts.items():
        render_type = ("minecraft:translucent" if name == shell_part else
                       "minecraft:cutout" if name == "copy_runes" else "minecraft:solid")
        path = f"assets/bustling/models/bb/motion/{name}.json"
        model = {"ambientocclusion": False, "textures": spec["textures"],
                 "render_type": render_type, "elements": elements}
        check(motion_models.setdefault(path, model) == model,
              f"{project_name}: the shared crystal shells differ")
    return output


def build_models(atlas):
    parts = {}
    base = {spec["runtime_path"]: export_model(model_id, spec, atlas, parts)
            for model_id, spec in MODELS.items()}
    return {**base, **parts}


def texture_files(atlas):
    yield ATLAS["runtime_path"], atlas
    for spec in MODELS.values():
        pulse = spec.get("pulse_texture")
        if not pulse:
            continue
        asset = ROOT / pulse["source"]
        metadata = read_json(Path(str(asset) + ".mcmeta"))
        width, height = pulse["frame_size"]
        animation = metadata["animation"]
        check([animation["width"], animation["height"]] == [width, height],
              f"{asset}: unexpected frame size")
        data = asset.read_bytes()
        check_png(data, width, height * pulse["frames"], str(asset))
        yield pulse["runtime_path"], data
        yield pulse["runtime_path"] + ".mcmeta", Path(str(asset) + ".mcmeta").read_bytes()


def export():
    atlas = atlas_bytes()
    models = build_models(atlas)
    for path, data in models.items():
        write_json(RESOURCES / path, data)
    for path, data in texture_files(atlas):
        target = RESOURCES / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f"Exported {len(models)} Java models and their textures.")


def validate():
    atlas = atlas_bytes()
    for path, expected in texture_files(atlas):
        check((RESOURCES / path).read_bytes() == expected, f"{path}: runtime texture differs from the source")
    for path, expected in build_models(atlas).items():
        check(read_json(RESOURCES / path) == expected, f"{path}: export is out of date")
    print("Validated the Blockbench projects, texture atlas and NeoForge model layers.")


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sync = commands.add_parser("sync-texture")
    sync.add_argument("--from-project", choices=MODELS)
    commands.add_parser("export")
    commands.add_parser("validate")
    args = parser.parse_args()
    if args.command == "sync-texture":
        sync_texture(args.from_project)
    elif args.command == "export":
        export()
    else:
        validate()
