Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
strDir = fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.CurrentDirectory = strDir

' 1. Chay Server (an cua so console đen)
WshShell.Run "cmd /c """ & strDir & "\run-server.bat""", 0, False

' Cho 3 giay de Server mo san sang
WScript.Sleep 3000

' 2. Chay Client 1
WshShell.Run "cmd /c """ & strDir & "\run-client.bat""", 0, False

' Cho 1.5 giay
WScript.Sleep 1500

' 3. Chay Client 2
WshShell.Run "cmd /c """ & strDir & "\run-client.bat""", 0, False
