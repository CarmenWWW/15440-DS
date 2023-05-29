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


#define MAXMSGLEN 100
#define MSGSIZE 15
#define OFFSET 814

void process_open(open_header_t *open_header, int sessfd) {
    fprintf(stderr, "start processing open\n");
    // unmarshall the parameters
    int flags = open_header->flags;
    mode_t mode = open_header->mode;
    int path_len = open_header->path_len;
    char *pathname = malloc(path_len);
    memcpy(pathname, open_header->path_ptr, path_len);

    fprintf(stderr, "get all inputs\n");
    // handle request 
    int fd;
    if (flags & O_CREAT) {
        fprintf(stderr, "open with mode with pathname : %s\n", pathname);
        fd = open(pathname, flags, mode);
    }
    else {
        fprintf(stderr, "open with pathname: %s\n", pathname);
        fd = open(pathname, flags);
        fprintf(stderr, "fd: %d\n", fd);
    }

    // error check for open, set errno
    // need to double check about errno settings
    if (fd < 0) {
        fd = -1 * errno;
    }
    else {
        fd += OFFSET;
    }
    fprintf(stderr, "process open successfully\n");
    free(pathname);

    // convert the int result to a msg string
    char msg[MSGSIZE];
    sprintf(msg, "%d", fd);
    // send the string back to the client
    send(sessfd, msg, strlen(msg), 0);
}

void process_close(close_header_t *close_header, int sessfd) {
    fprintf(stderr, "start processing close\n");
    // unmarshall the parameters
    int fd = close_header->fd - OFFSET;

    // handle request
    int res = close(fd);

    // error check for close, set errno
    if (res < 0) {
        res = -1 * errno;
    }

    // convert the int result to a msg string
    char msg[MSGSIZE];
    sprintf(msg, "%d", res);
    // sned the string back to client
    send(sessfd, msg, strlen(msg), 0);
}

void process_write(write_header_t *write_header, int sessfd) {
    fprintf(stderr, "start processing write\n");
    // unmarshall the parameters
    int fd = write_header->fd - OFFSET;
    int count = write_header->count;
    void *buf = malloc(count);
    memcpy(buf, write_header->buf_ptr, count);

    // handle request
    int num = write(fd, buf, count);

    // error check for write, set errno
    if (num < 0) {
        num = -1 * errno;
    }
    
    // convert the int result to a msg string
    char msg[MSGSIZE];
    sprintf(msg, "%d", num);
    // sned the string back to client
    send(sessfd, msg, strlen(msg), 0);
}

void process_lseek(lseek_header_t *lseek_header, int sessfd) {
    fprintf(stderr, "start processing lseek\n");
    // unmarshall the parameters
    int fd = lseek_header->fd - OFFSET;
    off_t offset = lseek_header->offset;
    int whence = lseek_header->whence;

    // handle request
    int off = lseek(fd, offset, whence);

    if (off < 0) {
        off = -1 * errno;
    }
        // convert the int result to a msg string
    char msg[MSGSIZE];
    sprintf(msg, "%d", off);
    // sned the string back to client
    send(sessfd, msg, strlen(msg), 0);
}


void process_unlink(unlink_header_t *unlink_header, int sessfd) {
    fprintf(stderr, "start processing unlink");
    // unmarshall the parameters
    int path_len = unlink_header->path_len;
    char *pathname = malloc(path_len);
    memcpy(pathname, unlink_header->path_ptr, path_len);

    int res = unlink(pathname);

    if (res < 0) {
        res = -1 * errno;
    }
    free(pathname);

    // convert the int result to a msg string
    char msg[MSGSIZE];
    sprintf(msg, "%d", res);
    // sned the string back to client
    send(sessfd, msg, strlen(msg), 0);
}

void process_read(read_request_header_t *read_header, int sessfd) {
    fprintf(stderr, "start processing read\n");
    // unmarshall the parameters
    int fd = read_header->fd - OFFSET;
    int count = read_header->count;
    void *buf = malloc(count);

    // handle request
    int num = read(fd, buf, count);

    char *buffer = NULL;
    int len = 0;
    // error handling for read
    if (num < 0) {
        num = -1 * errno;
        // when error, do not care about the content of the buffer
        len = sizeof(RPC_header_t) + sizeof(read_reply_header_t);
        buffer = malloc(len);
        RPC_header_t *rpc_header = (RPC_header_t*)buffer;
        rpc_header->tot_len = len;
        rpc_header->opcode = READ;
        read_reply_header_t *reply_header = (read_reply_header_t*)rpc_header->body_ptr;
        reply_header->size = num;
    }
    else {
        // on sucess, send the buf back to client
        len = sizeof(RPC_header_t) + sizeof(read_reply_header_t) + num;
        buffer = malloc(len);
        RPC_header_t *rpc_header = (RPC_header_t*)buffer;
        rpc_header->tot_len = len;
        rpc_header->opcode = READ;
        read_reply_header_t *reply_header = (read_reply_header_t*)rpc_header->body_ptr;
        reply_header->size = num;
        memcpy(reply_header->buf_ptr, buf, num);
    }

    int sd; 
    while ((sd = send(sessfd, buffer, len, 0)) < 0) {
        continue;
    }
    
}




void process_stat(stat_request_header_t *stat_header, int sessfd) {
    fprintf(stderr, "start processing stat");
    // unmarshall the parameters
    int path_len = stat_header->path_len;
    char *pathname = malloc(path_len);
    memcpy(pathname, stat_header->path_ptr, path_len);
    void *statbuf = malloc(sizeof(struct stat));

    int res = stat(pathname, (struct stat*)statbuf);

    // marshall the reply info
    int len = sizeof(RPC_header_t) + sizeof(stat_reply_header_t) + sizeof(struct stat);
    char *buffer = malloc(len);
    RPC_header_t *rpc_header = (RPC_header_t*)buffer;
    rpc_header->opcode = STAT;
    rpc_header->tot_len = len;
    stat_reply_header_t *stat_reply_header = (stat_reply_header_t*)rpc_header->body_ptr;
    memcpy(stat_reply_header->buf_ptr, statbuf, sizeof(struct stat));
   
    // error handling
    if (res < 0) {
        res = errno * -1;
    }
    stat_reply_header->res = res;

    int sd; 
    while ((sd = send(sessfd, buffer, len, 0)) < 0) {
        continue;
    }



}

void process_getdirentries(getdirentries_request_header_t *getdirentries_header, int sessfd) {
    fprintf(stderr, "start processing getdirentries");
    // unmarshall the parameters
    int fd = getdirentries_header->fd - OFFSET;
    size_t nbytes = getdirentries_header->nbytes;
    off_t basep = getdirentries_header->basep;
    char *buf = malloc(nbytes);

    int len;

    ssize_t num = getdirentries(fd, buf, nbytes, &basep);
    if (num < 0) {
        num = errno * -1;
        len = sizeof(RPC_header_t) + sizeof(getdirentries_reply_header_t);
    }
    else {
        len = sizeof(RPC_header_t) + sizeof(getdirentries_reply_header_t) + num;
    }

    // marshall reply 
    char *buffer = malloc(len);
    RPC_header_t *rpc_header = (RPC_header_t*)buffer;
    rpc_header->tot_len = len;
    rpc_header->opcode = GETDIRENTRIES;
    getdirentries_reply_header_t *getdirentries_reply = (getdirentries_reply_header_t *)rpc_header->body_ptr;
    getdirentries_reply->num = num;
    if (num >= 0) {
        getdirentries_reply->basep = basep;
        memcpy(getdirentries_reply->buf_ptr, buf, num);
    }

    // send the reply back to client
    int sd; 
    while ((sd = send(sessfd, buffer, len, 0)) < 0) {
        continue;
    }

}


int marshall_tree(struct dirtreenode *node, void *buf) {
    int buf_len = 0;
    queue_t *cur_queue = malloc(sizeof(queue_t));
    cur_queue->queue = malloc(sizeof(struct dirtreenode*));
    cur_queue->queue[0] = node;
    cur_queue->size = 1;

    queue_t *next_queue = malloc(sizeof(queue_t));
    next_queue->queue = NULL;
    next_queue->size = node->num_subdirs;

    while (cur_queue->size > 0) {
        // enter a new level, first create enough room for all nodes in the next
        // leve, and then reset the number of nodes in the next level
        next_queue->queue = malloc(sizeof(struct dirtreenode*) * next_queue->size);
        next_queue->size = 0;

        // deal with all nodes at the current level
        for (int i = 0; i < cur_queue->size; i++) {
            struct dirtreenode *cur_node = cur_queue->queue[i];
            // store the nodes in the current level into reply buffer
            getdirtree_reply_header_t *reply_header = (getdirtree_reply_header_t *)buf;
            memcpy(reply_header->path_ptr, cur_node->name, strlen(cur_node->name) + 1);
            reply_header->path_len = strlen(cur_node->name) + 1;
            reply_header->num_subdirs = cur_node->num_subdirs;
            buf += sizeof(getdirtree_reply_header_t) + strlen(cur_node->name) + 1;
            buf_len += sizeof(getdirtree_reply_header_t) + strlen(cur_node->name) + 1;
            // for the nodes at current level, push its children to next_queue
            // increment next_size
            for (int j = 0; j < reply_header->num_subdirs; j++) {
                struct dirtreenode *next = cur_node->subdirs[j];
                next_queue->size += next->num_subdirs;
                next_queue->queue[j] = next;
            }
        }

        free(cur_queue);
        cur_queue = next_queue;
    }
    free(next_queue);
    return buf_len;
}

void process_getdirtree(getdirtree_request_header_t *getdirtree_request, int sessfd) {
    int path_len = getdirtree_request->path_len;
    char *path = malloc(path_len);
    memcpy(path, getdirtree_request->path_ptr, path_len);
    
    int buf_len, res, len;
    void *buf = malloc(sizeof(getdirtree_reply_header_t));
    // get the dirtree
    struct dirtreenode *root = getdirtree(path);
    if (root == NULL) {
        res = errno * -1;
        getdirtree_reply_header_t *header = (getdirtree_reply_header_t *)buf;
        header->path_len = res;
        len = sizeof(RPC_header_t) + sizeof(getdirtree_reply_header_t);
    }
    else {
        buf_len = marshall_tree(root, buf);
        len = sizeof(RPC_header_t) + buf_len;
    }
    // marshall the data
    char *buffer = malloc(len);
    RPC_header_t *rpc_header = (RPC_header_t*)buffer;
    rpc_header->opcode = GETDIRTREE;
    rpc_header->tot_len = len;
    memcpy(rpc_header->body_ptr, buf, buf_len);

    int sd; 
    while ((sd = send(sessfd, buffer, len, 0)) < 0) {
        continue;
    }

}

// process different RPC requests sent from client using switch statement 
// via opcode
void process_RPC(void* data, int sessfd) {
    RPC_header_t *rpc_header = (RPC_header_t *)data;
    switch(rpc_header->opcode) {
        case OPEN: 
            process_open((open_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case CLOSE:
            process_close((close_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case WRITE:
            process_write((write_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case READ:
            process_read((read_request_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case LSEEK:
            process_lseek((lseek_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case UNLINK:
            process_unlink((unlink_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case STAT:
            process_stat((stat_request_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case GETDIRENTRIES:
            process_getdirentries((getdirentries_request_header_t *)rpc_header->body_ptr, sessfd);
            break;
        case GETDIRTREE:
            process_getdirtree((getdirtree_request_header_t*)rpc_header->body_ptr, sessfd);
            break;
        default:
            fprintf(stderr, "opcode does not exist\n");
            break;
    }
}

void serve(int sessfd) {
    char buf[MAXMSGLEN+1];

    // serve a client with multiple operations
    while(1) {
        fprintf(stderr, "server a client\n");
        int rv;
        bool flag = true; // set a flag for the first rv segment 
        int tot_len = 0; // stores the total len of buffer
        int cur_len = 0; // keep track of the current bytes received
        void *data = NULL; // stores the actual buffer data sent from client

        // need to make sure that all segments of info are successfully 
        // received for an operation
        while ((rv=recv(sessfd, buf, MAXMSGLEN, 0)) > 0) {
            fprintf(stderr, "start collecting an operation package\n");
            // first segment recv, take tot_len and malloc size for data
            if (flag) { 
                fprintf(stderr, "first segment received\n");
                // never enter this if statement again for this package
                flag = false; 
                RPC_header_t *rpc_header = (RPC_header_t*)buf;
                tot_len = rpc_header->tot_len;
                fprintf(stderr, "total length: %d\n", tot_len);
                data = malloc(tot_len);
                memcpy(data, buf, rv);
                fprintf(stderr, "receive %d bytes\n", rv);
            }
            else {
                fprintf(stderr, "keep collecting segments\n");
                memcpy(data + cur_len, buf, rv);
                fprintf(stderr, "receive %d bytes\n", rv);
            }
            cur_len += rv;
            fprintf(stderr, "current length is %d\n", cur_len);
            if (cur_len == tot_len) {
                fprintf(stderr, "finish collectin data for this operation\n");
                break;
            }
        }
        // either client closed connection, or error
        if (rv<0) {
            err(1, 0);
            break;
        }

        if (rv == 0) {
            break;
        }
        
        // process RPC requests with the complete buffer received
        process_RPC(data, sessfd);
        free(data);
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
    sockfd = socket(AF_INET, SOCK_STREAM, 0);    // TCP/IP socket
    if (sockfd<0) {
        err(1, 0);            // in case of error
        fprintf(stderr, "socket error");
    }

    
    // setup address structure to indicate server port
    memset(&srv, 0, sizeof(srv));            // clear it first
    srv.sin_family = AF_INET;            // IP family
    srv.sin_addr.s_addr = htonl(INADDR_ANY);    // don't care IP address
    srv.sin_port = htons(port);            // server port

    // bind to our port
    rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
    if (rv<0) {
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
        
        // get session socket
        sa_size = sizeof(struct sockaddr_in);
        sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
        if (sessfd<0) {
            err(1,0);
            fprintf(stderr, "accept error");
        }
        if (fork() == 0){
            serve(sessfd);
            close(sessfd);
            exit(0);
        }
        close(sessfd);
    }
    
    fprintf(stderr, "server shutting down cleanly\n");
    // close socket
    close(sockfd);

    return 0;
}


