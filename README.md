# UNDER NIGHT IN-BIRTH II Sys:Celes for Android (experimental, work in progress)

An **experiment** to run **your own copy** of UNDER NIGHT IN-BIRTH II Sys:Celes (Nintendo Switch, base game, title
`0100BD401D18A000`) natively on an arm64 Android phone, built on top of the **P4G Android port** (runtime, launcher,
on-device preparation). **This does not run the game properly yet**: nothing here is verified on a device.

> [!IMPORTANT]
> **No game files are included.** You need your own legally dumped base game and your own `prod.keys`.

UNI2 is (c) FRENCH-BREAD / Arc System Works. Unofficial fan project, not affiliated with them or Nintendo.

## Credits
Based on the P4G Android port (nxdevxz), which builds on the Persona 5 Royal port by Brandon and the Switch runtime of
Super Mario Bros. Wonder Recomp (BelmanteGu / Projeto Belmante), with Ryujinx's shader translator (MIT). See `LICENSE`
and `THIRD_PARTY_NOTICES.md`.

## What UNI2 changes on top of P4G
- `games/p4g/game.cpp`: UNI2 title ID, no Build ID check, hooks: `fputs` log, `glslc` stubs, every `nn::fs ... Commit`
  (always succeeds), empty `movie::Extractor`. Movies are skipped (`NX_SKIP_MOVIES=0` plays them).
- Import: title check, `Shader/*.bnsh` (88 files) scanned together with the ExeFS modules; a shader that fails is only
  counted (`spirv/import.txt`). `romfs_listing.txt` is written to the data folder.
- Runtime: audio no longer stalls at 1 fps when the audio renderer is not found (`hle_audio.cpp`), single Joy-Con
  `GetNpadState`, innermost `module_at`, `nce::hook` on `adrp`, `abort_hints` on aborts.
- Launcher: UNI2 texts, data folder `Under Night In-Birth II`, CPK import hidden.

Internal names still say `p4g` (folder, `libp4g.so`, package, log tag `adb logcat -s p4g:V`). The icon is still P4G's.
Save import/export still expects P4G's file format.

## Build
GitHub Actions -> **Build UNI2 Android APK** -> Run workflow -> artifact `uni2-apk-nce`. Without the optional secrets
`P4G_SIGNING_KEY_B64` / `P4G_SIGNING_PASSWORD` a debug key is generated (uninstall before installing the next build).
