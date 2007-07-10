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
use Getopt::Long;

my $opt_tar = 0;
my $opt_upload = 0;
exit 1 unless GetOptions("tar" => \$opt_tar,
			 "upload" => \$opt_upload);

# chdir to the directory the script's at, so future
# paths need only be relative
use FindBin qw($Bin);
chdir $Bin or die "Couldn't cd to $Bin\n";

die "Must use --tar or --upload\n" unless $opt_tar || $opt_upload;

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
my $dir = "php-memcached-$ver";
my $dist = "$dir.tar.gz";

if ($opt_tar) {
    # make a fresh directory
    mkdir $dir or die "Couldn't make directory: $dir\n";

    # copy files to fresh directory
    foreach my $file (@manifest) {
	system("cp", $file, "$dir/$file")
	    and die "Error copying file $file\n";
    }

    # tar it up
    system("tar", "zcf", $dist, $dir) 
	and die "Error running tar.\n";
    
    # remove temp directory
    system("rm", "-rf", $dir)
	and die "Error cleaning up temp directory\n";

    print "$dist created.\n";
}

if ($opt_upload) {
    print "Uploading $dist...\n";
    system("scp", $dist, 'bradfitz@danga.com:memd/dist/')
	and die "Error uploading to memcached/dist\n";
}

print "Done.\n";
