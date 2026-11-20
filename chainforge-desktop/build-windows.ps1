$ErrorActionPreference = 'Stop'

# Run this script from the ChainForge project root on Windows 10/11.
# Requirements: JDK 21 (not just a JRE), Maven, and the Windows x64 toolchain.

Set-Location $PSScriptRoot

if (-not (Test-Path 'pom.xml')) {
    throw 'pom.xml bulunamadi. Bu scripti Maven projesinin kok dizinine koyup oradan calistirin.'
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven bulunamadi. Maven kurulumunu PATH ortam degiskenine ekleyin.'
}

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage bulunamadi. JAVA_HOME JDK 21 dizinini gostermeli ve %JAVA_HOME%\bin PATH icinde olmali.'
}

$version = '0.1.0' # Keep in sync with pom.xml
$jarName = "chainforge-desktop-$version.jar"
$inputDir = Join-Path 'target' 'jpackage-input'
$distDir = 'dist'

Write-Host '1/4 - Maven package + runtime dependency jars...' -ForegroundColor Cyan
mvn clean package dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$inputDir"
if ($LASTEXITCODE -ne 0) { throw 'Maven build basarisiz.' }

$builtJar = Join-Path 'target' $jarName
if (-not (Test-Path $builtJar)) {
    throw "Uygulama JAR dosyasi bulunamadi: $builtJar (pom.xml artifactId/version degerlerini kontrol edin)."
}
Copy-Item $builtJar (Join-Path $inputDir $jarName) -Force

# Keep the output clean between builds.
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Path $distDir | Out-Null

Write-Host '2/4 - Self-contained Windows app image (bundled Java runtime)...' -ForegroundColor Cyan
$jpackageArgs = @(
    '--type', 'app-image',
    '--name', 'ChainForge',
    '--app-version', '1.0.0',
    '--vendor', 'ChainForge',
    '--description', 'ChainForge Desktop',
    '--input', $inputDir,
    '--main-jar', $jarName,
    '--main-class', 'dev.chainforge.Launcher',
    '--dest', $distDir,
    '--java-options', '-Dfile.encoding=UTF-8'
)

$iconPath = Join-Path 'src' 'main/deploy/chainforge.ico'
if (Test-Path $iconPath) {
    $jpackageArgs += @('--icon', $iconPath)
} else {
    Write-Warning 'src/main/deploy/chainforge.ico bulunamadi; varsayilan uygulama ikonu kullanilacak.'
}

& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage uygulama imajini olusturamadi.' }

$appFolder = Join-Path $distDir 'ChainForge'
$launcher = Join-Path $appFolder 'ChainForge.exe'
if (-not (Test-Path $launcher)) {
    throw "ChainForge.exe olusturulamadi: $launcher"
}

Write-Host '3/4 - Portable ZIP olusturuluyor...' -ForegroundColor Cyan
$zipPath = Join-Path $distDir 'ChainForge-Windows-x64-1.0.0-portable.zip'
Compress-Archive -Path $appFolder -DestinationPath $zipPath -Force

Write-Host '4/4 - Tamamlandi.' -ForegroundColor Green
Write-Host "Uygulama klasoru : $((Resolve-Path $appFolder).Path)"
Write-Host "Musteriye verilecek: $((Resolve-Path $zipPath).Path)"
Write-Host 'Musteri ZIP dosyasini cikartip ChainForge\ChainForge.exe dosyasina cift tiklayarak calistirabilir.'
