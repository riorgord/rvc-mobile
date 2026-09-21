@echo off
setlocal
set NDK=D:\Android\Sdk\ndk\26.3.11579264
set CLANG=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd
set SRC=D:\AI\claw_repair\research\rvc\mobile_prep\stream\phase2_out\naiqiawang\gya_02130\route2\harness\gsv_qnn_service_mg.c
set QNNINC=D:\AI\claw_repair\research\rvc\rvc_app\native\qnn_include
set OUT=D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\jniLibs\arm64-v8a\libgsv_qnn.so
"%CLANG%" -shared -fPIC -O2 ^
  -Wall -Wextra ^
  -I"%QNNINC%" ^
  %SRC% ^
  -llog -ldl -o "%OUT%"
if %errorlevel%==0 (
  echo BUILD OK: %OUT%
  dir "%OUT%"
) else (
  echo BUILD FAILED
)
