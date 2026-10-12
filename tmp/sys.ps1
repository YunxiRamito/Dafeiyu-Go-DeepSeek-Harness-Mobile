$os = Get-CimInstance Win32_OperatingSystem
"TotalMB={0} FreeMB={1}" -f [math]::Round($os.TotalVisibleMemorySize/1KB), [math]::Round($os.FreePhysicalMemory/1KB)
"CPUs=" + (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum
Get-Process java,emulator,qemu-system-x86_64,adb -ErrorAction SilentlyContinue | ForEach-Object { "{0} {1} {2}MB" -f $_.Name,$_.Id,[math]::Round($_.WorkingSet64/1MB) }
Get-PSDrive -PSProvider FileSystem | ForEach-Object { "{0}: free {1}GB" -f $_.Name,[math]::Round($_.Free/1GB,1) }
