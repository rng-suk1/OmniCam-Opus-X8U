#!/system/bin/sh
set -eu
MODDIR=${0%/*}

bind_library() {
    NAME=$1
    SOURCE="$MODDIR/system/odm/lib64/$NAME"
    TARGET="/odm/lib64/$NAME"
    test -f "$SOURCE"
    test -f "$TARGET"
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:same_process_hal_file:s0 "$SOURCE"
    mount --bind "$SOURCE" "$TARGET"
}

# The engine library is the stock one of ROM 17.0.1.200 plus this module's patches, bound whatever
# library the device has, so camera app and engine always come from the same ROM.
ALGO_TARGET=/odm/lib64/libAlgoInterface.so
ALGO_SOURCE=$MODDIR/payload/algo/libAlgoInterface.so
chown root:root "$ALGO_SOURCE" && chmod 0644 "$ALGO_SOURCE" &&
    chcon u:object_r:same_process_hal_file:s0 "$ALGO_SOURCE" && ls -Z "$ALGO_SOURCE" | grep -q ':same_process_hal_file:' &&
    mount --bind "$ALGO_SOURCE" "$ALGO_TARGET"
# The Meishe library is the stock one of ROM 17.0.1.200 plus the fisheye patches of tools/patch_msnativefilter.py
# (base layer, the newer ROM's fisheye); it keeps the label of the stock library.
MSNF_TARGET=/odm/lib64/libmsnativefilter.so
MSNF_SOURCE=$MODDIR/payload/algo/libmsnativefilter.so
chown root:root "$MSNF_SOURCE" && chmod 0644 "$MSNF_SOURCE" &&
    chcon u:object_r:same_process_hal_file:s0 "$MSNF_SOURCE" && ls -Z "$MSNF_SOURCE" | grep -q ':same_process_hal_file:' &&
    mount --bind "$MSNF_SOURCE" "$MSNF_TARGET"
# Hybrid Mount creates the two new backend paths before this phase.
bind_library libBasicTonePhotoX9.so
bind_library libBasicTonePhotoX10.so
bind_library libBasicTonePhoto.so
# Hybrid Mount registers each overlay it creates in KernelSU's umount list, and KernelSU unmounts the
# list for every app whose profile does not turn that off; the profile is set only in the manager
# and KernelSU drops it when the app is reset. The gallery needs these libraries like the camera
# does, and the config binds under /odm/etc are visible to every app anyway, so /odm/lib64 stays
# out of the list.
/data/adb/ksud umount remove /odm/lib64 > /dev/null 2>&1 || true
# OpenGate UI layer, only when post-fs-data.sh inserted its sensor-mode rows (merged/og_ready).
# The HAL loads it, so it keeps the vendor_file label of the stock library.
if [ -f "$MODDIR/merged/og_ready" ]; then
    SOURCE="$MODDIR/payload/og/camera.oemlayer.uilayer.so"
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:vendor_file:s0 "$SOURCE"
    mount --bind "$SOURCE" /odm/lib64/camera.oemlayer.uilayer.so
fi
