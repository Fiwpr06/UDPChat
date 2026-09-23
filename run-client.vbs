Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
strDir = fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.CurrentDirectory = strDir

' Chay Client an console den phia sau, chi hien giao dien JavaFX
WshShell.Run "cmd /c """ & strDir & "\run-client.bat""", 0, False
