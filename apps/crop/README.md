# Crop (local.claude.crop)

Quick image cropper for the HiBreak e-ink phone. Plain Java, no Gradle: `./build.sh` → `out/crop.apk` (own local key in `keys/`).

**Use**
- Share an image → **Crop** → drag corners / drag inside to move → **Send** (system share sheet: pick any app) or **Save** (Pictures/Crop).
- Apps that ask for an image via GET_CONTENT/PICK can choose **Crop an image**: pick, crop, **Done** returns the cropped file. Android 15's photo picker has higher priority, so some apps never show it; sharing always works.
- Launcher icon: opens the file picker, then the same crop screen.
- Square / Free toggle, Rotate 90°. PNG/WebP sources stay PNG, others save as JPEG q92; images over 3000 px are downsampled. EXIF rotation honoured.

**Files**: `CropActivity` (flows + output), `CropView` (box, 36 dp corner touch zones, no animation), `ShareProvider` (read-only content:// for cache/out, files older than a day cleaned on start).

**Verified on HiBreak** (synthetic grid image): launcher → picker → crop B2:C2 → Save gave 616x314 PNG of exactly B2+C2; Send opened the share sheet with the image; 0 crashes. Shell `am start SEND` can't grant MediaStore reads, so test sharing from a real app.

## Public build

`./build.sh --release` makes the build that is published:

```bash
KS=/path/to/crop-release.p12 KS_PASS=... ./build.sh --release   # -> out-release/crop.apk
```

- Signed with the release keystore in `KS` (PKCS12, alias `release`); the password is read from `KS_PASS`, never
  put on the command line of the signer. Keep the keystore out of the repository.
- Same code and resources as a plain `./build.sh`, which signs with a local key it creates in `keys/` and writes `out/`.
- No permissions. Logs never include image URIs or file paths.
- Not published: `out/`, `out-release/`, `keys/`.
