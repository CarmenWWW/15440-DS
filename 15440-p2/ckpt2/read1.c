#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#define MAXMSGLEN 2200000

int main() {
    int openfd1, openfd2, openfd3, openfd4;
    char buf1[MAXMSGLEN+1], buf2[MAXMSGLEN+1], buf3[MAXMSGLEN+1], buf4[MAXMSGLEN+1];
    char buf5[100], buf6[100];
    for (int i = 0; i < 100; i++) {
        buf5[i] = '*';
        buf6[i] = '$';
    }
    // char hello[13] = "Hello World!\n";

    fprintf(stderr, "opening new\n");
    openfd1 = open("new", O_RDONLY);
    ssize_t bytesRead1 = read(openfd1, buf1, 100);
    close(openfd1);
    
    fprintf(stderr, "opening new\n");
    openfd1 = open("new", O_RDONLY);
    bytesRead1 = read(openfd1, buf1, 100);
    close(openfd1);

    fprintf(stderr, "opening new\n");
    openfd1 = open("new", O_RDONLY);
    bytesRead1 = read(openfd1, buf1, 100);
    close(openfd1);

    openfd2 = open("super_new", O_RDONLY);
    ssize_t bytesRead2 = read(openfd2, buf2, 100);
    close(openfd2);

    openfd2 = open("super_new", O_RDONLY);
    bytesRead2 = read(openfd2, buf2, 100);
    close(openfd2);

    openfd2 = open("super_new", O_RDONLY);
    bytesRead2 = read(openfd2, buf2, 100);
    close(openfd2);
    // fprintf(stderr, "opening mini_pooh again O_CREAT\n");
    // openfd4 = open("mini_pooh", O_CREAT);
    // write(openfd4, buf6, 100);
    // close(openfd4);



    // fprintf(stderr, "opening medium\n"); // 607
    // openfd2 = open("medium", O_RDWR);
    // ssize_t bytesRead2 = read(openfd2, buf2, 1000);
    // write(openfd2, buf5, 100);
    // // for (int i = 0; i < 700; i++) {
    // //     fprintf(stderr, "%c", buf2[i]);
    // // }
    // close(openfd2);

    // fprintf(stderr, "opening big\n"); //3541
    // openfd3 = open("big", O_RDWR);
    // ssize_t bytesRead3 = read(openfd3, buf3, 4000);
    // write(openfd3, buf6, 100);
    // close(openfd3);
    // fprintf(stderr, "\n");
    // fprintf(stderr, "bytesRead1: %ld, bytesRead2: %ld, bytesRead3: %ld\n", bytesRead1, bytesRead2, bytesRead3);

    
    // fprintf(stderr, "opening huge\n");
    // openfd4 = open("huge", O_RDONLY);
    // ssize_t bytesRead4 = read(openfd4, buf4, MAXMSGLEN);
    // close(openfd4);
    // fprintf(stderr, "bytesRead1: %ld, bytesRead2: %ld, bytesRead3: %ld, bytesRead4: %ld\n", bytesRead1, bytesRead2, bytesRead3, bytesRead4);

}   