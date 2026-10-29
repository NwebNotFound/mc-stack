Set-Location C:\Users\nilsw\mc-stack
$p = (Get-Content -Raw C:\Users\nilsw\mc-stack\TASK5e-prompt.md) + "`n`nHinweis: setze anhand TASK4.md fort."
$p | & 'C:\Users\nilsw\AppData\Local\OpenAI\Codex\bin\08d187884f2330d1\codex.exe' exec --skip-git-repo-check -m gpt-6.1-terra -c model_reasoning_effort=high -s danger-full-access - *> C:\Users\nilsw\mc-stack\codex-task5e-log.txt

