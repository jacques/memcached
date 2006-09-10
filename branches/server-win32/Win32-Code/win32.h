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
struct iovec
{
    char *iov_base;
    int   iov_len;
};

struct msghdr
{
	void	*msg_name;			/* Socket name			*/
	int		 msg_namelen;		/* Length of name		*/
	struct iovec *msg_iov;			/* Data blocks			*/
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
	int ret;
	u_char wrkbuf[MAXPACKETSIZE];
	int len = msg->msg_iovlen;
	struct iovec *iov = msg->msg_iov;
	u_char *cp, *ep;

	for(cp = wrkbuf, ep = wrkbuf + MAXPACKETSIZE; --len >= 0; iov++) {
		int plen = iov->iov_len;
		if (cp + plen >= ep) {
			WSASetLastError(E2BIG);
			return -1;
		}
		memcpy(cp, iov->iov_base, plen);
		cp += plen;
	}
	ret = send(s, (char*)wrkbuf, cp - wrkbuf, flags);
	if(ret == -1 && WSAGetLastError() == WSAECONNRESET) return 0;
	return ret;
}

#endif
