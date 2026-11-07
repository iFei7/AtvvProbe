/*
 * wnvc_probe3.c --- WNVC 帧体对齐/位序定位实验
 *
 * 目标：确定 44 字节帧体相对 48 字节 BLE 单元的准确起点与位序。
 * 实验分三组：
 *   A 合成输入（全 0 / 全 FF / 交替 / 伪随机）——判断解码器是否真的响应
 *     比特内容（若全 0 与全 FF 输出相同，说明比特流没被使用）。
 *   B 平移扫描：帧体 = 载荷左移 s 字节（0..4），位序 identity / bitrev8。
 *   C 每帧重复喂两次（预热状态）观察是否出现连续语音。
 *
 * 判据：active = 每帧 320 样点 RMS > 100 的帧数；正确配置应接近 240。
 *
 * 用法: wnvc_probe3 <voice_stream.bin> <libvaudio.so>
 */
#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#define BODY 40
#define FRAME_SZ 48
#define UNIT 48
#define MAX_FRAMES 512
#define NSAMP 320

typedef int (*fn_dec4)(const uint8_t *, int, int16_t *, int *);
typedef void (*fn_init)(void);

static uint8_t g_pay[MAX_FRAMES][BODY];
static int g_frames;

static int load_frames(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f)
        return -1;
    static uint8_t raw[MAX_FRAMES * UNIT];
    size_t n = fread(raw, 1, sizeof(raw), f);
    fclose(f);
    g_frames = (int)(n / UNIT);
    if (g_frames <= 0 || g_frames > MAX_FRAMES)
        return -1;
    for (int i = 0; i < g_frames; i++)
        memcpy(g_pay[i], raw + i * UNIT, BODY);
    return 0;
}

static uint8_t bitrev8(uint8_t v)
{
    v = (uint8_t)(((v & 0xF0) >> 4) | ((v & 0x0F) << 4));
    v = (uint8_t)(((v & 0xCC) >> 2) | ((v & 0x33) << 2));
    return (uint8_t)(((v & 0xAA) >> 1) | ((v & 0x55) << 1));
}

/* 把 src(40B) 左移 shift 字节（右补 0）写入 dst44；bitrev 时逐字节位反转。 */
static void fill_body(uint8_t *dst44, const uint8_t *src, int shift, int bitrev)
{
    memset(dst44, 0, 44);
    for (int i = 0; i + shift < BODY && i < 44; i++) {
        uint8_t v = src[i + shift];
        dst44[i] = bitrev ? bitrev8(v) : v;
    }
}

/* 通用执行器：get_body(i, dst44) 由调用方提供。返回活跃帧数。 */
typedef void (*body_fn)(int idx, uint8_t *dst44, void *ctx);

static int run(body_fn bf, void *ctx, void *h, double *avg_rms, int repeat)
{
    fn_dec4 dec = (fn_dec4)dlsym(h, "Wnvc320Decoder");
    fn_init i1 = (fn_init)dlsym(h, "WnvcDecoderInit");
    fn_init i2 = (fn_init)dlsym(h, "Wnvc320DecoderInit");
    if (!dec || !i1 || !i2)
        return -1;
    i1();
    i2();
    static int16_t out[NSAMP * 8];
    uint8_t frame[FRAME_SZ];
    int act = 0;
    double acc = 0.0;
    for (int i = 0; i < g_frames; i++) {
        frame[0] = 0x57;
        frame[1] = 0x4E;
        frame[2] = 0x10;
        frame[3] = 0x00;
        bf(i, frame + 4, ctx);
        int olen = 0;
        for (int r = 0; r < repeat; r++)
            dec(frame, 0, out, &olen);
        double s = 0.0;
        for (int k = 0; k < NSAMP; k++)
            s += (double)out[k] * out[k];
        double rr = sqrt(s / NSAMP);
        acc += rr;
        if (rr > 100.0)
            act++;
    }
    *avg_rms = acc / g_frames;
    return act;
}

/* ctx: [0]=shift, [1]=bitrev 或 pattern id */
static void body_shift(int idx, uint8_t *d, void *ctx)
{
    int *p = (int *)ctx;
    fill_body(d, g_pay[idx], p[0], p[1]);
}

static void body_pattern(int idx, uint8_t *d, void *ctx)
{
    int pat = *(int *)ctx;
    (void)idx;
    memset(d, 0, 44);
    if (pat == 0)
        return; /* 全 0 */
    if (pat == 1) {
        memset(d, 0xFF, BODY);
    } else if (pat == 2) {
        for (int i = 0; i < BODY; i++)
            d[i] = (i & 1) ? 0x55 : 0xAA;
    } else if (pat == 3) {
        uint32_t s = 0x12345678;
        for (int i = 0; i < BODY; i++) {
            s = s * 1103515245u + 12345u;
            d[i] = (uint8_t)(s >> 16);
        }
    } else if (pat == 4) {
        for (int i = 0; i < BODY; i++)
            d[i] = (uint8_t)i;
    }
}

static int child_run(body_fn bf, void *ctx, void *h, int repeat, const char *tag)
{
    pid_t pid = fork();
    if (pid == 0) {
        double rms = 0.0;
        int act = run(bf, ctx, h, &rms, repeat);
        if (act < 0)
            printf("%-28s dlsym failed\n", tag);
        else
            printf("%-28s active=%3d/%d avg_rms=%8.1f\n", tag, act, g_frames, rms);
        fflush(NULL);
        _exit(act < 0 ? 1 : 0);
    }
    int st = 0;
    waitpid(pid, &st, 0);
    if (!WIFEXITED(st) || WEXITSTATUS(st) != 0) {
        printf("%-28s FAILED 0x%x\n", tag, st);
        return -1;
    }
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: %s voice_stream.bin libvaudio.so\n", argv[0]);
        return 2;
    }
    if (load_frames(argv[1]) != 0)
        return 1;
    void *h = dlopen(argv[2], RTLD_NOW);
    if (!h) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
    char tag[64];
    printf("--- A 合成输入对照 ---\n");
    const char *pn[] = {"all_zero", "all_ff", "alt_aa55", "rand_lcg", "ramp0_39"};
    for (int p = 0; p < 5; p++) {
        int pat = p;
        snprintf(tag, sizeof(tag), "pat/%s", pn[p]);
        child_run(body_pattern, &pat, h, 1, tag);
    }
    printf("--- B 平移 x 位序扫描 ---\n");
    for (int bitrev = 0; bitrev < 2; bitrev++)
        for (int sh = 0; sh <= 4; sh++) {
            int ctx[2] = {sh, bitrev};
            snprintf(tag, sizeof(tag), "shift%d/%s", sh,
                     bitrev ? "bitrev" : "plain");
            child_run(body_shift, ctx, h, 1, tag);
        }
    printf("--- C 每帧重复喂 2 次 ---\n");
    {
        int ctx[2] = {0, 0};
        child_run(body_shift, ctx, h, 2, "shift0/plain/repeat2");
    }
    return 0;
}
