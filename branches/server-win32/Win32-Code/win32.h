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
#else
// default is GCC style
typedef          long long  int64_t;
typedef unsigned long long uint64_t;
#endif // _WIN32 && _MSC_VER
typedef          int        int32_t;
typedef unsigned int        uint32_t;
typedef          short      int16_t;
typedef unsigned short      uint16_t;
typedef          char       int8_t;
typedef unsigned char       uint8_t;

#define pid_t int
#define close(s) closesocket(s)
#define EWOULDBLOCK WSAEWOULDBLOCK
#define EAGAIN EWOULDBLOCK 
#define E2BIG WSAEMSGSIZE
#define EAFNOSUPPORT WSAEOPNOTSUPP
typedef int socklen_t;

#undef errno
#define errno WSAGetLastError()

#define O_BLOCK 0
#define O_NONBLOCK 1
#define F_GETFL 3
#define F_SETFL 4

#define IOV_MAX 1024
struct iovec {
	u_long iov_len;  
	char FAR* iov_base;
};
struct msghdr
{
	void	*msg_name;			/* Socket name			*/
	int		 msg_namelen;		/* Length of name		*/
	struct iovec *msg_iov;		/* Data blocks			*/
	int		 msg_iovlen;		/* Number of blocks		*/
	void	*msg_accrights;		/* Per protocol magic (eg BSD file descriptor passing) */ 
	int		 msg_accrightslen;	/* Length of rights list */
};

int fcntl(SOCKET s, int cmd, int val);
int inet_aton(register const char *cp, struct in_addr *addr);

__inline int inet_pton(int af, register const char *cp, struct in_addr *addr)
{
    if(af != AF_INET) {
		WSASetLastError(EAFNOSUPPORT);
		return -1;
    }
    return inet_aton(cp, addr);
}

__inline size_t write(int s, void *buf, size_t len)
{
	size_t ret = send(s, buf, len, 0);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
}

__inline size_t read(int s, void *buf, size_t len)
{
	size_t ret = recv(s, buf, len, 0);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
}

#define MAXPACKETSIZE (1500-28)
__inline int sendmsg(int s, const struct msghdr *msg, int flags)
{
/*
	DWORD dwBufferCount;
	int error = WSASendTo((SOCKET) s,
		msg->msg_iov,
		msg->msg_iovlen,
		&dwBufferCount,
		flags,
		msg->msg_name,
		msg->msg_namelen,
		NULL,
		NULL
	);

	if(error == SOCKET_ERROR) {
		dwBufferCount = -1;
		error = WSAGetLastError();
		if(error == WSA_IO_PENDING) {
			WSASetLastError(EAGAIN);
		} else if(error == WSAECONNRESET) {
			return 0;
		}
	}
	return dwBufferCount;

/*/
	int ret;
	char *cp, *ep;
	char wrkbuf[MAXPACKETSIZE];

	int len = msg->msg_iovlen;
	struct iovec *iov = msg->msg_iov;
	for(cp = wrkbuf, ep = wrkbuf + MAXPACKETSIZE; len-- > 0; iov++) {
		char *pp = iov->iov_base;
		int plen = iov->iov_len;
		int clen = (ep - cp);
		while(plen > clen) {
			if(cp - wrkbuf) {
				memcpy(cp, pp, clen);
				ret = send(s, wrkbuf, MAXPACKETSIZE, flags);
				pp += clen;
				plen -= clen;
				cp = wrkbuf;
				clen = (ep - cp);
			} else {
				ret = send(s, pp, clen, flags);
				pp += clen;
				plen -= clen;
			}
			if(ret == -1 && WSAGetLastError() != WSAECONNRESET) return -1;
		}
		memcpy(cp, pp, plen);
		cp += plen;
	}
	ret = send(s, wrkbuf, (cp - wrkbuf), flags);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
/**/
}

#endif
