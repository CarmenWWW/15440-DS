#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
 
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>

#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>

#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>

#define OPEN 0
#define CLOSE 1
#define WRITE 2
#define LSEEK 3
#define UNLINK 4
#define STAT 5
#define READ 6
#define ENTRIES 7

#define OFFSET 2400
#define MAXMSGLEN 100
#define RETURNSIZE 15

typedef struct Open_header{
    int flags;
    mode_t m;
    int path_len;
    char path[0];
} Open_t;

typedef struct Close_header{
    int fd;
} Close_t;


typedef struct Write_header{
    int fd;
    size_t nbytes;
    char buf[0];
} Write_t;

typedef struct Lseek_header{
    int fd;
    off_t offset;
    int whence;
}Lseek_t;

typedef struct Unlink_header{
    int path_len;
    char name[0];
}Unlink_t;



typedef struct Read_request_header{
    int fd;
    size_t nbytes;
}Read_request_t;

typedef struct Read_reply_header{
    int size;
    char buf[];
}Read_reply_t;

typedef struct Entries_request_header{
    int fd;
    size_t nbytes;
    off_t basep;
}Entries_request_t;

typedef struct Entrie_reply_header{
    int num;
    off_t basep;
    char buf[];
}Entries_reply_t;


typedef struct Stat_request_header{
    int path_len;
    char path[0];
}Stat_request_t;

typedef struct Stat_reply_header{
    int res;
    char buf[];
}Stat_reply_t;

typedef struct RPC_header {
    int size;
    int opcode;
    char temp[0];
} RPC_t;