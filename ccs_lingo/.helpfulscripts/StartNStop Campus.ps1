# Define the service and file path
$serviceName = "InfiniteCampus"   
$filePath = "C:\INFINITECAMPUS\tomcat\tigger-3.0.3\logs\campus\campus.log"  

# Stop the service if it is running
$service = Get-Service -Name $serviceName
if ($service.Status -eq "Running") {
    Stop-Service -Name $serviceName -Force
    Write-Output "Service '$serviceName' stopped."
} else {
    Write-Output "Service '$serviceName' is not running."
}

# Delete the file if it exists
if (Test-Path $filePath) {
    Remove-Item -Path $filePath -Force
    Write-Output "File '$filePath' deleted."
} else {
    Write-Output "File '$filePath' does not exist."
}

# Start the service again
Start-Service -Name $serviceName
Write-Output "Service '$serviceName' started."
