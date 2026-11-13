#!/usr/bin/env bash
# Build the compiler, not game shaders. Requires Linux, .NET 10 and Android NDK r28c.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$BIN/aarch64-linux-android29-clang"
export PATH="$BIN:$PATH"
ARCHIVE="private/third_party/ryubing-a82350bb77.tar.gz"
SOURCE="private/third_party/ryubing"
mkdir -p private/third_party
if [ ! -f "$ARCHIVE" ]; then
    echo "Downloading the pinned Ryubing source archive..."
    curl --fail --location --retry 3 --retry-all-errors \
        --connect-timeout 20 --max-time 180 --show-error \
        --output "$ARCHIVE" \
        https://codeberg.org/Software_arch/Ryubing/archive/a82350bb77.tar.gz
fi
echo "aba9789bdcf3fda9e90f965636e9e85e9dcc299333fbf5451bcf22ecfd608506  $ARCHIVE" | sha256sum --check
if [ ! -d "$SOURCE" ]; then
    mkdir -p "$SOURCE"
    tar -xzf "$ARCHIVE" --strip-components=1 -C "$SOURCE"
fi
test -s "$SOURCE/LICENSE.txt"

# Host smoke test uses only an original EXIT-only fixture and validates the generated module.
PROJECT=tools/maxwell/spirv_harness/SpirvHarness.csproj
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1
export NUGET_XMLDOC_MODE=skip
echo "Building the host Maxwell translator smoke test..."
dotnet build "$PROJECT" -c Release --disable-build-servers --verbosity minimal
dotnet private/build/spirv_harness/bin/Release/net10.0/spirv_harness.dll \
    --test-local private/build/spirv_harness/probe
if command -v spirv-val >/dev/null; then
    for shader in private/build/spirv_harness/probe/*.spv; do
        spirv-val --target-env vulkan1.1 "$shader"
    done
fi

OUT=build-apk/shader-libs
mkdir -p "$OUT"
echo "Publishing the Android ARM64 NativeAOT translator..."
dotnet publish "$PROJECT" -c Release -r linux-bionic-arm64 \
    -p:NxBuildDir=spirv_harness_android -p:PublishAot=true -p:NativeLib=Shared -p:OutputType=Library \
    -p:DisableUnsupportedError=true -p:PublishAotUsingRuntimePack=true \
    -p:CppCompilerAndLinker=aarch64-linux-android29-clang -p:ObjCopyName=llvm-objcopy \
    -p:StripSymbols=true --disable-build-servers --verbosity minimal \
    -o private/build/spirv_harness_android/publish
cp private/build/spirv_harness_android/publish/spirv_harness.so "$OUT/libspirvharness.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"
cp "$SOURCE/LICENSE.txt" "$OUT/Ryubing-MIT.txt"
DOTNET_ROOT_BUILD="$(dirname "$(readlink -f "$(command -v dotnet)")")"
cp "$DOTNET_ROOT_BUILD/LICENSE.txt" "$OUT/dotnet-LICENSE.txt"
cp "$DOTNET_ROOT_BUILD/ThirdPartyNotices.txt" "$OUT/dotnet-THIRD-PARTY-NOTICES.txt"
"$BIN/llvm-readelf" -h "$OUT/libspirvharness.so" > "$OUT/shader-elf.txt"
grep -q AArch64 "$OUT/shader-elf.txt"
"$BIN/llvm-nm" -D --defined-only "$OUT/libspirvharness.so" > "$OUT/shader-symbols.txt"
grep -Eq '[[:space:]]spirv_harness_api_version(@[^[:space:]]*)?$' "$OUT/shader-symbols.txt"
grep -Eq '[[:space:]]spirv_harness_translate(@[^[:space:]]*)?$' "$OUT/shader-symbols.txt"
"$BIN/llvm-readelf" -d "$OUT/libspirvharness.so" > "$OUT/shader-dependencies.txt"
