/*
 * vaudio_harness.c --- 直接驱动原厂 vaudio Nano 协议栈（TX6 工厂语音路径）
 *
 * 调用链（全部来自 audio.vaudio.default.so 的符号与反编译）：
 *   Nano_HidOpen()                        初始化 64 个设备槽 + 互斥锁
 *   Nano_HidrawOpen("/dev/hidraw0", 0)    打开 hidraw，校验报告映射/VID:PID，写入槽 0
 *   Nano_HidAuthentication(slot0)         9B 认证挑战 + Hid_Poll 等 5d5d5a 响应
 *   Nano_HidrawActive(fd, 0)              派生读线程（按键检测/voice start/音频事件）
 *   Nano_HidrawOpsMic(slot0, 1)           发 mic ON（5e5a0100ffffff0000）
 *   Nano_GetAudio(buf, 520)               收解码后 PCM（16k s16）
 *
 * 诊断：每次调用返回值打日志；PCM 落盘 /data/local/tmp/nanosic_pcm/out.raw。
 * 注意：本进程不自行 read hidraw（避免与栈内读线程抢字节），RX 流量看 btsnoop。
 *
 * 用法: vaudio_harness <hidraw_path> <秒数>
 */
#include <dlfcn.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <math.h>
#include <sys/stat.h>

typedef void (*fn_void)(void);
typedef int (*fn_open_t)(const char *, int);
typedef int (*fn_active_t)(int, int);
typedef int (*fn_slot_int)(void *, int);
typedef int (*fn_getaudio_t)(void *, int);

#define SLOT_BSS_OFF 0x3f548u   /* Nano 槽数组 .bss 偏移（r2 vaddr，PT_LOAD 基 0） */

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static long fd_snapshot(int *out, int max)
{
    DIR *d = opendir("/proc/self/fd");
    long n = 0;
    struct dirent *e;
    if (!d) return -1;
    while ((e = readdir(d)) && n < max) {
        if (e->d_name[0] >= '0' && e->d_name[0] <= '9')
            out[n++] = atoi(e->d_name);
    }
    closedir(d);
    return n;
}

/* 找出 snap2 有而 snap1 没有的 fd（HidrawOpen 新开的那个） */
static int fd_diff(int *a, long na, int *b, long nb)
{
    for (long i = 0; i < nb; i++) {
        int found = 0;
        for (long j = 0; j < na; j++)
            if (a[j] == b[i]) { found = 1; break; }
        if (!found) return b[i];
    }
    return -1;
}

static unsigned long lib_load_base(const char *pathfrag)
{
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    unsigned long lo = 0, best = 0;
    if (!f) return 0;
    while (fgets(line, sizeof line, f)) {
        if (strstr(line, pathfrag)) {
            lo = strtoul(line, NULL, 16);
            if (best == 0 || lo < best) best = lo;
        }
    }
    fclose(f);
    return best;
}

static void rms_log(const int16_t *p, int n_samples, FILE *pcm)
{
    double s = 0;
    for (int i = 0; i < n_samples; i++)
        s += (double)p[i] * p[i];
    double rms = n_samples ? sqrt(s / n_samples) : 0;
    fprintf(stderr, "[pcm] bytes=%d rms=%.1f\n", n_samples * 2, rms);
    if (pcm) fwrite(p, 1, n_samples * 2, pcm);
}

int main(int argc, char **argv)
{
    const char *hidraw = (argc > 1) ? argv[1] : "/dev/hidraw0";
    int seconds = (argc > 2) ? atoi(argv[2]) : 60;
    const char *libpath = "/data/local/tmp/libvaudio.so";

    setvbuf(stderr, NULL, _IOLBF, 0);
    mkdir("/data/local/tmp/nanosic_pcm", 0777);

    fprintf(stderr, "[harness] start, hidraw=%s dur=%ds\n", hidraw, seconds);

    void *so = dlopen(libpath, RTLD_NOW);
    if (!so) {
        fprintf(stderr, "[FATAL] dlopen %s: %s\n", libpath, dlerror());
        return 1;
    }
    fprintf(stderr, "[harness] dlopen ok\n");

    fn_void      hid_open   = (fn_void)dlsym(so, "Nano_HidOpen");
    fn_open_t    hw_open    = (fn_open_t)dlsym(so, "Nano_HidrawOpen");
    fn_active_t  active     = (fn_active_t)dlsym(so, "Nano_HidrawActive");
    fn_slot_int  auth       = (fn_slot_int)dlsym(so, "Nano_HidAuthentication");
    fn_slot_int  ops_mic    = (fn_slot_int)dlsym(so, "Nano_HidrawOpsMic");
    fn_getaudio_t get_audio = (fn_getaudio_t)dlsym(so, "Nano_GetAudio");
    typedef int (*fn_state_t)(int);
    fn_state_t dev_state    = (fn_state_t)dlsym(so, "Nano_HidrawDevState");

    if (!hid_open || !hw_open || !active || !auth || !ops_mic || !get_audio) {
        fprintf(stderr, "[FATAL] dlsym: hid_open=%p hw_open=%p active=%p auth=%p ops_mic=%p get_audio=%p\n",
                (void *)hid_open, (void *)hw_open, (void *)active,
                (void *)auth, (void *)ops_mic, (void *)get_audio);
        return 1;
    }

    unsigned long base = lib_load_base("libvaudio.so");
    void *slot0 = base ? (void *)(base + SLOT_BSS_OFF) : NULL;
    fprintf(stderr, "[harness] lib load base=0x%lx slot0=%p\n", base, slot0);
    if (!slot0) {
        fprintf(stderr, "[FATAL] cannot locate lib load base\n");
        return 1;
    }

    /* 1) 初始化栈 */
    hid_open();
    fprintf(stderr, "[step] Nano_HidOpen done\n");

    /* 2) HidrawOpen（它自己 open(path)，fd 落入槽 0） */
    int before[256], after[256];
    long nb = fd_snapshot(before, 256);
    int ret = hw_open(hidraw, 0);
    long na = fd_snapshot(after, 256);
    int newfd = fd_diff(before, nb, after, na);
    fprintf(stderr, "[step] Nano_HidrawOpen(\"%s\",0) ret=%d newfd=%d devstate0=%d\n",
            hidraw, ret, newfd, dev_state ? dev_state(0) : -1);

    if (newfd < 0) {
        fprintf(stderr, "[WARN] no new fd — HidrawOpen 可能校验失败（报告映射/VID:PID）\n");
    }

    /* 3) 认证（Hid_Poll 在此等待 3s，此时读线程尚未启动，无竞争） */
    ret = auth(slot0);
    fprintf(stderr, "[step] Nano_HidAuthentication ret=%d\n", ret);

    /* 4) 激活读线程 */
    if (newfd >= 0) {
        ret = active(newfd, 0);
        fprintf(stderr, "[step] Nano_HidrawActive(%d,0) ret=%d\n", newfd, ret);
    } else {
        fprintf(stderr, "[WARN] skip Active (no fd)\n");
    }

    /* 5) mic ON */
    ret = ops_mic(slot0, 1);
    fprintf(stderr, "[step] Nano_HidrawOpsMic(slot0,1) ret=%d\n", ret);

    /* 6) 收 PCM 60 秒 */
    FILE *pcm = fopen("/data/local/tmp/nanosic_pcm/out.raw", "wb");
    static unsigned char buf[2048];
    int got_any = 0, nonzero_rms = 0, loops = 0;
    double t0 = now_s();
    while (now_s() - t0 < seconds) {
        memset(buf, 0, sizeof buf);
        int n = get_audio(buf, sizeof buf);
        loops++;
        if (n > 0) {
            got_any++;
            /* 按 int16 统计 RMS */
            int ns = n / 2;
            double s = 0;
            for (int i = 0; i < ns; i++) {
                int16_t v = (int16_t)(buf[2 * i] | (buf[2 * i + 1] << 8));
                s += (double)v * v;
            }
            double rms = ns ? sqrt(s / ns) : 0;
            if ((long)loops % 20 == 1 || rms > 200)
                fprintf(stderr, "[pcm] loop=%d n=%d rms=%.1f\n", loops, n, rms);
            if (rms > 200) nonzero_rms++;
            if (pcm) fwrite(buf, 1, n, pcm);
        } else if ((long)loops % 50 == 1) {
            fprintf(stderr, "[pcm] loop=%d n=%d (idle)\n", loops, n);
        }
        usleep(20000);
    }
    fprintf(stderr, "[harness] RESULT loops=%d got_any=%d rms>200_frames=%d\n",
            loops, got_any, nonzero_rms);
    if (pcm) fclose(pcm);

    /* 保持进程存活 2s 让内部线程收尾日志 */
    sleep(2);
    fprintf(stderr, "[harness] exit\n");
    return (got_any > 0 && nonzero_rms > 0) ? 0 : 2;
}
