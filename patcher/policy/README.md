# SELinux additions

Appended to vbbot's files by `patch_system_img.py`. Cut byte for byte from the files tested on the owner's phone (Oct 9–25, 2026).

- `plat_sepolicy.append.cil`: a dedicated label for three Bigme compositor properties. Only the HWC may read or set them. No permissive domains.
- `plat_property_contexts.append`: maps `vendor.xrz.fps_limit`, `vendor.xrz.hwc_idle`, `vendor.xrz.gray_256_dither` and `vendor.xrz.force_global_refresh_mode` to that label.
- `hibreak_eink.cil`: the e-ink helper's own domain (`hibreak_eink`). It replaces the inherited `phhsu_daemon`, which is root, **permissive**, and may set every property. The helper may only:
  - listen on its socket (only `system_app`, meaning the controller and the sleep app, may connect);
  - read and write the frontlight files (`hibreak_light_sysfs`, via genfscon) and the e-ink driver controls (`hibreak_eink_debugfs`), and no other sysfs or debugfs file;
  - set the fixed refresh properties: `vendor_default_prop` (where Bigme's `vendor.xrz.*` live) and `hibreak_hwc_state_prop`.

## Checked on a Mac against the phone's full policy

The policy set is: system, system_ext and product from the image, plus vendor from Bigme's stock 3.1.6 `vendor.img`. It was compiled with official SELinux 3.8.1 `secilc`.

- **Boot-style compile** (`-N`, as init does): passes.
- **Strict compile** (neverallow checks on): the same 1,704 inherited failures as the unchanged phone policy, plus these **known, intended exceptions**. They are Android's Treble rules, which say system-partition programs should not touch vendor debugfs or vendor properties. Driving Bigme's e-ink driver *is* that job, so they cannot be avoided without a vendor-side helper:
  - `domain.te:2139`: coredomain access to debugfs (`hibreak_eink_debugfs`);
  - `property.te:158` and `:358`, `plat_pub_versioned` vendor prop rule: coredomain read/set of `vendor_default_prop` and `hibreak_hwc_state_prop`.

## Before calling it done

Run the image built with `make_payload.py ... helper_mode=permissive` on a phone first. Use the e-ink features (lights, modes, full refresh, image tone, keyboard), then look for `avc: denied ... scontext=u:r:hibreak_eink` in `logcat -b events` or `dmesg`. Add any missing rule, rebuild enforcing, and set `HELPER_DOMAIN_VERIFIED_ON_HARDWARE = True`.

Untested assumptions:
- The frontlight genfscon path matches the real kernel path (`/devices/platform/11d01000.i2c7/i2c-7/7-0036`, taken from the helper's own code).
- The helper needs no extra capability (such as `dac_override`) to write those files.
