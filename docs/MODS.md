# Mods (RomFS overlay, LayeredFS style)

Put mods in `Internal storage/Persona 4 Golden/mods/<ModName>/romfs/...`, with the same paths as inside the game's RomFS
(the same layout as Switch LayeredFS: `atmosphere/contents/<titleid>/romfs/...`).

- A file there replaces the game's file with the same path; a new file is added.
- Folders starting with `.` or `_` are ignored: rename `MyMod` to `_MyMod` to turn a mod off.
- Several mods apply in alphabetical order; the last wins.
- Paths are case sensitive on Android: match the game's case exactly.
- Mods are scanned each time the game starts. Use **Diagnostics → Logs → Export** in the launcher to collect the
  startup log when reporting a mod-loading problem.
- The game file is never modified.
