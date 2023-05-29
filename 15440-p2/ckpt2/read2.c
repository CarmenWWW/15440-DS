#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#define MAXMSGLEN 2200000

int main() {
    char buf5[100], buf6[100];
    for (int i = 0; i < 100; i++) {
        buf5[i] = '*';
        buf6[i] = '$';
    }
    char buf1[MAXMSGLEN+1], buf2[MAXMSGLEN+1], buf3[MAXMSGLEN+1], buf4[MAXMSGLEN+1];

    fprintf(stderr, "opening super new\n"); // 607
    int openfd2 = open("super_new", O_CREAT);
    write(openfd2, buf6, 100);
    close(openfd2);
} 