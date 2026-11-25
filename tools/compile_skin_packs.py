"""Compile the beta.5 overlay against the beta.4 release and its NeoForge client libraries."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
base, libs, tools = map(Path, sys.argv[1:4])
loose = root / "classes"
runtime = sorted(libs.rglob("*.jar"))
game = sorted(libs.rglob("client-*-srg.jar"))
patched = sorted(libs.rglob("neoforge-*-client.jar"))
if not game or not patched:
    raise SystemExit("Pass the NeoForge 21.1.x client's installed libraries directory")
join = lambda entries: os.pathsep.join(map(str, entries))
ecj = ["java", "-jar", str(tools / "ecj.jar"), "-21", "-proc:none", "-nowarn"]
asm = [tools / "asm.jar", tools / "asm-tree.jar"]
hook_dir = root / "hook-builder"
subprocess.run([*ecj, "-cp", join(asm), "-d", str(hook_dir),
                str(root / "tools/PatchSkinPacks.java")], check=True)
subprocess.run(["java", "-cp", join([hook_dir, *asm]), "PatchSkinPacks",
                str(base), str(loose)], check=True)
subprocess.run([*ecj, "-cp", join([loose, *patched, *game, base, *runtime]), "-d", str(loose),
                *map(str, sorted((root / "src").rglob("*.java")))], check=True)
