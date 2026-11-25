"""Package a compiled Bustling build using the version in gradle.properties."""
import re
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 tools/package_release.py <base-jar>")
base = Path(sys.argv[1])
properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith("#"))
version = properties["mod_version"]
if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[\w.-]+)?", version):
    raise ValueError(f"Invalid mod version {version}")
classes = root / "classes"
resources = root / "resources"
overrides = {file.relative_to(classes).as_posix(): file.read_bytes() for file in classes.rglob("*.class")}
overrides.update({file.relative_to(resources).as_posix(): file.read_bytes()
                  for file in resources.rglob("*") if file.is_file()})
mod_metadata = "META-INF/neoforge.mods.toml"
template = overrides[mod_metadata].decode()
if template.count("${mod_version}") != 1:
    raise ValueError("The mod metadata must include one mod_version placeholder")
overrides[mod_metadata] = template.replace("${mod_version}", version).encode()
manifest = "META-INF/MANIFEST.MF"
removed = {"assets/bustling/models/bb/motion/object_editor_glow.json",
           "assets/bustling/models/bb/motion/staff_shell.json"}
jar_path = root / "build" / f"bustling-{version}.jar"
jar_path.parent.mkdir(exist_ok=True)
with ZipFile(base) as previous:
    manifest_lines = previous.read(manifest).decode().splitlines()
    manifest_lines = [line for line in manifest_lines if line and not line.startswith("Implementation-Version:")]
    overrides[manifest] = ("\r\n".join([*manifest_lines, f"Implementation-Version: {version}"]) + "\r\n\r\n").encode()
    with ZipFile(jar_path, "w", ZIP_DEFLATED) as jar:
        for name in sorted((set(previous.namelist()) | overrides.keys()) - removed):
            jar.writestr(name, overrides[name] if name in overrides else previous.read(name))
archive = jar_path.with_suffix(".zip")
with ZipFile(archive, "w", ZIP_DEFLATED) as update:
    update.write(jar_path, jar_path.name)
with ZipFile(jar_path) as jar, ZipFile(archive) as update:
    assert jar.testzip() is None and update.testzip() is None
    assert update.namelist() == [jar_path.name] and update.read(jar_path.name) == jar_path.read_bytes()
print(archive)
