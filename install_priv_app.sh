#!/bin/bash
# ==============================================================================
# Script to install CertManagerGoogle as a Privileged System App
# ==============================================================================

set -e

APK_PATH="cert-manager-system/build/outputs/apk/debug/cert-manager-system-debug.apk"
PRIV_APP_DIR="/system/priv-app/CertManagerGoogle"
APK_NAME="CertManagerGoogle.apk"

echo "Building CertManager (System Version)..."
./gradlew :cert-manager-system:assembleDebug

echo "Restarting adb as root..."
adb root
sleep 2

echo "Uninstalling existing user app (to prevent conflicts)..."
adb shell pm uninstall com.android.niap.cert.service || true

echo "Remounting system partition as read-write..."
adb remount
sleep 1

echo "Creating Priv-App directory..."
adb shell mkdir -p ${PRIV_APP_DIR}

echo "Pushing APK to system/priv-app..."
adb push ${APK_PATH} ${PRIV_APP_DIR}/${APK_NAME}

echo "Setting permissions..."
adb shell chmod 644 ${PRIV_APP_DIR}/${APK_NAME}

echo "Rebooting device to apply system app changes..."
adb reboot

echo "Done! After reboot, CertManagerGoogle will run as a privileged system background service."
