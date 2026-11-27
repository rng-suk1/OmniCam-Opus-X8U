#!/system/bin/sh
# 一键重启 Camera HAL 与相机进程，用于恢复偶发的多摄变焦失效

echo "正在重启 Camera HAL..."
killall vendor.qti.camera.provider-service_64 >/dev/null 2>&1 || true
am force-stop com.oplus.camera >/dev/null 2>&1 || true
sleep 1
echo "已完成，请重新打开相机。"
