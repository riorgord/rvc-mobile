// QNN 2.47 日志回调桥:QNN 回调是 printf 风格(fmt + va_list),
// Python ctypes 无法消费 va_list,由本 shim 负责 vsnprintf 后
// 输出到 stderr(Chaquopy → logcat python.stderr)和可选文件。
// 纯 libc,零依赖,Termux clang 可直接编译。
#include <stdarg.h>
#include <stdio.h>

static FILE *g_f = 0;

void gsv_log_open(const char *path) {
    if (g_f) fclose(g_f);
    g_f = fopen(path, "w");
}

void gsv_log_close(void) {
    if (g_f) {
        fclose(g_f);
        g_f = 0;
    }
}

// 签名与 QnnLog_Callback_t 一致:
// void (*)(const char* fmt, QnnLog_Level_t level, uint64_t timestamp, va_list args)
void gsv_log_callback(const char *fmt, int level,
                      unsigned long long ts, va_list args) {
    char buf[2048];
    vsnprintf(buf, sizeof(buf), fmt, args);
    fprintf(stderr, "[QNN][%d][%llu] %s\n", level, ts, buf);
    if (g_f) {
        fprintf(g_f, "[%d][%llu] %s\n", level, ts, buf);
        fflush(g_f);
    }
}
