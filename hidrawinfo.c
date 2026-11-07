/* hidrawinfo: inspect /dev/hidraw* devices on Android (root)
 *
 * usage:
 *   hidrawinfo --list                 list all hidraw devices (name/bus/vid/pid)
 *   hidrawinfo /dev/hidraw1 -d        dump report descriptor (HIDIOCGRDESC)
 *   hidrawinfo /dev/hidraw1 -i        dump raw info (vid/pid/bus/name/phys)
 *   hidrawinfo /dev/hidraw1 -c N      capture N seconds of reports, hex each read
 *   hidrawinfo /dev/hidraw1 -w AABB   write bytes as output report
 *   hidrawinfo /dev/hidraw1 -wA AABB  write bytes as feature report
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/hidraw.h>

static void hexdump(const unsigned char *p, int n) {
    for (int i = 0; i < n; i++) {
        printf("%02x", p[i]);
        if ((i + 1) % 32 == 0 || i == n - 1) printf("\n");
        else if ((i + 1) % 8 == 0) printf(" ");
    }
}

static void dump_info(int fd) {
    struct hidraw_devinfo info;
    char name[256] = {0}, phys[256] = {0};
    ioctl(fd, HIDIOCGRAWINFO, &info);
    ioctl(fd, HIDIOCGRAWNAME(256), name);
    ioctl(fd, HIDIOCGRAWPHYS(256), phys);
    printf("bustype=0x%04x vendor=0x%04x product=0x%04x\n",
           info.bustype, info.vendor & 0xffff, info.product & 0xffff);
    printf("name=%s\nphys=%s\n", name, phys);
}

static void dump_desc(int fd) {
    struct hidraw_report_descriptor rpt;
    int size;
    if (ioctl(fd, HIDIOCGRDESCSIZE, &size) < 0) { printf("GRDESCSIZE err: %s\n", strerror(errno)); return; }
    printf("desc_size=%d\n", size);
    rpt.size = size;
    if (ioctl(fd, HIDIOCGRDESC, &rpt) < 0) { printf("GRDESC err: %s\n", strerror(errno)); return; }
    hexdump(rpt.value, rpt.size);
}

static int write_report(int fd, const char *hex, int feature) {
    int len = strlen(hex) / 2;
    unsigned char buf[512];
    for (int i = 0; i < len && hex[i*2]; i++) {
        unsigned int v;
        sscanf(hex + i*2, "%2x", &v);
        buf[i] = v;
    }
    int r;
    if (feature)
        r = ioctl(fd, HIDIOCSFEATURE(len), buf);
    else
        r = write(fd, buf, len);
    if (r < 0) printf("write err: %s\n", strerror(errno));
    else printf("write ok: %d bytes\n", r);
    return r;
}

static void capture(int fd, int seconds) {
    unsigned char buf[1024];
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    double t0 = ts.tv_sec + ts.tv_nsec / 1e9;
    double tend = t0 + seconds;
    while (1) {
        fd_set rfds;
        struct timeval tv = {1, 0};
        FD_ZERO(&rfds);
        FD_SET(fd, &rfds);
        if (select(fd + 1, &rfds, NULL, NULL, &tv) <= 0) {
            clock_gettime(CLOCK_MONOTONIC, &ts);
            if (ts.tv_sec + ts.tv_nsec / 1e9 > tend) break;
            continue;
        }
        int n = read(fd, buf, sizeof(buf));
        if (n < 0) { printf("read err: %s\n", strerror(errno)); break; }
        clock_gettime(CLOCK_MONOTONIC, &ts);
        printf("[%.3f] %d bytes: ", ts.tv_sec + ts.tv_nsec / 1e9 - t0, n);
        hexdump(buf, n);
        fflush(stdout);
        if (ts.tv_sec + ts.tv_nsec / 1e9 > tend) break;
    }
}

int main(int argc, char **argv) {
    if (argc < 2 || strcmp(argv[1], "--list") == 0) {
        DIR *d = opendir("/dev");
        struct dirent *e;
        while ((e = readdir(d))) {
            if (strncmp(e->d_name, "hidraw", 6)) continue;
            char path[128];
            snprintf(path, sizeof(path), "/dev/%s", e->d_name);
            int fd = open(path, O_RDWR);
            printf("== %s ==\n", path);
            if (fd < 0) { printf("  open err: %s\n", strerror(errno)); continue; }
            struct hidraw_devinfo info;
            char name[256] = {0};
            if (ioctl(fd, HIDIOCGRAWINFO, &info) == 0)
                printf("  bustype=0x%04x vendor=0x%04x product=0x%04x\n",
                       info.bustype, info.vendor & 0xffff, info.product & 0xffff);
            if (ioctl(fd, HIDIOCGRAWNAME(256), name) == 0)
                printf("  name=%s\n", name);
            close(fd);
        }
        closedir(d);
        return 0;
    }

    const char *dev = argv[1];
    int fd = open(dev, O_RDWR);
    if (fd < 0) { printf("open %s err: %s\n", dev, strerror(errno)); return 1; }

    int action = 0;
    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "-d") == 0) { dump_desc(fd); action = 1; }
        else if (strcmp(argv[i], "-i") == 0) { dump_info(fd); action = 1; }
        else if (strcmp(argv[i], "-c") == 0 && i + 1 < argc) { capture(fd, atoi(argv[++i])); action = 1; }
        else if (strcmp(argv[i], "-w") == 0 && i + 1 < argc) { write_report(fd, argv[++i], 0); action = 1; }
        else if (strcmp(argv[i], "-wA") == 0 && i + 1 < argc) { write_report(fd, argv[++i], 1); action = 1; }
    }
    if (!action) dump_info(fd);
    close(fd);
    return 0;
}
