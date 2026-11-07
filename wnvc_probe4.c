/*
 * wnvc_probe4.c --- 按 get_next_bit 的真实位序验证 4 种变换
 *
 * get_next_bit：每次从缓冲区取一个 u16（小端装载）→ 指针 +2，
 *               然后按 bit15→bit0 顺序取位。
 * 因此解码器实际读取的比特序列是：
 *     byte[1] bit7..0, byte[0] bit7..0, byte[3] bit7..0, byte[2] bit7..0, ...
 *
 * 若编码器按 b0..b1 顺序、MSB first 写入，则需「u16 字节交换」；
 * 若编码器按 LSB first 写入，则需 rev16（= 每字节 bitrev + 字节交换）。
 *
 * 本程序对 4 种变换各解 8 帧，输出每帧 RMS 与「载荷前 5 bit」的十进制值
 * （decode_envelope 先读 5 bit 帧错误码，无错时应恒为 7）。
 *
 * 用法: wnvc_probe4 <voice_stream.bin> <libvaudio.so>
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
#define MAXF 512
#define NS 320
#define NF 8 /* 只解前 8 帧 */

typedef int (*fn_dec4)(const uint8_t *, int, int16_t *, int *);
typedef void (*fn_init)(void);

static uint8_t g_pay[MAXF][BODY];
static int g_frames;

static int load_frames(const char *p)
{
    FILE *f = fopen(p, "rb");
    if (!f)
        return -1;
    static uint8_t raw[MAXF * UNIT];
    size_t n = fread(raw, 1, sizeof(raw), f);
    fclose(f);
    g_frames = (int)(n / UNIT);
    if (g_frames <= 0)
        return -1;
    for (int i = 0; i < g_frames; i++)
        memcpy(g_pay[i], raw + i * UNIT, BODY);
    return 0;
}

static uint8_t br8(uint8_t v)
{
    v = (uint8_t)(((v & 0xF0) >> 4) | ((v & 0x0F) << 4));
    v = (uint8_t)(((v & 0xCC) >> 2) | ((v & 0x33) << 2));
    return (uint8_t)(((v & 0xAA) >> 1) | ((v & 0x55) << 1));
}

static uint16_t br16(uint16_t v)
{
    return (uint16_t)((br8((uint8_t)(v & 0xFF)) << 8) | br8((uint8_t)(v >> 8)));
}

/* id: 0 plain, 1 u16 swap, 2 rev16, 3 bitrev8 only, 4 bitrev8+swap */
static void xform(uint8_t *dst, const uint8_t *src, int id)
{
    for (int i = 0; i < BODY; i += 2) {
        uint8_t a = src[i], b = src[i + 1];
        switch (id) {
        case 0:
            dst[i] = a;
            dst[i + 1] = b;
            break;
        case 1:
            dst[i] = b;
            dst[i + 1] = a;
            break;
        case 2: { /* rev16 of little-endian word */
            uint16_t w = (uint16_t)(a | (b << 8));
            w = br16(w);
            dst[i] = (uint8_t)(w & 0xFF);
            dst[i + 1] = (uint8_t)(w >> 8);
            break;
        }
        case 3:
            dst[i] = br8(a);
            dst[i + 1] = br8(b);
            break;
        default: /* 4: bitrev8 + swap */
            dst[i] = br8(b);
            dst[i + 1] = br8(a);
            break;
        }
    }
}

/* 按 get_next_bit 的规则取前 nbit 位并组成整数 */
static int first_bits(const uint8_t *body, int nbit)
{
    int v = 0, got = 0, cnt = 0;
    uint16_t cache = 0;
    for (int i = 0; i < BODY && got < nbit; i += 2) {
        cache = (uint16_t)(body[i] | (body[i + 1] << 8));
        for (cnt = 0; cnt < 16 && got < nbit; cnt++) {
            v = (v << 1) | ((cache >> (15 - cnt)) & 1);
            got++;
        }
    }
    return v;
}

static void run_one(int id, void *h, const char *name)
{
    fn_dec4 dec = (fn_dec4)dlsym(h, "Wnvc320Decoder");
    fn_init i1 = (fn_init)dlsym(h, "WnvcDecoderInit");
    fn_init i2 = (fn_init)dlsym(h, "Wnvc320DecoderInit");
    if (!dec || !i1 || !i2) {
        printf("%-16s dlsym fail\n", name);
        return;
    }
    i1();
    i2();
    uint8_t frame[FRAME_SZ];
    static int16_t out[NS];
    printf("%-16s", name);
    for (int k = 0; k < NF && k < g_frames; k++) {
        frame[0] = 0x57;
        frame[1] = 0x4E;
        frame[2] = 0x10;
        frame[3] = 0x00;
        memset(frame + 4, 0, 44);
        xform(frame + 4, g_pay[k], id);
        int olen = -1;
        memset(out, 0, sizeof(out));
        dec(frame, 0, out, &olen);
        double s = 0.0;
        for (int i = 0; i < NS; i++)
            s += (double)out[i] * out[i];
        double r = sqrt(s / NS);
        printf(" f%d[rms=%.0f,len=%d]", k, r, olen);
    }
    printf("  first5bits=");
    for (int k = 0; k < 3; k++) {
        uint8_t b[44];
        memset(b, 0, 44);
        xform(b, g_pay[k], id);
        printf("%d,", first_bits(b, 5));
    }
    printf("\n");
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
    const char *nm[] = {"plain", "u16_swap", "rev16", "bitrev8", "bitrev8_swap"};
    for (int id = 0; id < 5; id++) {
        pid_t pid = fork();
        if (pid == 0) {
            run_one(id, h, nm[id]);
            fflush(NULL);
            _exit(0);
        }
        int st = 0;
        waitpid(pid, &st, 0);
        if (!WIFEXITED(st) || WEXITSTATUS(st) != 0)
            printf("%-16s FAILED 0x%x\n", nm[id], st);
    }
    return 0;
}
