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
int (*orig_open)(const char *pathname, int flags, ...);
int (*orig_close)(int fd);
ssize_t (*orig_read)(int fd, void *buf, size_t nbytes);
ssize_t (*orig_write)(int fd, const void *buf, size_t nbytes);
off_t (*orig_lseek)(int fd, off_t offset, int whence);
int (*orig_stat)(const char *path,  struct stat *statbuf);
int (*orig_unlink)(const char *name);
ssize_t (*orig_getdirentries)(int fd, char *restrict buf, size_t nbytes,
                             off_t *restrict basep);
struct dirtreenode* (*orig_getdirtree)(const char *path);
void (*orig_freedirtree) (struct dirtreenode* dt);




// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
	mode_t m = 0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
	fprintf(stderr, "mylib: open called for path %s\n", pathname);
	
	// step 1. marshall the information into struct
	int total = sizeof(RPC_t) + sizeof(Open_t) + strlen(pathname) + 1; 
	char* array = malloc(total);

 	// marshall the open struct into RPC struct 
	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	rpc -> opcode = OPEN;
	// marshall the information of open into open struct
	Open_t *open = (Open_t *)(rpc->temp);
	open -> path_len = strlen(pathname) + 1;
	open -> flags = flags;
	open -> m = m;
	memcpy(open -> path, pathname, strlen(pathname) + 1);
	
	// step 2. send struct to server
	int sv = send(sockfd, array, total, 0);// send message
	if (sv < 0) {
        fprintf(stderr, "open send error\n");
        err(1,0);    
        return -1;
    }// in case something went wrong

	// step 3. get message back
	char rv_buf[MAXMSGLEN+1];
	int rv = recv(sockfd, rv_buf, total, 0);// get message
	if (rv<0){
		fprintf(stderr, "open recv error\n");
		err(1,0);
		return -1;
	}// in case something went wrong
	
	// step 4. return respond from server
	int result = atoi((char*)rv_buf); // convert result to int
	fprintf(stderr, "open final result: %d\n", result);
	// if there is an error in server, set errno and return -1
	if (result < 0){ 
		errno = result * -1;
		fprintf(stderr, "open errno: %d\n", errno);
		free(array);
		return -1;
	}
	free(array);
	return result;
}

int close(int fd) {
	fprintf(stderr, "mylib: close called\n");
	// step 0. check if the fd is valid
	if (fd < 0){
		errno = EBADF;
		return -1;
	}
	if (fd < OFFSET) {fprintf(stderr, "orig_close\n");return orig_close(fd);}

	// step 1. marshall the information into struct
	int total = sizeof(RPC_t) + sizeof(Close_t);
	char* array = malloc(total);
	// marshall the close struct into RPC struct 
	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	rpc -> opcode = CLOSE;
	Close_t *close = (Close_t *)rpc->temp;
	// marshall the information of close into close struct
	close -> fd = fd;

	// step 2. send message to server
	int sv = send(sockfd, array, total, 0);
	if (sv < 0) { // if there is a send error
		fprintf(stderr, "close send error");
        err(1,0);    
        return -1;
    }

	// step 3. get message back
	char rv_buf[MAXMSGLEN+1];
	int rv = recv(sockfd, rv_buf, MAXMSGLEN, 0);// get message
	if (rv<0){
		fprintf(stderr, "close recv error");
		err(1,0);
		return -1;
	}		// in case something went wrong

	// step 4. return respond from server
	int result = atoi((char*)rv_buf);
	if (result < 0){
		errno = result * -1;
		fprintf(stderr, "close errno: %d\n", errno);
		free(array);
		return -1;
	}
	fprintf(stderr, "close result: %d\n", result);
	free(array);
	return result;
}

ssize_t read(int fd, void *buf, size_t nbytes){
	fprintf(stderr, "client read\n");
	// step 0. check if the fd is valid
	if (fd < 0){
		errno = EBADF;
		return -1;
	}
    if (fd < OFFSET) {return orig_read(fd, buf, nbytes);}
	
	// step 1. marshall the information into struct
    int total = sizeof(RPC_t) + sizeof(Read_request_t);
    char *array = malloc(total);
    // marshall the read struct into RPC struct 
    RPC_t *rpc_header = (RPC_t *)array;
    rpc_header->opcode = READ;
    rpc_header->size = total;
	Read_request_t *read_request_header = (Read_request_t*)rpc_header->temp;
    // marshall the information of read into read struct
	// do not need to marshall buf since it is empty initially
    read_request_header->fd = fd;
    read_request_header->nbytes = nbytes;
	
	// step 2. send struct to server
	int sd = send(sockfd, array, total, 0);
    if (sd < 0) { // if there is a send error
        fprintf(stderr, "read send error\n");
        err(1,0);    
        return -1;
    }
	
	// step 3. get message back
	// use a loop to ensure all messages are received
	char rv_buf[MAXMSGLEN+1];
    int rv;
    bool flag = true; // set a flag for the first rv segment 
    int totalsize = 0; // stores the total len of receive buffer
    int nowsize = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client
    while ((rv=recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
        if (flag) { 
            flag = false; 
            RPC_t *rpc_reply_header = (RPC_t*)rv_buf;
            totalsize = rpc_reply_header->size;
            data = malloc(totalsize);
            memcpy(data, rv_buf, rv);
        }
        else {memcpy(data + nowsize, rv_buf, rv);}
        nowsize += rv;
        if (nowsize >= totalsize) {break;}
    }
    if (rv<0) {
        err(1, 0);
        return -1;
    }

	// step 4. return respond from server
    RPC_t *rpc_reply = (RPC_t*)data;
    Read_reply_t *read_reply = (Read_reply_t*)rpc_reply->temp;
    int return_num = read_reply->size;
    if (read_reply->size < 0) {
        errno = -1 * return_num;
        free(data);
        return -1;
    }
    else {// set the replied buf to input buf
        memcpy(buf, read_reply->buf, return_num);
        free(data);
        return return_num;
    }
}

ssize_t write(int fd, const void *buf1, size_t nbytes){
	fprintf(stderr, "write\n");
	// step 0. check if the fd is valid
	if (fd < 0){
		errno = EBADF;
		return -1;
	}
    if (fd < OFFSET) {fprintf(stderr, "client write\n"); return orig_write(fd, buf1, nbytes);}

	// step 1. marshall the information into struct
	int total = sizeof(RPC_t) + sizeof(Write_t) + nbytes;
	char* array = malloc(total);
	// marshall the write struct into RPC struct 
	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	rpc -> opcode = WRITE;
	Write_t *write = (Write_t *)(rpc->temp);
	// marshall the information of write into write struct
	write -> fd = fd;
	write -> nbytes = nbytes;
	memcpy(write -> buf, buf1, nbytes);

	// step 2. send struct to server
	int sv = send(sockfd, array, total, 0);
    if (sv < 0) {
		fprintf(stderr, "write send error");
        err(1,0);    
        return -1;
    }
	
	// step 3. get message back
	char rv_buf[MAXMSGLEN+1];
	rv = recv(sockfd, rv_buf, MAXMSGLEN, 0);// get message
	if (rv < 0) {
        fprintf(stderr, "write receive error");
        err(1,0);    
		return -1;
    }

	// step 4. return respond from server
	int result = atoi((char*)rv_buf);
	if (result < 0){
		errno = result * -1;
		fprintf(stderr, "write errno: %d\n", errno);
		free(array);
		return -1;
	}
	fprintf(stderr, "write result: %d\n", result);
	free(array);
	return result;
}

off_t lseek(int fd, off_t offset, int whence){
	fprintf(stderr, "lseek\n");
	if (fd < 0){
		errno = EBADF;
		return -1;
	}
    if (fd < OFFSET) {
        return orig_lseek(fd, offset, whence);
    }
	int total = sizeof(RPC_t) + sizeof(Lseek_t);
	char* array = malloc(total);
	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	rpc -> opcode = LSEEK;

	Lseek_t *lseek = (Lseek_t *)rpc->temp;
	lseek -> fd = fd;
	lseek -> offset = offset;
	lseek -> whence = whence;

	// send message to server
	// fprintf(stderr, "%s\n", msg);
    int sv = send(sockfd, array, total, 0);
    if (sv < 0) {
		fprintf(stderr, "lseek send error");
        err(1,0);    
        return -1;
    }

	// get message back
	char rv_buf[MAXMSGLEN+1];
	int rv = recv(sockfd, rv_buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) {
        fprintf(stderr, "lseek receive error");
        err(1,0);    
		return -1;
    }
	

	int result = atoi((char*)rv_buf);
	if (result < 0){
		errno = result * -1;
		fprintf(stderr, "lseek errno: %d\n", errno);
		free(array);
		return -1;
	}

	fprintf(stderr, "lseek result: %d\n", result);
	free(array);
	return result;
}

int stat(const char *path,  struct stat *statbuf){
	fprintf(stderr, "stat\n");
	int total = sizeof(RPC_t) + sizeof(Stat_request_t) + strlen(path) + 1;
	char* array = malloc(total);

	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	fprintf(stderr, "stat total size: %d\n", total);
	rpc -> opcode = STAT;

	Stat_request_t *stat = (Stat_request_t *)(rpc->temp);
	stat -> path_len = strlen(path) + 1;
	memcpy(stat -> path, path, strlen(path) + 1);
	
	// send struct to server
    int sv = send(sockfd, array, total, 0);
    if (sv < 0) {
		fprintf(stderr, "stat send error");
        err(1,0);    
        return -1;
    }

	// get message back
	// marshall to int respond + struct stat respond_stat
    char rv_buf[MAXMSGLEN+1];
    int rv;
    bool first = true; // set a flag for the first rv segment 
    int totalsize = 0; // stores the total len of receive buffer
    int nowsize = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client

	while ((rv = recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
		if (first){
			first = false;
			RPC_t *respond_struct = (RPC_t *)rv_buf;
			totalsize = respond_struct -> size;
			data = malloc(totalsize);
			memcpy(data, rv_buf, rv);

			fprintf(stderr, "received total size: %d\n", totalsize);
		}
		else{
			memcpy(data + nowsize, rv_buf, rv);
			fprintf(stderr, "copying\n");
		}
		nowsize += rv;
		if (nowsize >= totalsize){break;}
	}

	if (rv<0){
		err(1,0);
		return -1;
	}

	RPC_t *final_respond = (RPC_t *)data;
	Stat_reply_t *stat_reply = (Stat_reply_t *)final_respond -> temp;
	int res = stat_reply -> res;

	if (res < 0){
		errno = -1 * res;
		fprintf(stderr, "stat errno: %d\n", errno);
		free(data);
		return -1;
	}
	else{
		memcpy(statbuf, stat_reply -> buf, sizeof(struct stat));
		fprintf(stderr, "stat result : %d\n", res);
		free(data);
		return res;
	}
}

int unlink(const char *name){
	fprintf(stderr, "unlink\n");
	int total = sizeof(RPC_t) + sizeof(Unlink_t) + strlen(name) + 1;
	char* array = malloc(sizeof(char)*total);

	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	fprintf(stderr, "unlink send total size: %d\n", total);
	rpc -> opcode = UNLINK;

	Unlink_t *unlink = (Unlink_t *)(rpc->temp);
	unlink -> path_len = strlen(name) + 1;
	memcpy(unlink -> name, name, strlen(name) + 1);
	
	// send struct to server
	int sv = send(sockfd, array, total, 0);
	if (sv < 0) {
        fprintf(stderr, "unlink send error\n");
        err(1,0);    
        return -1;
    }
	
	// get message back　
	char rv_buf[MAXMSGLEN+1];
	rv = recv(sockfd, rv_buf, total, 0);	// get message
	if (rv<0){
        fprintf(stderr, "unlink receive error\n");
        err(1,0);    
		return -1;
    }
	

	int result = atoi((char*)rv_buf);
	if (result < 0){
		errno = result * -1;
		fprintf(stderr, "unlink errno: %d\n", errno);
		free(array);
		return -1;
	}
	fprintf(stderr, "unlink final result:  %d\n", result);
	free(array);
	return result;
}

ssize_t getdirentries(int fd, char *buf, size_t nbytes , off_t *basep){
	fprintf(stderr, "getdirentries\n");
    if (fd < 0){
		errno = EBADF;
		return -1;
	}
	if (fd < OFFSET){
        return orig_getdirentries(fd, buf, nbytes, basep);
    }
	
	int total = sizeof(RPC_t) + sizeof(Entries_request_t);
	char* array = malloc(total);

	RPC_t *rpc = (RPC_t *)array;
	rpc -> size = total;
	rpc -> opcode = ENTRIES;

	Entries_request_t *entries = (Entries_request_t *)(rpc->temp);
	entries -> fd = fd;
	entries -> nbytes = nbytes;
	entries -> basep = *basep;

	fprintf(stderr, "client getdirentries total size: %d\n", total);
	
	// send struct to server
	fprintf(stderr, "client sending entries\n");
    int sv = send(sockfd, array, total, 0);
    if (sv < 0) {
		fprintf(stderr, "client send entries error");
        err(1,0);    
        return -1;
    }

	// get message back
	// marshall to int respond + struct stat respond_stat
    char rv_buf[MAXMSGLEN+1];
    int rv;
    bool first = true; // set a flag for the first rv segment 
    int totalsize = 0; // stores the total len of receive buffer
    int nowsize = 0; // keep track of the current bytes received
    void *data = NULL; // stores the actual buffer data sent from client

	while ((rv = recv(sockfd, rv_buf, MAXMSGLEN, 0)) > 0) {
		fprintf(stderr, "client receiving getdirentries \n");
		if (first){
			first = false;
			fprintf(stderr, "first receive\n");
			RPC_t *rpc_reply_header = (RPC_t *)rv_buf;
			totalsize = rpc_reply_header -> size;
			fprintf(stderr, "getentries recv total size: %d\n", totalsize);
			data = malloc(totalsize);
			memcpy(data, rv_buf, rv);
		}
		else{
			memcpy(data + nowsize, rv_buf, rv);
			fprintf(stderr, "copying getdirentries\n");
		}
		nowsize += rv;
		if (nowsize >= totalsize){fprintf(stderr, "client finished getdirentries \n");break;}
	}

	fprintf(stderr, "exiting receive loop\n");

	if (rv<0){
		err(1,0);
		return -1;
	}

	RPC_t *final_respond = (RPC_t *)data;
	Entries_reply_t *entries_reply = (Entries_reply_t*)final_respond -> temp;
	fprintf(stderr, "marshall completed\n");
	int return_num = entries_reply->num;
	fprintf(stderr, "get respond completed\n");
	if (return_num < 0){
		errno = return_num * -1;
		fprintf(stderr, "read errno: %d\n", errno);
		free(data);
		return -1;
	}
	*basep = entries_reply -> basep;
	memcpy(buf, entries_reply -> buf, return_num);
	free(data);
	return return_num;
}


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
	// set function pointer orig_open to point to the original open function
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
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) { 
        err(1,0);
        fprintf(stderr, "connect error\n");
	}

	fprintf(stderr, "init mylib\n");

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
}