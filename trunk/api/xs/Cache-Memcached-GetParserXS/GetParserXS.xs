#include "EXTERN.h"
#include "perl.h"
#include "XSUB.h"

#include "ppport.h"

#include "const-c.inc"

#define DEST     0  /* destination hashref we're writing into */
#define NSLEN    1  /* length of namespace to ignore on keys */
#define ON_ITEM  2
#define BUF      3  /* read buffer */
#define STATE    4  /* 0 = waiting for a line, N = reading N bytes */
#define OFFSET   5  /* offsets to read into buffers */
#define FLAGS    6
#define KEY      7  /* current key we're parsing (without the namespace prefix) */

#define DEBUG    0

int get_nslen (AV* self) {
  SV** svp = av_fetch(self, NSLEN, 0);
  if (svp)
    return SvIV((SV*) *svp);
  return 0;
}

void set_key (AV* self, const char *key) {
  av_store(self, KEY, newSVpv(key, strlen(key)));
}

SV *get_key_sv (AV* self) {
  SV** svp = av_fetch(self, KEY, 0);
  if (svp)
    return (SV*) *svp;
  return 0;
}

SV *get_on_item (AV* self) {
  SV** svp = av_fetch(self, ON_ITEM, 0);
  if (svp)
    return (SV*) *svp;
  return 0;
}

void set_flags (AV* self, int flags) {
  av_store(self, FLAGS, newSViv(flags));
}

void set_offset (AV* self, int offset) {
  av_store(self, OFFSET, newSViv(offset));
}

void set_state (AV* self, int state) {
  av_store(self, STATE, newSViv(state));
}

HV* get_dest (AV* self) {
  SV** svp = av_fetch(self, DEST, 0);
  if (svp)
    return (HV*) SvRV(*svp);
  return 0;
}

int get_state (AV* self) {
  SV** svp = av_fetch(self, STATE, 0);
  if (svp)
    return SvIV((SV*) *svp);
  return 0;
}

SV* get_buffer (AV* self) {
  SV** svp = av_fetch(self, BUF, 0);
  if (svp)
    return *svp;
  return 0;
}

/* returns an answer, but also unsets ON_ITEM */
int final_answer (AV* self, int ans) {
  av_store(self, ON_ITEM, newSV(0));
  return ans;
}

int parse_buffer (SV* selfref) {
  AV* self = (AV*) SvRV(selfref);
  HV* ret = get_dest(self);
  SV* bufsv = get_buffer(self);
  STRLEN len;
  char* buf;
  char key[257];
  unsigned int itemlen;
  unsigned int flags;
  int scanned;
  int nslen = get_nslen(self);
  SV* on_item = get_on_item(self);

  if (DEBUG)
    printf("get_buffer (nslen = %d)...\n", nslen);

  while (1) {
    int rv;
    buf = SvPV(bufsv, len);

    if (DEBUG)
      printf(" buf (len=%d) = [%s]\n", len, buf);

    scanned = 0;
    rv = sscanf(buf, "VALUE %256s %u %u%n", key, &flags, &itemlen, &scanned);

    if (DEBUG)
      printf("rv=%d, scanned=%d, one=[%d], two=[%d]\n",
             rv, scanned, buf[scanned], buf[scanned+1]);

    if (rv >= 3 && scanned && buf[scanned] == '\r' && buf[scanned + 1] == '\n') {
      int p     = scanned + 2;      /* 2 to skip \r\n */
      int state = itemlen + 2;      /* 2 to include reading final \r\n, a different \r\n */
      int copy  = len - p > state ? state : len - p;
      char *barekey = key + nslen;

      if (DEBUG)
        printf("key=[%s], state=%d, copy=%d\n", key, state, copy);

      if (copy) {
        //SV*  newSVpv(const char*, STRLEN);
        //SV**  hv_store(HV*, const char* key, U32 klen, SV* val, U32 hash);
        /*  $ret->{$self->[KEY]} = substr($self->[BUF], $p, $copy) */
        hv_store(ret, barekey, strlen(barekey), newSVpv(buf + p, copy), 0);
        buf[p + copy - 1] = '\0';

        if (DEBUG)
          printf("doing store:  len=%d key=[%s] of data [%s]\n",
                 strlen(barekey), barekey,
                 buf + p);
      }

      /* delete the stuff we used */
      sv_chop(bufsv, buf + p + copy);

      if (copy == state) {
        dSP ;

         /* have it all? */
        ENTER ;
        SAVETMPS ;
        PUSHMARK(SP) ;
        XPUSHs(sv_2mortal(newSVpv(barekey, strlen(barekey))));
        XPUSHs(sv_2mortal(newSViv(flags)));
        PUTBACK ;
        call_sv(on_item, G_VOID | G_DISCARD);
        FREETMPS ;
        LEAVE ;

        set_offset(self, 0);
        set_state(self, 0);
        continue;
      } else {
        /* don't have it all... but buffer is now empty */
        set_offset(self, copy);
        set_flags(self, flags);
        set_key(self, barekey);
        set_state(self, state);

        if (DEBUG)
          printf("don't have it all.... have '%d' of '%d'\n",
                 copy, state);
        return 0; /* return saying '0', not done */
      }
    }

    if (strncmp(buf, "END\r\n", 5) == 0) {
      /* we're done successfully, return 1 to finish */
      return final_answer(self, 1);
    }


    /* # if we're here probably means we only have a partial VALUE
       # or END line in the buffer. Could happen with multi-get,
       # though probably very rarely. Exit the loop and let it read
       # more.

       # but first, make sure subsequent reads don't destroy our
       # partial VALUE/END line.
    */

    set_offset(self, len);
    return 0;
  }
}

int parse_from_sock_xx (SV* selfref, SV* sock, int sockfd) {
  int res;
  AV* self = (AV*) SvRV(selfref);
  HV* ret = get_dest(self);
  int state = get_state(self);

  if (state) {
    //res = read(sockfd, *buf, state);
  }

  printf("fileno = %d\n", sockfd);

  printf("got = %x, state = %d\n", ret, state);
  return -1;

  /*
    # where are we reading into?
    if ($self->[STATE]) { # reading value into $ret
        $res = sysread($sock, $ret->{$self->[KEY]},
                       $self->[STATE] - $self->[OFFSET],
                       $self->[OFFSET]);

        return 0
            if !defined($res) and $!==EWOULDBLOCK;

        if ($res == 0) { # catches 0=conn closed or undef=error
            $self->[ON_ITEM] = undef;
            return -1;
        }

        $self->[OFFSET] += $res;
        if ($self->[OFFSET] == $self->[STATE]) { # finished reading
            $self->[ON_ITEM]->($self->[KEY], $self->[FLAGS]);
            $self->[OFFSET] = 0;
            $self->[STATE]  = 0;
            # wait for another VALUE line or END...
        }
        return 0; # still working, haven't got to end yet
    }

    # we're reading a single line.
    # first, read whatever's there, but be satisfied with 2048 bytes
    $res = sysread($sock, $self->[BUF],
                   2048, $self->[OFFSET]);
    return 0
        if !defined($res) and $!==EWOULDBLOCK;
    if ($res == 0) {
        $self->[ON_ITEM] = undef;
        return -1;
    }

    $self->[OFFSET] += $res;

  */
}

MODULE = Cache::Memcached::GetParserXS		PACKAGE = Cache::Memcached::GetParserXS		

INCLUDE: const-xs.inc

int
parse_from_sock_xx ( self, sock, sockfd )
    SV *self
    SV *sock
    int sockfd

int
parse_buffer ( self )
    SV *self


