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
#define write(s, buf, len) send(s, buf, len, 0)
#define read(s, buf, len) recv(s, buf, len, 0)
#define EWOULDBLOCK WSAEWOULDBLOCK
#define EAGAIN EWOULDBLOCK 
typedef int socklen_t;
#define O_BLOCK 0
#define O_NONBLOCK 1
#define F_GETFL 3
#define F_SETFL 4

int fcntl(SOCKET s, int cmd, int val);
int inet_aton(register const char *cp, struct in_addr *addr);

#endif
