#!/usr/bin/env python

from distutils.core import setup
import memcache

setup(name="python-memcache",
      version=memcache.__version__,
      author="Evan Martin",
      author_email="martine@danga.com",
      url="http://www.danga.com/memcached",
      py_modules=["memcache"])

