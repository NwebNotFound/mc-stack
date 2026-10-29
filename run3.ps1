Set-Location "$env:USERPROFILE\mc-stack"
$codex = "$env:LOCALAPPDATA\OpenAI\Codex\bin\08d187884f2330d1\codex.exe"
while (Get-CimInstance Win32_Process -Filter "Name='codex.exe'" | Where-Object { $_.CommandLine -match ' exec .*gpt-6\.1-terra' }) { Start-Sleep 15 }
Get-Content TASK3.md -Raw | & $codex exec -m gpt-6.1-terra -c model_reasoning_effort=high -s danger-full-access --skip-git-repo-check - *> codex-log3.txt
