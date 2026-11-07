/* memscan: read/search target process memory (Android root)
 *
 * usage:
 *   memscan <pid> dump <hexaddr> <len>          hexdump memory
 *   memscan <pid> find <hexbytes> [maxhits]     search all readable regions
 *   memscan <pid> findfile <hexaddr> <len> <hexbytes>   search one region
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <ctype.h>
#include <sys/types.h>

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
static int parse_hex(const char *s, unsigned char *out, int max) {
    int n = 0;
    while (*s && n < max) {
        while (*s == ' ' || *s == ',') s++;
        if (!*s) break;
        int hi = hexval(s[0]), lo = s[1] ? hexval(s[1]) : -1;
        if (hi < 0 || lo < 0) break;
        out[n++] = (hi << 4) | lo;
        if (s[1]) s += 2; else s += 1;
    }
    return n;
}

static void hexdump(unsigned long long addr, const unsigned char *p, int n) {
    for (int i = 0; i < n; i += 16) {
        printf("%08llx  ", addr + i);
        for (int j = 0; j < 16 && i + j < n; j++) printf("%02x ", p[i + j]);
        printf("\n");
    }
}

static unsigned long long parse_addr(const char *s) {
    return strtoull(s, NULL, 16);
}

int main(int argc, char **argv) {
    if (argc < 3) { printf("usage: memscan <pid> dump|find|findfile ...\n"); return 1; }
    int pid = atoi(argv[1]);
    char mempath[64];
    snprintf(mempath, sizeof(mempath), "/proc/%d/mem", pid);
    int mem = open(mempath, O_RDONLY);
    if (mem < 0) { printf("open %s err: %s\n", mempath, strerror(errno)); return 1; }

    if (strcmp(argv[2], "dump") == 0 && argc >= 5) {
        unsigned long long addr = parse_addr(argv[3]);
        int len = atoi(argv[4]);
        static unsigned char buf[65536];
        if (len > 65536) len = 65536;
        if (pread64(mem, buf, len, (off64_t)addr) != len) {
            printf("pread err: %s\n", strerror(errno));
            if (errno == 0) printf("(partial read unsupported)\n");
        }
        hexdump(addr, buf, len);
    } else if (strcmp(argv[2], "find") == 0 && argc >= 4) {
        unsigned char pat[512];
        int plen = parse_hex(argv[3], pat, sizeof(pat));
        int maxhits = argc >= 5 ? atoi(argv[4]) : 100;
        char mapspath[64];
        snprintf(mapspath, sizeof(mapspath), "/proc/%d/maps", pid);
        FILE *mf = fopen(mapspath, "r");
        if (!mf) { printf("open maps err: %s\n", strerror(errno)); return 1; }
        char line[512];
        int hits = 0, scanned = 0;
        static unsigned char rbuf[262144];
        while (fgets(line, sizeof(line), mf) && hits < maxhits) {
            unsigned long long lo, hi;
            char perms[8];
            if (sscanf(line, "%llx-%llx %7s", &lo, &hi, perms) != 3) continue;
            if (perms[0] != 'r') continue;
            if (strstr(line, "/dev/") && !strstr(line, "ashmem")) continue;
            scanned++;
            unsigned long long cur = lo;
            /* keep pattern aligned search; overlap 1 region boundary */
            while (cur < hi && hits < maxhits) {
                size_t want = (hi - cur) < sizeof(rbuf) ? (size_t)(hi - cur) : sizeof(rbuf);
                if (want < (size_t)plen) break;
                ssize_t got = pread64(mem, rbuf, want, (off64_t)cur);
                if (got < (ssize_t)plen) break;
                for (ssize_t i = 0; i + plen <= got; i++) {
                    if (rbuf[i] == pat[0] && memcmp(rbuf + i, pat, plen) == 0) {
                        printf("HIT %#llx  (region %#llx-%llx %s)\n", cur + i, lo, hi, perms);
                        hits++;
                        if (hits >= maxhits) break;
                    }
                }
                cur += got - plen + 1;
            }
        }
        fclose(mf);
        printf("scanned %d readable regions, %d hits\n", scanned, hits);
    } else if (strcmp(argv[2], "findfile") == 0 && argc >= 6) {
        unsigned long long addr = parse_addr(argv[3]);
        int len = atoi(argv[4]);
        unsigned char pat[512];
        int plen = parse_hex(argv[5], pat, sizeof(pat));
        static unsigned char rbuf[262144];
        unsigned long long cur = addr;
        int hits = 0;
        while (cur < addr + len) {
            size_t want = (addr + len - cur) < sizeof(rbuf) ? (size_t)(addr + len - cur) : sizeof(rbuf);
            if (want < (size_t)plen) break;
            ssize_t got = pread64(mem, rbuf, want, (off64_t)cur);
            if (got < (ssize_t)plen) break;
            for (ssize_t i = 0; i + plen <= got; i++) {
                if (rbuf[i] == pat[0] && memcmp(rbuf + i, pat, plen) == 0) {
                    printf("HIT %#llx\n", cur + i);
                    hits++;
                }
            }
            cur += got - plen + 1;
        }
        printf("%d hits in %#llx..%#llx\n", hits, addr, addr + len);
    } else {
        printf("unknown mode\n");
    }
    close(mem);
    return 0;
}
