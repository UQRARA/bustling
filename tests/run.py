"""Run offline regressions against compiled changes. Arguments are loose classes, bbfix.9, and build-cache."""
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory

here = Path(__file__).resolve().parent
loose, original, tooling = map(Path, sys.argv[1:4])
cp = [loose, original, tooling/"gson.jar"]
join = lambda paths: os.pathsep.join(map(str, paths))
with TemporaryDirectory(prefix="bbfix9-regression-") as temp:
    root = Path(temp)
    fixture, classes, config = root/"fixture", root/"classes", root/"config"
    subprocess.run([sys.executable, str(here/"fixture.py"), str(fixture)], check=True)
    subprocess.run(["java", "-jar", str(tooling/"ecj.jar"), "-21", "-proc:none", "-nowarn",
                    "-cp", join(cp), "-d", str(classes), *map(str, fixture.rglob("*.java")),
                    str(here/"ToolAndDeviceRegression.java"), str(here/"EnvironmentPopulationRegression.java"),
                    str(here/"DevicePowerRegression.java")], check=True)
    subprocess.run(["java", "-Xverify:all", f"-Dbustling.qa.config={config}", "-cp",
                    join([classes, *cp]), "ToolAndDeviceRegression"], check=True)
    environment = ["java", "-Xverify:all", f"-Dbustling.qa.config={root / 'environment-config'}", "-cp",
                   join([classes, *cp]), "dev.bustling.EnvironmentPopulationRegression"]
    subprocess.run(environment, check=True)
    subprocess.run([*environment, "reload"], check=True)
    subprocess.run(["java", "-Xverify:all", f"-Dbustling.qa.config={root / 'power-config'}", "-cp",
                    join([classes, *cp]), "dev.bustling.DevicePowerRegression"], check=True)
