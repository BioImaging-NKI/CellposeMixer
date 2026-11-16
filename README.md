# qupath-extension-segmentation

Segmentation extension for [QuPath](https://qupath.github.io) 0.7 (NKI). Based on the
[QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Build
Requires a Java 25 JDK (found automatically by Gradle).
```powershell
.\scripts\dev.ps1 build
```
The jar is written to `build/libs/`. Drag it onto QuPath, or copy it into your QuPath extensions folder.

## Develop
```powershell
.\scripts\dev.ps1 run -Kill     # build, deploy, (re)start QuPath
.\scripts\dev.ps1 debug -Kill   # same, with a Java debugger on port 5005
```
In VS Code, use the **Attach to QuPath** launch configuration. See [CLAUDE.md](CLAUDE.md) for details.
