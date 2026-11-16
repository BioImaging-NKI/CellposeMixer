# qupath-extension-segmentation

Segmentation extension for [QuPath](https://qupath.github.io) 0.7 (NKI). Based on the
[QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Build
Requires a Java 25 JDK (found automatically by Gradle).
```powershell
.\scripts\dev.ps1 build
```
The jar is written to `build/libs/`. Drag it onto QuPath, or copy it into your QuPath extensions folder.

## Release
Push a version tag, e.g. `git tag v0.2.0` then `git push origin v0.2.0`. GitHub Actions builds the extension and
publishes `qupath-extension-segmentation-0.2.0-all.jar` as a release. Tags like `v0.2.0-rc1` become pre-releases.

## Develop
```powershell
.\scripts\dev.ps1 run -Kill     # build, deploy, (re)start QuPath
.\scripts\dev.ps1 debug -Kill   # same, with a Java debugger on port 5005
```
In VS Code, use the **Attach to QuPath** launch configuration. See [CLAUDE.md](CLAUDE.md) for details.
