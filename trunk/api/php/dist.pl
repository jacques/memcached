#!/usr/bin/perl
#
# Simplify Brad's life.  I'm sure there's a PHP-specific way
# to do this (or there should be), but I don't do the PHP, 
# so this is my answer.
#
# Heavily documented for the PHP programmers who might be
# reading this.
#

use strict;

# chdir to the directory the script's at, so future
# paths need only be relative
use FindBin qw($Bin);
chdir $Bin or die "Couldn't cd to $Bin\n";

# files to distribute
my @manifest = qw(
                  ChangeLog
                  Documentation
                  MemCachedClient.inc.php
                  );

# figure out the version number
open (PHP, "MemCachedClient.inc.php") or die;
{ local $/ = undef; $_ = <PHP>; }  # suck in the whole file
close PHP;
die "Can't find version number\n" unless
    /MC_VERSION.+?(\d[^\'\"]+)/s;
my $ver = $1;

# make a fresh directory
my $dir = "php-memcached-$ver";
mkdir $dir or die "Couldn't make directory: $dir\n";

# copy files to fresh directory
foreach my $file (@manifest) {
    system("cp", $file, "$dir/$file")
        and die "Error copying file $file\n";
}

# tar it up
my $dist = "$dir.tar.gz";
system("tar", "zcf", $dist, $dir) 
    and die "Error running tar.\n";

# remove temp directory
system("rm", "-rf", $dir)
    and die "Error cleaning up temp directory\n";

print "Created $dist, uploading...\n";
system("scp", $dist, 'bradfitz@danga.com:memd/dist/')
    and die "Error uploading to memcached/dist\n";

print "Done.\n";
