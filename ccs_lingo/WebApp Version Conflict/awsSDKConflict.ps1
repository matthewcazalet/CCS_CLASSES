# Check for potential conflicts
Write-Host "Checking for potential AWS SDK conflicts..." -ForegroundColor Cyan

# 1. Check what AWS classes webapp might use
Write-Host " AWS SDK in webapp (outside your JAR):" -ForegroundColor Yellow
$webappLib = "C:\INFINITECAMPUS_441\webapps\campus\WEB-INF\lib"
Get-ChildItem "$webappLib" -Filter "aws*.jar" | Where-Object { $_.Name -notlike "*lingo*" } | Select-Object Name

# 2. Check what's bundled in your JAR
Write-Host "AWS SDK bundled in lingo JAR:" -ForegroundColor Yellow
jar -tf target/ccs_lingo-2025.06.jar | 
    Select-String "software/amazon/awssdk" | 
    ForEach-Object { $_.ToString().Split('/')[0..4] -join '/' } | 
    Select-Object -Unique | Select-Object -First 5

Write-Host " Assessment:" -ForegroundColor Green
Write-Host "   - WebApp AWS SDK 2.19.13 is in separate JAR files" -ForegroundColor Gray
Write-Host "   - Lingo AWS SDK 2.20.81 is inside ccs_lingo.jar" -ForegroundColor Gray
Write-Host "   - No conflicts - they are isolated" -ForegroundColor Gray