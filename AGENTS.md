# Project Instructions

## Build environment

- Do not run any project build, Gradle build, test, assemble, install, or packaging command from Codex CLI when Codex is running in WSL.
- This prohibition also applies to indirectly invoking Windows build tools from WSL, such as calling `gradlew.bat`, `cmd.exe`, or PowerShell.
- Leave build and device verification to the user in the native Windows/Android Studio environment unless the user explicitly changes this instruction.
- Read-only source inspection and non-build static checks are allowed in WSL.
