#!/usr/bin/env python

"""pooltest

Bring up two memcaches on :11211 and :11212.  Try killing one or both.
If this code raises any exceptions, it's a bug."""

import memcache
import time

mc = memcache.Client(["127.0.0.1:11211", "127.0.0.1:11212"], debug=1)

def test_setget(key, val):
    print "Testing set/get {'%s': %s} ..." % (key, val),
    mc.set(key, val)
    newval = mc.get(key)
    if newval == val:
        print "OK"
    else:
        print "FAIL"

i = 0
while 1:
    test_setget("foo%d" % i, "bar%d" % i)
    time.sleep(1)
    i += 1
