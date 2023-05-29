#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#define MAXMSGLEN 100

int main(){
    printf("entering...\n");
    int openfd1, openfd2, status1, status2;
    ssize_t byteRead, byteWrite;
    void *rd_buf = malloc(MAXMSGLEN);
    openfd1 = open("./test1.txt", O_RDWR, 0);
    printf("open: %d\n", openfd1);
    openfd2 = open("./test2.txt", O_RDWR, 0);
    printf("open: %d\n", openfd2);

    // testing read
    status1 = read(openfd1, rd_buf, MAXMSGLEN);
    printf("read: %d\n", status1);
    printf("read result : %s\n", (char*)rd_buf);

    // testing lseek
    int lseek_status = lseek(openfd1, 2, SEEK_CUR);
    printf("lseek status: %d\n", lseek_status);

    lseek_status = lseek(openfd1, 2, SEEK_SET);
    printf("lseek status: %d\n", lseek_status);

    // testing write
    byteWrite = write(openfd1, "testtest", 9);
    fprintf(stderr, "byte written on test1: %ld\n", byteWrite);
    byteWrite = write(openfd2, "testtest", 9);
    fprintf(stderr, "byte written on test2: %ld\n", byteWrite);

    // testing error handling
    lseek_status = lseek(openfd1, 200, SEEK_CUR);
    printf("lseek status: %d\n", lseek_status);
    rd_buf = malloc(MAXMSGLEN);
    byteWrite = write(openfd1, "lololmao", 9);
    status1 = read(openfd1, rd_buf, MAXMSGLEN);
    printf("read: %d\n", status1);
    printf("read result : %s\n", (char*)rd_buf);
    status1 = read(openfd1, rd_buf, MAXMSGLEN);

    int opendirfd = open("./", O_RDONLY, 0);
    printf("open with fd: %d\n", opendirfd);
    rd_buf = malloc(MAXMSGLEN);
    status1 = read(opendirfd, rd_buf, MAXMSGLEN);
    printf("read: %d\n", status1);
    printf("read result : %s\n", (char*)rd_buf);

    

    // close
    status1 = close(openfd1);
    fprintf(stderr, "close status: %d\n\n", status1);

    status2 = close(openfd2);
    fprintf(stderr, "close status: %d\n\n", status2);


    return 0;
}