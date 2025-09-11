# Define your directories
 $sourceLib = "C:\Users\mcazalet\OneDrive - Custom Computer Specialists, Inc\Documents\VSCodeEnv\Projects\ccs_lingo\target\lib"
 $webappLib = "C:\INFINITECAMPUS_441\webapps\campus\WEB-INF\lib"

 # Function to extract base name and version from JAR filename
 function Parse-JarName($filename) {
     if ($filename -match "^(.*?)-(\d+(?:\.\d+)*)(?:\.jar)$") {
         return @{
             Base = $matches[1]
             Version = $matches[2]
             Full = $filename
         }
     } else {
         return $null
     }
 }

 # Build dictionaries with base name as key and list of versions as value
 function Build-JarMap($jarList) {
     $map = @{}
     foreach ($jar in $jarList) {
         $base = $jar.Base
         if (-not $map.ContainsKey($base)) {
             $map[$base] = @()
         }
         $map[$base] += $jar.Version
     }
     return $map
 }

 # Get parsed JAR info
 $sourceJars = Get-ChildItem -Path $sourceLib -Filter *.jar | ForEach-Object { Parse-JarName $_.Name } | Where-Object { $_ }
 $webappJars = Get-ChildItem -Path $webappLib -Filter *.jar | ForEach-Object { Parse-JarName $_.Name } | Where-Object { $_ }

 # Build maps
 $sourceMap = Build-JarMap $sourceJars
 $webappMap = Build-JarMap $webappJars

 # Compare versions
 $mismatches = @()
 foreach ($base in $sourceMap.Keys) {
     if ($webappMap.ContainsKey($base)) {
         $sourceVersions = $sourceMap[$base] | Sort-Object -Unique
         $webappVersions = $webappMap[$base] | Sort-Object -Unique
         if ($sourceVersions -ne $webappVersions) {
             $mismatches += @{
                 Base = $base
                 SourceVersions = ($sourceVersions -join ", ")
                 WebappVersions = ($webappVersions -join ", ")
             }
         }
     }
 }

 # Output results
 if ($mismatches.Count -gt 0) {
     Write-Host "`n⚠️ JARs with version mismatches:`n"
     $mismatches | Sort-Object Base | ForEach-Object {
         Write-Host ("{0,-30} Source: {1,-20} | Webapp: {2,-20}" -f $_.Base, $_.SourceVersions, $_.WebappVersions)
     }
 } else {
     Write-Host "`n✅ All matching JARs have the same version(s)."
 }