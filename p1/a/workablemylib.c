#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
 
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>

#define MAXMSGLEN 100
#define OFFSET 814


#include <stdlib.h>
#include <arpa/inet.h>

#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>
#include <stdbool.h>
#include "../include/dirtree.h"
#include "RPC.h"


char *serverip;
char *serverport;
unsigned short port;
char *msg;
char buf[MAXMSGLEN+1];
int sockfd, rv, sv;
struct sockaddr_in srv;


// The following line declares a function pointer with the same prototype as the open function.  
int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT
int (*orig_close)(int fd);
ssize_t (*orig_read)(int fd, void *buf, size_t count);
ssize_t (*orig_write)(int fd, const void *buf, size_t count);
off_t (*orig_lseek)(int fd, off_t offset, int whence);
int (*orig_stat)(const char *pathname, struct stat *statbuf);
int (*orig_unlink)(const char *pathname);
ssize_t (*orig_getdirentries)(int fd, char *restrict buf, size_t nbytes,
                             off_t *restrict basep);
struct dirtreenode* (*orig_getdirtree)(const char *path);
void (*orig_freedirtree)(struct dirtreenode* dt);

int sockfd = 0;

// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
    
    mode_t m=0;
    if (flags & O_CREAT) {
        va_list a;
        va_start(a, flags);
        m = va_arg(a, mode_t);
        va_end(a);
    }
    
    // we just print a message, then call through to the original open function (from libc)
    fprintf(stderr, "mylib: OPEN called for path %s\n", pathname);

    int tot_len = sizeof(RPC_header_t) + sizeof(open_header_t) + strlen(pathname) + 1;
    char *buffer = malloc(tot_len);
    // int fn_len = sizeof(open_header_t) + strlen(pathname) + 1;
    // char *fn_buffer = malloc(fn_len);

    // setup the RPC header
    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = OPEN;
    rpc_header->tot_len = tot_len;

    // setup the open request header
    // open_header_t *open_header = (open_header_t*)fn_buffer;
    open_header_t *open_header = (open_header_t *)rpc_header->body_ptr;
    open_header->path_len = strlen(pathname) + 1;
    open_header->flags = flags;
    open_header->mode = m;
    memcpy(open_header->path_ptr, pathname, strlen(pathname) + 1);

    // send the buffer to the server
    int sd = send(sockfd, buffer, tot_len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send open request\n");
    // receive the reply from server
    int rv = recv(sockfd, buffer, tot_len, 0);
    if (rv < 0) {
        fprintf(stderr, "receive error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "receive open reply\n");
    buffer[rv] = 0;
    int fd = atoi(buffer);
    free(buffer);
    
    // need error check for fd
    if (fd < 0) {
        fprintf(stderr, "open error");
        // err(1,0);
		errno = -1 * fd;
        return -1;
    }
    fprintf(stderr, "open fd: %d\n", fd);
    return fd;
}

int close(int fd) {
    fprintf(stderr, "mylib: CLOSE called\n");
    if (fd < OFFSET) {
        return orig_close(fd);
    }
    int len = sizeof(RPC_header_t) + sizeof(close_header_t);
    char *buffer = malloc(len);

    // setup the RPC header
    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = CLOSE;
    rpc_header->tot_len = len;
    // setup the close request header
    close_header_t *close_header = (close_header_t *)rpc_header->body_ptr;
    close_header->fd = fd;

    // send the buffer to the server
    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send close request\n");

    // receive the reply from server
    int rv = recv(sockfd, buffer, len, 0);
    if (rv < 0) {
        fprintf(stderr, "receive error");
        err(1,0);    
		return -1;
    }
    fprintf(stderr, "receive close reply %d\n", rv);
    buffer[rv] = 0;
    int res = atoi(buffer);
    free(buffer);

    // error check for res
    if (res < 0) {
        fprintf(stderr, "close error");
        // err(1, 0);
		errno = -1 * res;
        return -1;
    }

    return res;
}

ssize_t read(int fd, void *buf, size_t count) {
    fprintf(stderr, "mylib: READ called\n");
    if (fd < OFFSET) {
        return orig_read(fd, buf, count);
    }

    int len = sizeof(RPC_header_t) + sizeof(read_request_header_t);
    char *buffer = malloc(len);

    // setup the RPC header
    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = READ;
    rpc_header->tot_len = len;
    // setup the wrte request header
    read_request_header_t *read_request_header = (read_request_header_t*)rpc_header->body_ptr;
    read_request_header->fd = fd;
    read_request_header->count = count;
    // memcpy(read_request_header->buf_ptr, buf, count);

    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send read request\n");

    char rv_buf[MAXMSGLEN+1];
    int rv;
    bool flag = true; // set a flag for the first rv segment 
    int tot_len = 0; // stores the total len of receive buffer
    int cur_len = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client

    // need to make sure that all segments of info are successfully 
    // received for an operation
    while ((rv=recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
        // fprintf(stderr, "start collecting an operation package\n");
        // first segment recv, take tot_len and malloc size for data
        if (flag) { 
            // fprintf(stderr, "first segment received\n");
            // never enter this if statement again for this package
            flag = false; 
            RPC_header_t *rpc_reply_header = (RPC_header_t*)rv_buf;
            tot_len = rpc_reply_header->tot_len;
            // fprintf(stderr, "total length: %d\n", tot_len);
            data = malloc(tot_len);
            memcpy(data, rv_buf, rv);
            // fprintf(stderr, "receive %d bytes\n", rv);
        }
        else {
            // fprintf(stderr, "keep collecting segments\n");
            memcpy(data + cur_len, rv_buf, rv);
            // fprintf(stderr, "receive %d bytes\n", rv);
        }
        cur_len += rv;
        // fprintf(stderr, "current length is %d\n", cur_len);
        if (cur_len >= tot_len) {
            // fprintf(stderr, "finish collectin data for this operation\n");
            break;
        }
    }
    if (rv<0) {
        err(1, 0);
        return -1;
    }

    RPC_header_t *rpc_reply = (RPC_header_t*)data;
    read_reply_header_t *read_reply = (read_reply_header_t*)rpc_reply->body_ptr;
    int return_num = read_reply->size;

    if (read_reply->size < 0) {
        errno = -1 * return_num;
        free(data);
        return -1;
    }
    else {
        memcpy(buf, read_reply->buf_ptr, return_num);
        free(data);
        return return_num;
    }

}

ssize_t write(int fd, const void *buf, size_t count) {
    fprintf(stderr, "mylib: WRITE called\n");

    if (fd < OFFSET) {
        return orig_write(fd, buf, count);
    }
    
    int len = sizeof(RPC_header_t) + sizeof(write_header_t) + count;
    char *buffer = malloc(len);

    // setup the RPC header
    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = WRITE;
    rpc_header->tot_len = len;
    // setup the wrte request header
    write_header_t *write_header = (write_header_t *)rpc_header->body_ptr;
    write_header->fd = fd;
    write_header->count = count;
    memcpy(write_header->buf_ptr, buf, count);

    // send the buffer to the server
    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send write request\n");

    // receive the reply from server
    int rv = recv(sockfd, buffer, len, 0);
    if (rv < 0) {
        fprintf(stderr, "receive error");
        err(1,0);    
		return -1;
    }
    fprintf(stderr, "receive write reply\n");

    buffer[rv] = 0;
    int num = atoi(buffer);
    free(buffer);

    // error check for num
    if (num < 0) {
        fprintf(stderr, "write error");
        errno = -1 * num;
		return -1;
    }
    fprintf(stderr, "write %d bytes", num);
    return num;
}

off_t lseek(int fd, off_t offset, int whence) {
    fprintf(stderr, "mylib: LSEEK called\n");
    if (fd < OFFSET) {
        return orig_lseek(fd, offset, whence);
    }
    
    int len = sizeof(RPC_header_t) + sizeof(lseek_header_t);
    char *buffer = malloc(len);

    // setup the RPC header
    RPC_header_t *rpc_header = (RPC_header_t*)buffer;
    rpc_header->opcode = LSEEK;
    rpc_header->tot_len = len;
    // setup the lseek header
    lseek_header_t *lseek_header = (lseek_header_t*)rpc_header->body_ptr;
    lseek_header->fd = fd;
    lseek_header->offset = offset;
    lseek_header->whence = whence;

    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error\n");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send lseek request\n");

    int rv = recv(sockfd, buffer, len, 0);
    if (rv < 0) {
        fprintf(stderr, "receive error");
        err(1,0);    
		return -1;
    }
    fprintf(stderr, "receive lseek reply\n");

    buffer[rv] = 0;
    int num = atoi(buffer);
    free(buffer);

    if (num < 0) {
        fprintf(stderr, "lseek error");
        errno = -1 * num;
		return -1;
    }
    return num;
}

int stat(const char *pathname, struct stat *statbuf) {
    fprintf(stderr, "mylib: STAT called\n");
    int len = sizeof(RPC_header_t) + sizeof(stat_request_header_t) + strlen(pathname) + 1;
    char *buffer = malloc(len);

    // setup RPC header
    RPC_header_t *rpc_header = (RPC_header_t*)buffer;
    rpc_header->opcode = STAT;
    rpc_header->tot_len = len;
    // setup Stat header
    stat_request_header_t *stat_request_header = (stat_request_header_t *)rpc_header->body_ptr;
    stat_request_header->path_len = strlen(pathname) + 1;
    memcpy(stat_request_header->path_ptr, pathname, strlen(pathname) + 1);

    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send stat request\n");
    
    char rv_buf[MAXMSGLEN+1];
    int rv;
    bool flag = true; // set a flag for the first rv segment 
    int tot_len = 0; // stores the total len of receive buffer
    int cur_len = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client

    // need to make sure that all segments of info are successfully 
    // received for an operation
    while ((rv=recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
        if (flag) { 
            flag = false; 
            RPC_header_t *rpc_reply_header = (RPC_header_t*)rv_buf;
            tot_len = rpc_reply_header->tot_len;
            data = malloc(tot_len);
            memcpy(data, rv_buf, rv);
        }
        else {
            memcpy(data + cur_len, rv_buf, rv);
        }
        cur_len += rv;
        // fprintf(stderr, "current length is %d\n", cur_len);
        if (cur_len >= tot_len) {
            // fprintf(stderr, "finish collectin data for this operation\n");
            break;
        }
    }
    if (rv<0) {
        err(1, 0);
        return -1;
    }

    RPC_header_t *rpc_reply = (RPC_header_t*)data;
    stat_reply_header_t *stat_reply = (stat_reply_header_t*)rpc_reply->body_ptr;
    int res = stat_reply->res;

    if (res < 0) {
        errno = -1 * res;
        free(data);
        return -1;
    }
    else {
        memcpy(statbuf, stat_reply->buf_ptr, sizeof(struct stat));
        free(data);
        return res;
    }

}

int unlink(const char *pathname) {
    fprintf(stderr, "mylib: UNLINK called\n");
    int len = sizeof(RPC_header_t) + sizeof(unlink_header_t) + strlen(pathname) + 1;
    char *buffer = malloc(len);

    // setup the rpc_header
    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = UNLINK;
    rpc_header->tot_len = len;
    // setup the unlink header
    unlink_header_t *unlink_header = (unlink_header_t *)rpc_header->body_ptr;
    unlink_header->path_len = strlen(pathname) + 1;
    memcpy(unlink_header->path_ptr, pathname, strlen(pathname)+1);


    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send unlink request\n");

    int rv = recv(sockfd, buffer, len, 0);
    if (rv < 0) {
        fprintf(stderr, "receive error");
        err(1,0);    
		return -1;
    }
    fprintf(stderr, "receive unlink reply\n");

    buffer[rv] = 0;
    int res = atoi(buffer);
    free(buffer);

    if (res < 0) {
        fprintf(stderr, "write error");
        errno = -1 * res;
		return -1;
    }
    return res;
}

ssize_t getdirentries(int fd, char *restrict buf, size_t nbytes, 
                      off_t *restrict basep) {
    fprintf(stderr, "mylib: GetDirEntries called\n");
    if (fd < OFFSET){
        return orig_getdirentries(fd, buf, nbytes, basep);
    }

    int len = sizeof(RPC_header_t) + sizeof(getdirentries_request_header_t);
    char *buffer = malloc(len);

    RPC_header_t *rpc_header = (RPC_header_t *)buffer;
    rpc_header->opcode = GETDIRENTRIES;
    rpc_header->tot_len = len;
    getdirentries_request_header_t *getdirentries_request_header = (getdirentries_request_header_t *)rpc_header->body_ptr;
    getdirentries_request_header->fd = fd;
    getdirentries_request_header->nbytes = nbytes;
    getdirentries_request_header->basep = *basep;

    int sd = send(sockfd, buffer, len, 0);
    if (sd < 0) {
        fprintf(stderr, "send error");
        err(1,0);    
        return -1;
    }
    fprintf(stderr, "send getdirentries request");

    char rv_buf[MAXMSGLEN+1];
    int rv;
    bool flag = true; // set a flag for the first rv segment 
    int tot_len = 0; // stores the total len of receive buffer
    int cur_len = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client

    // need to make sure that all segments of info are successfully 
    // received for an operation
    while ((rv=recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
        // fprintf(stderr, "start collecting an operation package\n");
        // first segment recv, take tot_len and malloc size for data
        if (flag) { 
            // fprintf(stderr, "first segment received\n");
            // never enter this if statement again for this package
            flag = false; 
            RPC_header_t *rpc_reply_header = (RPC_header_t*)rv_buf;
            tot_len = rpc_reply_header->tot_len;
            // fprintf(stderr, "total length: %d\n", tot_len);
            data = malloc(tot_len);
            memcpy(data, rv_buf, rv);
            // fprintf(stderr, "receive %d bytes\n", rv);
        }
        else {
            // fprintf(stderr, "keep collecting segments\n");
            memcpy(data + cur_len, rv_buf, rv);
            // fprintf(stderr, "receive %d bytes\n", rv);
        }
        cur_len += rv;
        // fprintf(stderr, "current length is %d\n", cur_len);
        if (cur_len >= tot_len) {
            // fprintf(stderr, "finish collectin data for this operation\n");
            break;
        }
    }
    if (rv<0) {
        err(1, 0);
        return -1;
    }

    RPC_header_t *rpc_reply = (RPC_header_t*)data;
    getdirentries_reply_header_t *getdirentries_reply = (getdirentries_reply_header_t *)rpc_reply->body_ptr;
    int num = getdirentries_reply->num;
    if (num < 0) {
        errno = -1 * num;
        return -1;
    }
    *basep = getdirentries_reply->basep;
    memcpy(buf, getdirentries_reply->buf_ptr, num);
    free(data);
    return num;
}

// struct dirtreenode* unmarshall_tree(getdirtree_reply_header_t *reply_header) {

//     // setup the root
//     struct dirtreenode *root = malloc(sizeof(struct dirtreenode*));
//     int path_len = reply_header->path_len;
//     root->name = malloc(path_len);
//     memcpy(root->name, reply_header->path_ptr, path_len);
//     root->num_subdirs = reply_header->num_subdirs;
//     reply_header += sizeof(struct dirtreenode*) + path_len;
    
//     queue_t *cur_queue = malloc(sizeof(queue_t));
//     cur_queue->queue = malloc(sizeof(struct dirtreenode*));
//     cur_queue->queue[0] = root;
//     cur_queue->size = 1;
//     queue_t *next_queue = malloc(sizeof(queue_t));
//     next_queue->queue = NULL;
//     next_queue->size = root->num_subdirs;
    
//     while (cur_queue->size > 0) {
//         next_queue->queue = malloc(sizeof(struct dirtreenode*) * next_queue->size);
//         next_queue->size = 0;

//         for (int i = 0; i < cur_queue->size; i++) {
//             struct dirtreenode *cur_node = cur_queue->queue[i];
//             for (int j = 0; j < cur_node->num_subdirs; j++) {
//                 struct dirtreenode *node = malloc(sizeof(struct dirtreenode *));
//                 int path_len = reply_header->path_len;
//                 node->name = malloc(path_len);
//                 memcpy(node->name, reply_header->path_ptr, path_len);
//                 node->num_subdirs = reply_header->num_subdirs;
//                 node->subdirs = malloc(sizeof(struct dirtreenode*) * node->num_subdirs);
//                 next_queue->queue[j] = node;
//                 next_queue->size += node->num_subdirs;
//             }

//         }
//         free(cur_queue);
//         cur_queue = next_queue;
//     }
//     free(next_queue);
//     return root;

// }

struct dirtreenode* getdirtree(const char *path){
	msg = "getdirtree";
	// send message to server
	// fprintf(stderr, "%s\n", msg);
	send(sockfd, msg, strlen(msg), 0);	// send message; should check return value
	
	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	buf[rv]=0;				// null terminate string to print
	// fprintf(stderr, "client got messge: %s\n", buf);
	
	return orig_getdirtree(path);
}


void freedirtree (struct dirtreenode* dt){
	msg = "freedirtree";
	// send message to server
	// fprintf(stderr, "%s\n", msg);
	send(sockfd, msg, strlen(msg), 0);	// send message; should check return value
	
	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	buf[rv]=0;				// null terminate string to print
	// fprintf(stderr, "client got messge: %s\n", buf);
	
	return orig_freedirtree(dt);
}


// This function is automatically called when program is started
void _init(void) {
    // set function pointers to point to the original system functions
    orig_open = dlsym(RTLD_NEXT, "open");
    orig_close = dlsym(RTLD_NEXT, "close");
    orig_read = dlsym(RTLD_NEXT, "read");
    orig_write = dlsym(RTLD_NEXT, "write");
    orig_lseek = dlsym(RTLD_NEXT, "lseek");
    orig_stat = dlsym(RTLD_NEXT, "stat");
    orig_unlink = dlsym(RTLD_NEXT, "unlink");
    orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");
    orig_getdirtree = dlsym(RTLD_NEXT, "getdirtree");
    orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");

    char *serverip;
    char *serverport;
    unsigned short port;
    
    //char buf[MAXMSGLEN+1];
    int rv;
    struct sockaddr_in srv;
    // char *msg = "Hello from client";
    
    // Get environment variable indicating the ip address of the server
    serverip = getenv("server15440");
    if (serverip) fprintf(stderr, "Got environment variable server15440: %s\n", serverip);
    else {
        fprintf(stderr, "Environment variable server15440 not found.  Using 127.0.0.1\n");
        serverip = "127.0.0.1";
    }
    
    // Get environment variable indicating the port of the server
    serverport = getenv("serverport15440");
    if (serverport) fprintf(stderr, "Got environment variable serverport15440: %s\n", serverport);
    else {
        fprintf(stderr, "Environment variable serverport15440 not found.  Using 15440\n");
        serverport = "15440";
    }
    port = (unsigned short)atoi(serverport);
    
    // Create socket
    sockfd = socket(AF_INET, SOCK_STREAM, 0);    // TCP/IP socket
    if (sockfd<0) {
        err(1, 0);            // in case of error
        fprintf(stderr, "socket error\n");
    }

    // setup address structure to point to server
    memset(&srv, 0, sizeof(srv));            // clear it first
    srv.sin_family = AF_INET;            // IP family
    srv.sin_addr.s_addr = inet_addr(serverip);    // IP address of server
    srv.sin_port = htons(port);            // server port

    // actually connect to the server
    rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
    if (rv<0) { 
        err(1,0);
        fprintf(stderr, "connect error\n");
    }
    
    fprintf(stderr, "Init mylib\n");
}