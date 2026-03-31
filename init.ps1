param(
    [Parameter(Mandatory=$true)]
    [string]$ProjectName,

    [Parameter(Mandatory=$true)]
    [string]$Group,

    [Parameter(Mandatory=$true)]
    [string]$BasePackage
)

$ErrorActionPreference = "Stop"

$oldProjectName = "hexagonal-service-template"
$oldGroup = "com.company"
$oldBasePackage = "com.company.service"
$oldPackagePath = "com/company/service"
$newPackagePath = $BasePackage -replace '\.', '/'

$root = $PSScriptRoot

Write-Host "Initializing project..." -ForegroundColor Cyan
Write-Host "  Project name : $ProjectName"
Write-Host "  Group        : $Group"
Write-Host "  Base package : $BasePackage"
Write-Host ""

Write-Host "[1/4] Renaming package directories..." -ForegroundColor Yellow

$javaRoots = Get-ChildItem -Path $root -Recurse -Directory -Filter "java" |
    Where-Object { $_.FullName -match "src[\\/](main|test)[\\/]java$" }

foreach ($javaRoot in $javaRoots) {
    $oldDir = Join-Path $javaRoot.FullName $oldPackagePath
    if (Test-Path $oldDir) {
        $newDir = Join-Path $javaRoot.FullName $newPackagePath
        $parentDir = Split-Path $newDir -Parent
        if (-not (Test-Path $parentDir)) {
            New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
        }
        Move-Item -Path $oldDir -Destination $newDir -Force
        $cleanup = Join-Path $javaRoot.FullName ($oldPackagePath.Split('/')[0])
        if (Test-Path $cleanup) {
            Remove-Item -Path $cleanup -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "[2/4] Replacing package names in source files..." -ForegroundColor Yellow

$extensions = @("*.java", "*.kts", "*.yml", "*.yaml", "*.xml", "*.toml", "*.properties", "*.md")
$files = @()
foreach ($ext in $extensions) {
    $files += Get-ChildItem -Path $root -Recurse -Filter $ext -File |
        Where-Object { $_.FullName -notmatch "[\\/](build|\.gradle|\.git)[\\/]" }
}

foreach ($file in $files) {
    $content = Get-Content $file.FullName -Raw
    $original = $content

    $content = $content -replace [regex]::Escape($oldBasePackage), $BasePackage
    $content = $content -replace [regex]::Escape($oldGroup), $Group
    $content = $content -replace [regex]::Escape($oldProjectName), $ProjectName

    if ($content -ne $original) {
        Set-Content -Path $file.FullName -Value $content -NoNewline
        Write-Host "  Updated: $($file.FullName.Substring($root.Length + 1))" -ForegroundColor Gray
    }
}

Write-Host "[3/4] Updating Docker container names..." -ForegroundColor Yellow

$dcFile = Join-Path $root "docker-compose.yml"
if (Test-Path $dcFile) {
    $content = Get-Content $dcFile -Raw
    $shortName = ($ProjectName -replace '-service$', '') -replace '-', ''
    $content = $content -replace 'appdb', ($shortName + "db")
    $content = $content -replace 'appuser', ($shortName + "user")
    $content = $content -replace 'apppass', ($shortName + "pass")
    Set-Content -Path $dcFile -Value $content -NoNewline
    Write-Host "  Updated: docker-compose.yml" -ForegroundColor Gray
}

Write-Host "[4/4] Updating application.yml defaults..." -ForegroundColor Yellow

$appYml = Join-Path $root "infrastructure/app/src/main/resources/application.yml"
if (Test-Path $appYml) {
    $content = Get-Content $appYml -Raw
    $shortName = ($ProjectName -replace '-service$', '') -replace '-', ''
    $content = $content -replace 'appdb', ($shortName + "db")
    $content = $content -replace 'appuser', ($shortName + "user")
    $content = $content -replace 'apppass', ($shortName + "pass")
    Set-Content -Path $appYml -Value $content -NoNewline
    Write-Host "  Updated: application.yml" -ForegroundColor Gray
}

Write-Host ""
Write-Host "Done! Project '$ProjectName' is ready." -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Replace the sample 'Item' domain with your actual domain model"
Write-Host "  2. Update openapi.yaml with your API contract"
Write-Host "  3. Modify Flyway migrations for your schema"
Write-Host "  4. Run: docker compose up -d"
Write-Host "  5. Run: ./gradlew build"
Write-Host "  6. Run: ./gradlew :infrastructure:app:bootRun"
