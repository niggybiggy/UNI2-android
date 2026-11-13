# Local shader generation

P4G no longer needs a preconverted game shader pack in the APK. Its Maxwell translator is built from the pinned MIT Ryubing source (`a82350bb77`, archive SHA-256 checked by the build script). The APK contains the compiler, its license notices, and the port's own presentation/overlay shaders, not the game's translated shader files.

## Preparation

The importer opens the player's base NSP/XCI with their keys, verifies the ExeFS NSO segments, and scans the decompressed modules for NVN Maxwell programs. Unambiguous shader/control pairs are translated into a fresh staging directory. The importer also compiles an original, no-op compute fixture to check the native compiler and disk writes on the device. This probe is removed before installing the cache.

On success, `<data root>/spirv` receives the new cache; an existing cache is renamed to `spirv-backup-<timestamp>`. Saves and mods are not touched. A failure leaves the previous cache available. The `prepared.tsv` marker is written only after all discovered seed shaders succeed. Old copied shader packs and the former numbered GXP output do not satisfy this format.

Some programs may only become available when the game runs. A zero-seed scan is valid after the compiler probe succeeds. The renderer translates each missing shader synchronously at first use, then reuses its cached output. A first encounter can briefly pause gameplay. These calls run on a dedicated host thread with an 8 MiB stack, rather than attaching NativeAOT to an NCE guest stack.

## Cache files

- `<hash>.spv`: generated module; hash is the first 16 hexadecimal digits of SHA-1 of the entire Maxwell code block, identical to the runtime's existing key.
- `<hash>.resources`: the translator's resource mapping, including storage buffer descriptors. An empty file is valid when there are no resources. It is committed atomically before the module.
- `results.tsv`: a readable aggregate resource table. Sidecars remain authoritative for shaders first encountered after the renderer loaded the table.
- `prepared.tsv`: format/version and seed shader hashes. The launcher checks both seed shaders and modules generated during gameplay.
- `failed.txt`: records a first-use translation failure and causes the launcher to route back to preparation next time. Repeated failure needs investigation of that error, rather than repeatedly preparing blindly.

Only a newly translated shader or an error is logged, once per hash per run. No per-frame logging was added. The NVN window origin, viewport, winding, presentation transforms, FPS pacing, controls and save layout are unchanged.

## Build and validation

`build-p4g.yml` builds `libspirvharness.so` from source using .NET 10 NativeAOT for `linux-bionic-arm64`. It no longer downloads `libp4gshader.so` from an old GXP conversion artifact. The APK verifier rejects `.spv` entries, `assets/spirv/`, and the obsolete converter. License notices in `assets/licenses/` are ordinary text and are retained.

Host checks exercise original synthetic Maxwell translation, malformed input, block discovery, Java compilation, cache readiness, and existing save import/rollback behavior. They do not replace a cold-cache Android test with the player's game. NativeAOT's Android Bionic support is experimental upstream: https://github.com/dotnet/runtime/blob/main/src/coreclr/nativeaot/docs/android-bionic.md

Before republishing, install a test build, complete preparation without manually supplying a shader cache, then test a dungeon, dialogue, a battle transition, several Persona attacks, saving and reopening. A second run should reuse the locally generated cache. Report `failed.txt` and `nx.log` if translation fails. No game dump or keys should be uploaded.
