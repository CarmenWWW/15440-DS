#include <sys/types.h>
#include "../include/dirtree.h"

// define Opcodes for the function calls
#define OPEN 0
#define CLOSE 1
#define WRITE 2
#define LSEEK 3
#define UNLINK 4
#define STAT 5
#define READ 6
#define GETDIRENTRIES 7

// RPC request header
typedef struct {

    int tot_len; // total len of the header
    int opcode; // opcode
    char body_ptr[]; // char array as placeholder for function header

} RPC_header_t;

// open request header
typedef struct {

    int flags;
    mode_t mode;
    int path_len; // length of the path
    char path_ptr[]; // char array that points to file path
    
} open_header_t;

// close request header
typedef struct {
    
    int fd;

} close_header_t;

// write request header
typedef struct {

    int fd;
    size_t count; // buffer size
    char buf_ptr[]; // char array that points to the text buffer
    
} write_header_t;

typedef struct {

    int fd;
    size_t count;
    char buf_ptr[];

} read_request_header_t;

typedef struct {

    int size;
    char buf_ptr[];

} read_reply_header_t;


typedef struct {

    int fd;
    off_t offset;
    int whence;

} lseek_header_t;


typedef struct {
    
    int path_len; // length of the path
    char path_ptr[]; // char array that points to file path

} unlink_header_t;

typedef struct {

    int path_len; // length of the path
    char path_ptr[]; // char array that points to file path

} stat_request_header_t;


typedef struct {

    int res;
    char buf_ptr[];
    
} stat_reply_header_t;

typedef struct {
    
    int fd;
    size_t nbytes;
    off_t basep;

} getdirentries_request_header_t;

typedef struct {

    int num;
    off_t basep;
    char buf_ptr[];

} getdirentries_reply_header_t;
