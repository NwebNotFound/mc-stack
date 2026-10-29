Set-Location "$env:USERPROFILE\mc-stack"
$codex = "$env:LOCALAPPDATA\OpenAI\Codex\bin\2993767bd1dfb009\codex.exe"
$p = Get-Content TASK2.md -Raw
& $codex exec -m gpt-6.1-terra -c 'model_reasoning_effort="high"' -s danger-full-access --skip-git-repo-check $p *> codex-log2.txt
