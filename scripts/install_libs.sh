#!/bin/bash
set -e
Q=/home/riorg/qnn_sdk/lib
A=/mnt/d/AI/claw_repair/research/rvc/rvc_app/android/app/src/main
echo "A=$A"
cp $Q/aarch64-android/libQnnHtp.so            $A/jniLibs/arm64-v8a/
cp $Q/aarch64-android/libQnnHtpV69Stub.so     $A/jniLibs/arm64-v8a/
cp $Q/aarch64-android/libQnnHtpNetRunExtensions.so $A/jniLibs/arm64-v8a/
cp $Q/aarch64-android/libQnnSystem.so         $A/jniLibs/arm64-v8a/
cp $Q/hexagon-v69/unsigned/libQnnHtpV69Skel.so $A/assets/hexagon-v69/
cp $Q/hexagon-v69/unsigned/libQnnHtpV69.so    $A/assets/hexagon-v69/
echo ARM_OK
ls -la $A/jniLibs/arm64-v8a/ | grep Qnn
echo HEX_OK
ls -la $A/assets/hexagon-v69/
