/*
 * wnvc_probe2.c --- WNVC 解码器黑盒扫描：确定比特流字节/位序
 *
 * 已确认（反汇编）：
 *   Wnvc320Decoder(frame, unused, out_i16, &len_bytes) @0x27b94
 *     - r1 = frame+4 被作为比特流源
 *     - memcpy(dst, frame+4, 44, 120)：即帧体 44 字节
 *       其中 len = samples/8 + 4 = 320/8 + 4 = 44
 *     - controldec+4 = 1000 * frame[2]  (= 16000 for frame[2]=0x10)
 *     - *len_bytes = 0x280 (640B = 320 × int16)
 *   状态全在 .bss 全局对象，必须先 Init，且帧必须顺序解码。
 *
 * 本程序对载荷施加 9 种字节/位序变换，统计「活跃帧数」（每帧 320 样点
 * RMS > 100 视为有信号）。正确变换应让绝大多数帧活跃；错误变换会得到
 * 大量 [-19..19] 的抖动（解码器把比特流当静音）。
 *
 * 用法: wnvc_probe2 <voice_stream.bin> <libvaudio.so>
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
#define FRAME_SZ 48 /* 4B "WN" 头 + 44B 帧体，保证 memcpy(44) 不越界 */
#define UNIT 48
#define MAX_FRAMES 512
#define NSAMP 320

typedef int (*fn_dec4)(const uint8_t *, int, int16_t *, int *);
typedef void (*fn_init)(void);

static uint8_t g_body[MAX_FRAMES][BODY];
static uint8_t g_extra[MAX_FRAMES][4]; /* 每序列尾部 4 字节: 1d 00 seq_lo seq_hi */
static int g_frames;

static const char *g_tname[] = {
    "identity",       /* 0 原样 */
    "word_swap16",    /* 1 每 2 字节交换 */
    "bitrev_byte",    /* 2 每字节位反转 */
    "rev_chunk16",    /* 3 每 16 字节块内逆序 */
    "rev_all40",      /* 4 全部 40 字节逆序 */
    "rev_all40_wswap",/* 5 全逆序后再 2 字节交换 */
    "nib_swap",       /* 6 每字节高低 nibble 交换 */
    "rev4_32bit",     /* 7 按 4 字节整体逆序（大端 32bit）*/
    "body_plus_ext4", /* 8 帧体 = 40B 载荷 + 尾部 4B */
};

/* 读入数据，按 48 字节序列拆出 40 字节载荷与尾部 4 字节。 */
static int load_frames(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror("open");
        return -1;
    }
    static uint8_t raw[MAX_FRAMES * UNIT];
    size_t n = fread(raw, 1, sizeof(raw), f);
    fclose(f);
    g_frames = (int)(n / UNIT);
    if (g_frames <= 0 || g_frames > MAX_FRAMES)
        return -1;
    for (int i = 0; i < g_frames; i++) {
        memcpy(g_body[i], raw + i * UNIT, BODY);
        memcpy(g_extra[i], raw + i * UNIT + 40, 4);
    }
    if (raw[40] != 0x1D || raw[41] != 0x00) {
        fprintf(stderr, "bad trailer %02x %02x\n", raw[40], raw[41]);
        return -1;
    }
    printf("frames=%d\n", g_frames);
    return 0;
}

static uint8_t bitrev8(uint8_t v)
{
    v = (uint8_t)(((v & 0xF0) >> 4) | ((v & 0x0F) << 4));
    v = (uint8_t)(((v & 0xCC) >> 2) | ((v & 0x33) << 2));
    v = (uint8_t)(((v & 0xAA) >> 1) | ((v & 0x55) << 1));
    return v;
}

/* 按变换 id 生成 44 字节帧体。 */
static void make_body(uint8_t *dst44, const uint8_t *src, const uint8_t *ext,
                      int id)
{
    memset(dst44, 0, 44);
    switch (id) {
    case 0:
        memcpy(dst44, src, BODY);
        break;
    case 1:
        for (int i = 0; i < BODY; i += 2) {
            dst44[i] = src[i + 1];
            dst44[i + 1] = src[i];
        }
        break;
    case 2:
        for (int i = 0; i < BODY; i++)
            dst44[i] = bitrev8(src[i]);
        break;
    case 3:
        for (int c = 0; c < BODY / 16; c++)
            for (int i = 0; i < 16; i++)
                dst44[c * 16 + i] = src[c * 16 + 15 - i];
        break;
    case 4:
        for (int i = 0; i < BODY; i++)
            dst44[i] = src[BODY - 1 - i];
        break;
    case 5:
        for (int i = 0; i < BODY; i += 2) {
            dst44[i] = src[BODY - 2 - i];
            dst44[i + 1] = src[BODY - 1 - i];
        }
        break;
    case 6:
        for (int i = 0; i < BODY; i++)
            dst44[i] = (uint8_t)((src[i] << 4) | (src[i] >> 4));
        break;
    case 7:
        for (int i = 0; i < BODY; i += 4)
            for (int k = 0; k < 4; k++)
                dst44[i + k] = src[i + 3 - k];
        break;
    case 8:
        memcpy(dst44, src, BODY);
        memcpy(dst44 + BODY, ext, 4);
        break;
    default:
        memcpy(dst44, src, BODY);
        break;
    }
}

/* 单个变换：Init 一次，顺序解码全部帧，返回活跃帧数。 */
static int run_one(int id, void *h, int16_t *out, int *active, double *rms_out)
{
    fn_dec4 dec = (fn_dec4)dlsym(h, "Wnvc320Decoder");
    fn_init i_top = (fn_init)dlsym(h, "WnvcDecoderInit");
    fn_init i_320 = (fn_init)dlsym(h, "Wnvc320DecoderInit");
    if (!dec || !i_top || !i_320)
        return -1;
    i_top();
    i_320();

    uint8_t frame[FRAME_SZ];
    int act = 0;
    double acc = 0.0;
    for (int i = 0; i < g_frames; i++) {
        frame[0] = 0x57;
        frame[1] = 0x4E;
        frame[2] = 0x10; /* 16 kbps / 16 kHz */
        frame[3] = 0x00;
        make_body(frame + 4, g_body[i], g_extra[i], id);
        int olen = 0;
        dec(frame, 0, out + (size_t)i * NSAMP, &olen);
        const int16_t *p = out + (size_t)i * NSAMP;
        double s = 0.0;
        for (int k = 0; k < NSAMP; k++)
            s += (double)p[k] * p[k];
        double r = sqrt(s / NSAMP);
        acc += r;
        if (r > 100.0)
            act++;
    }
    *active = act;
    *rms_out = acc / g_frames;
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
    printf("%-18s %6s %10s  %s\n", "transform", "active", "avg_rms", "per-frame rms[0..7]");
    for (int id = 0; id < 9; id++) {
        pid_t pid = fork();
        if (pid == 0) {
            static int16_t out[MAX_FRAMES * NSAMP];
            int act = 0;
            double rms = 0.0;
            int r = run_one(id, h, out, &act, &rms);
            if (r == 0) {
                printf("%-18s %6d %10.1f  ", g_tname[id], act, rms);
                for (int i = 0; i < 8; i++) {
                    double s = 0.0;
                    for (int k = 0; k < NSAMP; k++) {
                        double v = out[(size_t)i * NSAMP + k];
                        s += v * v;
                    }
                    printf("%7.0f ", sqrt(s / NSAMP));
                }
                printf("\n");
            }
            fflush(NULL);
            _exit(r == 0 ? 0 : 1);
        }
        int st = 0;
        waitpid(pid, &st, 0);
        if (!WIFEXITED(st) || WEXITSTATUS(st) != 0)
            printf("%-18s FAILED status=0x%x\n", g_tname[id], st);
    }
    return 0;
}
