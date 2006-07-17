/* win32.h
 *
 */

#ifndef WIN32_H
#define WIN32_H

#include <Winsock2.h>

#pragma warning(disable : 4996)

#if defined(_MSC_VER)
// for MSVC 6.0
typedef          __int64    int64_t;
typedef unsigned __int64    uint64_t;
typedef          int        int32_t;
typedef unsigned int        uint32_t;
#else
// default is GCC style
typedef          long long  int64_t;
typedef unsigned long long uint64_t;
typedef          int        int32_t;
typedef unsigned int        uint32_t;
#endif // _WIN32 && _MSC_VER

#define pid_t int

#undef errno
#define errno WSAGetLastError()
#define close(s) closesocket(s)
#define EWOULDBLOCK WSAEWOULDBLOCK
#define EAGAIN EWOULDBLOCK 
typedef int socklen_t;
#define O_BLOCK 0
#define O_NONBLOCK 1
#define F_GETFL 3
#define F_SETFL 4

int fcntl(SOCKET s, int cmd, int val);
int inet_aton(register const char *cp, struct in_addr *addr);

__inline size_t write(int s, void *buf, size_t len) {
	size_t ret = send(s, buf, len, 0);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
}
__inline size_t read(int s, void *buf, size_t len) {
	size_t ret = recv(s, buf, len, 0);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
}

#endif
