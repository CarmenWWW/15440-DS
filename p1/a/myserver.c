#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <signal.h>
#include <sys/wait.h>
#include <dirent.h>
#include "RPC.h"
#include "../include/dirtree.h"



void send_open(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "open\n");
	Open_t *open_struct = (Open_t*)(finalRPC -> temp);
	mode_t m = open_struct -> m;
	int flags = open_struct -> flags;
	int path_len = open_struct -> path_len;
	char *pathname = malloc(path_len);
	memcpy(pathname, open_struct -> path, path_len);

    int respond;
    if (flags & O_CREAT) {
        fprintf(stderr, "open with mode with pathname : %s\n", pathname);
        respond = open(pathname, flags, m) + OFFSET;
    }
    else {
        fprintf(stderr, "open with pathname: %s\n", pathname);
        respond = open(pathname, flags) + OFFSET;
        fprintf(stderr, "fd respond: %d\n", respond);
    }
	if (respond < OFFSET){
		respond = -1 * errno;
	}
	fprintf(stderr, "open respond is: ");
	fprintf(stderr, "%d\n", respond);
	free(pathname);

	char result[RETURNSIZE];
	sprintf(result, "%d", respond);
	send(sessfd, result, strlen(result), 0);
}

void send_close(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "close\n");
	Close_t *close_struct = (Close_t*)(finalRPC -> temp);
	int id = close_struct -> id - OFFSET;

	fprintf(stderr, "id: ");
	fprintf(stderr, "%d\n", id);

	int respond = close(id);

	if (respond < 0){
		respond = -1 * errno;
	}

	fprintf(stderr, "close respond is: ");
	fprintf(stderr, "%d\n", respond);

	char result[RETURNSIZE];
	sprintf(result, "%d", respond);
	send(sessfd, result, strlen(result), 0);
}

void send_write(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "write\n");
	Write_t *write_struct = (Write_t*)(finalRPC -> temp);
	int id = (write_struct -> id) - OFFSET;
	size_t nbytes = write_struct -> nbytes;

	void *bufWrite = malloc(nbytes);
	memcpy(bufWrite, write_struct -> buf, nbytes);

	int respond = write(id, bufWrite, nbytes);
	if (respond < 0){
		respond = -1 * errno;
	}

	fprintf(stderr, "write respond is: ");
	fprintf(stderr, "%d\n", respond);
	char result[RETURNSIZE];
	sprintf(result, "%d", respond);
	send(sessfd, result, strlen(result), 0);

	free(bufWrite);
}

void send_read(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "read\n");
	Read_request_t *read_struct = (Read_request_t*)(finalRPC -> temp);
	int id = (read_struct -> id) - OFFSET;
    size_t nbytes = read_struct -> nbytes;
	void* buf = malloc(nbytes);

	fprintf(stderr, "read id: %d\n", id);
	fprintf(stderr, "read nbytes: %ld\n", nbytes);
	
	int num = read(id, buf, nbytes);

	char *buffer = NULL;
	int len = 0;
	if (num < 0){
		num = -1 * errno;
		len = sizeof(RPC_t) + sizeof(Read_reply_t);
		buffer = malloc(len);
		RPC_t *rpc_struct = (RPC_t*)buffer;
		rpc_struct -> size = len;
		rpc_struct -> opcode = READ;
		Read_reply_t *reply_struct = (Read_reply_t *)rpc_struct -> temp;
		reply_struct -> size = num;
	}
	else {
        // on sucess, send the buf back to client
        len = sizeof(RPC_t) + sizeof(Read_reply_t) + num;
		buffer = malloc(len);
		RPC_t *rpc_struct = (RPC_t*)buffer;
		rpc_struct -> size = len;
		rpc_struct -> opcode = READ;
		Read_reply_t *reply_struct = (Read_reply_t *)rpc_struct -> temp;
		reply_struct -> size = num;   
		memcpy(reply_struct->buf, buf, num);    
    }
	
	// send struct to server
	int sv;
	while ((sv = send(sessfd, buffer, len, 0)) < 0){// send message; should check return value)
		continue;
	}	
	free(buffer);
	free(buf);
}

void send_lseek(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "lseek\n");
	Lseek_t *lseek_struct = (Lseek_t*)(finalRPC -> temp);
	int fd = (lseek_struct -> fd) - OFFSET;
	off_t offset = lseek_struct -> offset;
    int whence = lseek_struct -> whence;

	int respond = lseek(fd, offset, whence);
	if (respond < 0){
		respond = -1 * errno;
	}

	fprintf(stderr, "lseek respond is: ");
	fprintf(stderr, "%d\n", respond);

	char result[RETURNSIZE];
	sprintf(result, "%d", respond);
	send(sessfd, result, RETURNSIZE, 0);
}

void send_unlink(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "unlink\n");
	Unlink_t *unlink_struct = (Unlink_t*)(finalRPC -> temp);
	int path_len = unlink_struct -> path_len;
	char *name = malloc(path_len);
	memcpy(name, unlink_struct -> name, path_len);

	fprintf(stderr, "name: ");
	fprintf(stderr, "%s\n", name);

	int respond = unlink(name);
	if (respond < 0){
		respond = -1 * errno;
	}
	fprintf(stderr, "unlink respond is: %d\n", respond);
	

	char result[RETURNSIZE];
	sprintf(result, "%d", respond);
	send(sessfd, result, strlen(result), 0);
	free(name);
}

void send_stat(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "stat\n");
	Stat_request_t *stat_struct = (Stat_request_t*)(finalRPC -> temp);
	int path_len = stat_struct -> path_len;
	char *pathname = malloc(path_len);
	memcpy(pathname, stat_struct -> path, path_len);

	void *statbuf = malloc(sizeof(struct stat));

	int respond = stat(pathname, (struct stat*)statbuf);

	int len = sizeof(RPC_t) + sizeof(Stat_reply_t) + sizeof(struct stat);
	char *array = malloc(len);
	RPC_t *rpc_header = (RPC_t*)array;
	rpc_header -> size = len;
	rpc_header -> opcode = STAT;

	Stat_reply_t* stat_reply = (Stat_reply_t *)rpc_header -> temp;
	memcpy(stat_reply -> buf, statbuf, sizeof(struct stat));
	
	if (respond < 0){
		respond = errno * -1;
	}
	stat_reply -> res = respond;

	fprintf(stderr, "stat respond: %d\n", respond);

	// send struct to server
	int sv;
	while ((sv = send(sessfd, array, len, 0)) < 0){
		continue;
	}
	free(array);
	free(pathname);
	free(statbuf);
}


void send_entries(RPC_t* finalRPC, int sessfd){
	fprintf(stderr, "entries\n");
	Entries_request_t *entries_struct = (Entries_request_t*)(finalRPC -> temp);
	int fd = (entries_struct -> fd) - OFFSET;
    size_t nbytes = entries_struct -> nbytes;
	off_t basep = entries_struct -> basep;
	char* buf = malloc(nbytes);

	ssize_t num = getdirentries(fd, buf, nbytes, &basep);

	int len;

	fprintf(stderr, "server completed\n");
	if (num < 0){
		num = -1 * errno;
		len = sizeof(RPC_t) + sizeof(Entries_reply_t);
	}
	else{
		len = sizeof(RPC_t) + sizeof(Entries_reply_t) + num;
	}
	fprintf(stderr, "entries respond is: %ld\n", num);
	
	char *buffer = malloc(len);
	RPC_t *rpc_header = (RPC_t *)buffer;
	rpc_header -> size = len;
	rpc_header -> opcode = ENTRIES;
	Entries_reply_t *entries_reply = (Entries_reply_t *)rpc_header -> temp;
	entries_reply -> num = num;
	if (num >= 0){
		entries_reply -> basep = basep;
		memcpy(entries_reply -> buf, buf, num);
	}


	// send struct to client
	int sv;
	while ((sv = send(sessfd, buffer, len, 0)) < 0){
		continue;
	}
	free(buffer);
}

void send_RPC(void *buffer, int sessfd){
// step 3. see which function it correspond to, write respond
	RPC_t *finalRPC = (RPC_t *)buffer;
	if (finalRPC -> opcode == OPEN){send_open(finalRPC, sessfd);}
	else if (finalRPC -> opcode == CLOSE){send_close(finalRPC, sessfd);}
	else if (finalRPC -> opcode == WRITE){send_write(finalRPC, sessfd);}
	else if (finalRPC -> opcode == LSEEK){send_lseek(finalRPC, sessfd);}
	else if (finalRPC -> opcode == UNLINK){send_unlink(finalRPC, sessfd);}
	else if (finalRPC -> opcode == STAT){send_stat(finalRPC, sessfd);}
	else if (finalRPC -> opcode == READ){send_read(finalRPC, sessfd);}
	else if (finalRPC -> opcode == ENTRIES){send_entries(finalRPC, sessfd);}


	else{
		int respond = 0;
		const void * re = &respond;
		send(sessfd, re, 4, 0);
	}
}

void receiving(int sessfd){
	char buf[MAXMSGLEN + 1];
	fprintf(stderr, "server started receiving\n");
	while (1){
		// step 1. get size of byte we need
		int rv;
		int nowsize = 0;
		int totalsize = 0;
		void* buffer = NULL;
		bool first = true;

		while ((rv = recv(sessfd, buf, MAXMSGLEN, 0)) > 0) {
			fprintf(stderr, "start receiving from client\n");
			if (first){
				fprintf(stderr, "first receive from client\n");
				fprintf(stderr, "%d\n", rv);
				first = false;
				RPC_t *rpc = (RPC_t *)buf;
				totalsize = rpc -> size;
				fprintf(stderr, "total size : %d\n", totalsize);
				buffer = malloc(totalsize);
				memcpy(buffer, buf, rv);
			}
			else{
				fprintf(stderr, "keep receiving from client \n");
				memcpy(buffer + nowsize, buf, rv);
				fprintf(stderr, "get %d bytes\n", rv);
			}
			nowsize += rv;
			if (nowsize >= totalsize){fprintf(stderr, "finish receiving from client \n");break;}
		}

		if (rv<0){
			err(1,0);
			break;
		}

		if (totalsize == 0){
			fprintf(stderr, "total size receive error: 00");
		}

		send_RPC(buffer, sessfd);
		free(buffer);
	}
}

void handler(int sig){
    pid_t pid;
    while ((pid=waitpid(-1, 0, WNOHANG)) > 0) {
        continue;
    }
    return;
}

int main(int argc, char**argv) {
	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short)atoi(serverport);
	else port=15440;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0){
		err(1, 0);
		fprintf(stderr, "socket error");
	}			// in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0){
        err(1,0);
        fprintf(stderr, "bind error");
        return -1;
    }
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv<0) {
        err(1,0);
        fprintf(stderr, "listen error");
        return -1;
    }

	signal(SIGCHLD, handler);

	
	// main server loop, handle clients one at a time
	while(1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
        if (sessfd<0) {
            err(1,0);
            fprintf(stderr, "accept error");
        }
		if (fork()==0) { // child process
			receiving(sessfd); // handle client session 
			close(sessfd); // then close client session 
			exit(0); // then exit
		}
		close(sessfd); // parent does not need this
		// start receiving

	}
	// close socket
	close(sockfd);
	return 0;
}

