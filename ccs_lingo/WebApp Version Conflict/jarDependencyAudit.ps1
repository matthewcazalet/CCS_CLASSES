param (
    [string]$ClassName = "google-http-client-apache-v2"
)

# Normalize input: convert dots to slashes for package-style paths
$SearchPath = $ClassName.Replace('.', '/')

Write-Host "`n🔍 Searching for '$ClassName' in Maven dependency tree..." -ForegroundColor Cyan
$mvnHits = mvn dependency:tree | Select-String "$ClassName" -Context 5,5

if ($mvnHits) {
    $mvnHits | ForEach-Object { $_.ToString() }
} else {
    Write-Host "   ❌ Not found in Maven dependency tree." -ForegroundColor DarkGray
}

Write-Host "`n📦 Checking for '$SearchPath' in built JAR..." -ForegroundColor Cyan
$jarHits = jar -tf target/ccs_lingo-2025.06.jar | Select-String "$SearchPath"

if ($jarHits) {
    $jarHits | ForEach-Object { $_.ToString() }
} else {
    Write-Host "   ❌ Not found in JAR contents." -ForegroundColor DarkGray
}

Write-Host "`n🧾 Conflict Summary:" -ForegroundColor Green
if ($mvnHits -and $jarHits) {
    Write-Host "   ⚠️ '$ClassName' appears in both dependency tree and JAR!" -ForegroundColor Red
} elseif ($mvnHits) {
    Write-Host "   ✅ '$ClassName' found only in dependency tree." -ForegroundColor Green
} elseif ($jarHits) {
    Write-Host "   ⚠️ '$ClassName' bundled in JAR but not declared in dependencies!" -ForegroundColor Yellow
} else {
    Write-Host "   ❌ '$ClassName' not found in either location." -ForegroundColor Gray
}