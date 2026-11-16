<#
.SYNOPSIS
  Dev cycle for the QuPath extension: build -> deploy -> run/debug QuPath.

.EXAMPLE
  .\scripts\dev.ps1 build          # build the jar only
  .\scripts\dev.ps1 deploy         # build + copy jar into the QuPath extensions folder
  .\scripts\dev.ps1 run            # deploy + start QuPath (normal exe, with console)
  .\scripts\dev.ps1 debug          # deploy + start QuPath under JDK 25 with JDWP on port 5005
  .\scripts\dev.ps1 debug -Kill    # same, but first close a running QuPath (jar is locked while running!)
  .\scripts\dev.ps1 undeploy       # remove the jar from the extensions folder
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'deploy', 'run', 'debug', 'undeploy')]
    [string]$Command = 'run',
    [switch]$Kill,
    [switch]$Suspend,   # debug: wait for the debugger before QuPath starts
    [int]$Port = 5005
)

$ErrorActionPreference = 'Stop'

# ---- Configuration -----------------------------------------------------------
$ProjectDir    = Split-Path -Parent $PSScriptRoot
$QuPathDir     = 'D:\Software\Qupath\QuPath-0.7.0'
$ExtensionsDir = 'D:\Software\Qupath\Extensions_v07\extensions'
$Jdk25         = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
$JarPattern    = 'qupath-extension-segmentation-*-all.jar'
$DeployedName  = 'qupath-extension-segmentation.jar'   # fixed name -> never two versions side by side
# Keep Gradle caches inside the project (not in C:\Users\...\.gradle)
$env:GRADLE_USER_HOME = Join-Path $ProjectDir '.gradle-home'
# ------------------------------------------------------------------------------

function Get-QuPathProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        ($_.ExecutablePath -and $_.ExecutablePath -like "$QuPathDir*") -or
        ($_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and $_.CommandLine -match 'qupath\.QuPath|qupath-debug\.args')
    }
}

function Stop-QuPathIfRunning {
    $procs = @(Get-QuPathProcesses)
    if ($procs.Count -eq 0) { return }
    if (-not $Kill) {
        throw "QuPath is running (PID $($procs.ProcessId -join ', ')) and locks the extension jar. Close it, or re-run with -Kill."
    }
    Write-Host "Stopping QuPath (PID $($procs.ProcessId -join ', '))..." -ForegroundColor Yellow
    $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 1
}

function Invoke-Build {
    Write-Host "== Building" -ForegroundColor Cyan
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat shadowJar --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } finally { Pop-Location }
}

function Invoke-Deploy {
    Invoke-Build
    Stop-QuPathIfRunning
    $jar = Get-ChildItem (Join-Path $ProjectDir 'build\libs') -Filter $JarPattern |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "No jar matching $JarPattern in build\libs" }
    # Remove any older copies (other names/versions) of this extension
    Get-ChildItem $ExtensionsDir -Filter 'qupath-extension-segmentation*.jar' -File | Remove-Item -Force
    Copy-Item $jar.FullName (Join-Path $ExtensionsDir $DeployedName) -Force
    Write-Host "== Deployed $($jar.Name) -> $ExtensionsDir\$DeployedName" -ForegroundColor Green
}

function Start-QuPathNormal {
    Write-Host "== Starting QuPath (console)" -ForegroundColor Cyan
    Start-Process -FilePath (Join-Path $QuPathDir 'QuPath-0.7.0 (console).exe') -WorkingDirectory $QuPathDir
}

function Start-QuPathDebug {
    # The bundled runtime has no JDWP agent, so launch QuPath's own jars with a full JDK 25.
    # Classpath + JVM options are taken from the installer's .cfg so it matches the real app.
    $appDir = Join-Path $QuPathDir 'app'
    $cfg    = Get-Content (Join-Path $appDir 'QuPath-0.7.0 (console).cfg')
    $cp     = $cfg | Where-Object { $_ -like 'app.classpath=*' } |
              ForEach-Object { $_.Substring('app.classpath='.Length).Replace('$APPDIR', $appDir) }
    $mainClass = ($cfg | Where-Object { $_ -like 'app.mainclass=*' }).Substring('app.mainclass='.Length)
    $jvmOpts   = $cfg | Where-Object { $_ -like 'java-options=*' } |
                 ForEach-Object { $_.Substring('java-options='.Length).Replace('$APPDIR', $appDir) }

    $s = if ($Suspend) { 'y' } else { 'n' }
    $argLines = @("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$s,address=127.0.0.1:$Port") + $jvmOpts +
                @('-cp', ('"' + (($cp -join ';') -replace '\\', '\\') + '"'), $mainClass)
    $argFile = Join-Path $ProjectDir 'build\qupath-debug.args'
    Set-Content -Path $argFile -Value $argLines -Encoding ASCII

    Write-Host "== Starting QuPath under JDK 25 with debugger on 127.0.0.1:$Port (suspend=$s)" -ForegroundColor Cyan
    Write-Host "   Attach from VS Code with the 'Attach to QuPath' launch config." -ForegroundColor Cyan
    # Working dir = build\ so JVM crash files (hs_err_pid*.log) land in the project, not in the QuPath install
    Start-Process -FilePath (Join-Path $Jdk25 'bin\java.exe') -ArgumentList "@`"$argFile`"" -WorkingDirectory (Join-Path $ProjectDir 'build')

    # Wait until the JDWP port is open so a VS Code 'attach' (preLaunchTask) can connect right away
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $client = New-Object System.Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1', $Port); $client.Close(); Write-Host "== Debug port $Port is open" -ForegroundColor Green; return }
        catch { Start-Sleep -Milliseconds 500 }
        finally { $client.Dispose() }
    }
    Write-Warning "Debug port $Port did not open within 30 s - check the QuPath console window for errors."
}

switch ($Command) {
    'build'    { Invoke-Build }
    'deploy'   { Invoke-Deploy }
    'run'      { Invoke-Deploy; Start-QuPathNormal }
    'debug'    { Invoke-Deploy; Start-QuPathDebug }
    'undeploy' {
        Stop-QuPathIfRunning
        Get-ChildItem $ExtensionsDir -Filter 'qupath-extension-segmentation*.jar' -File | Remove-Item -Force -Verbose
    }
}
