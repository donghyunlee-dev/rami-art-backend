[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$legacyPath = Join-Path $Root 'src/main/resources/db/migration'
$adminPath = Join-Path $Root 'supabase/migrations'

if (-not (Test-Path $legacyPath) -or -not (Test-Path $adminPath)) {
    throw "Migration directories are missing."
}

$legacyFiles = Get-ChildItem $legacyPath -File -Filter '*.sql' | Sort-Object Name
$adminFiles = Get-ChildItem $adminPath -File -Filter '*.sql' | Sort-Object Name
$errors = [System.Collections.Generic.List[string]]::new()
$review = [System.Collections.Generic.List[string]]::new()

foreach ($file in $legacyFiles) {
    $content = Get-Content $file.FullName -Raw
    if ($content -match '(?i)\bpublic\.(?!blob\.)') {
        $errors.Add("Legacy Flyway migration references public schema: $($file.Name)")
    }
}

foreach ($file in $adminFiles) {
    $content = Get-Content $file.FullName -Raw
    if ($content -match '(?i)rami_art_studio\.') {
        $errors.Add("Admin Supabase migration references legacy schema: $($file.Name)")
    }
    if ($content -match '(?i)drop\s+(table|schema|function|constraint)|truncate\s+') {
        $review.Add("Review destructive or constraint-changing statement: $($file.Name)")
    }
}

$duplicateNames = @($legacyFiles.Name + $adminFiles.Name | Group-Object | Where-Object Count -gt 1)
if ($duplicateNames.Count -gt 0) {
    $errors.Add("Duplicate migration filenames: $($duplicateNames.Name -join ', ')")
}

Write-Output "Legacy Flyway migrations: $($legacyFiles.Count)"
Write-Output "Admin Supabase migrations: $($adminFiles.Count)"
Write-Output "Boundary errors: $($errors.Count)"
Write-Output "Manual review items: $($review.Count)"
$review | ForEach-Object { Write-Warning $_ }

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output 'Schema boundary verification passed.'
