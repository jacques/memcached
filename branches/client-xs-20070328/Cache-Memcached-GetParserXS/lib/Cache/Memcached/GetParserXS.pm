package Cache::Memcached::GetParserXS;

use 5.006;
use strict;
use warnings;
# We don't want to inherit from this, because our constants may be different.
# use base 'Cache::Memcached::GetParser';
use Carp;
use Errno qw( EINPROGRESS EWOULDBLOCK EISCONN );
use AutoLoader;

our $VERSION = '0.01';

require XSLoader;
XSLoader::load('Cache::Memcached::GetParserXS', $VERSION);

sub DEST;
sub NSLEN;
sub ON_ITEM;
sub BUF;
sub STATE;
sub OFFSET;
sub FLAGS;
sub KEY;
sub FINISHED;

sub new {
    my ($class, $dest, $nslen, $on_item) = @_;

    my $self = bless [], (ref $class || $class);

    $self->[DEST]     = $dest;
    $self->[NSLEN]    = $nslen;
    $self->[ON_ITEM]  = $on_item;
    $self->[BUF]      = '';
    $self->[STATE]    = 0;
    $self->[OFFSET]   = 0;
    $self->[FLAGS]    = undef;
    $self->[KEY]      = undef;
    $self->[FINISHED] = {};

    return $self
}

sub current_key {
    return $_[0][KEY];
}

# returns 1 on success, -1 on failure, and 0 if still working.
sub parse_from_sock {
    my ($self, $sock) = @_;
    my $res;

    # where are we reading into?
    if ($self->[STATE]) { # reading value into $ret
        my $ret = $self->[DEST];
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
            $self->[OFFSET] = 0;
            $self->[STATE]  = 0;
            # wait for another VALUE line or END...
        }
        return 0; # still working, haven't got to end yet
    }

    # we're reading a single line.
    # first, read whatever's there, but be satisfied with 2048 bytes
    $res = sysread($sock, $self->[BUF],
                   128*1024, $self->[OFFSET]);
    return 0
        if !defined($res) and $!==EWOULDBLOCK;
    if ($res == 0) {
        $self->[ON_ITEM] = undef;
        return -1;
    }

    $self->[OFFSET] += $res;

    my $answer = $self->parse_buffer;

    if ($answer > 0) {
        my $finished = $self->[FINISHED];
        my $finalize = $self->[ON_ITEM];

        while (my ($key, $flags) = each %$finished) {
            $finalize->($key, $flags);
        }
        $self->[ON_ITEM] = undef;
    }

    return $answer;
}

sub DESTROY {} # Empty definition, so AUTOLOAD doesn't catch it

# sub parse_buffer is defined in XS

sub AUTOLOAD {
    # This AUTOLOAD is used to 'autoload' constants from the constant()
    # XS function.

    my $constname;
    our $AUTOLOAD;
    ($constname = $AUTOLOAD) =~ s/.*:://;
    croak "&Cache::Memcached::GetParserXS::constant not defined" if $constname eq 'constant';
    my ($error, $val) = constant($constname);
    if ($error) { croak $error; }
    {
        no strict 'refs';
        # Fixed between 5.005_53 and 5.005_61
#XXX    if ($] >= 5.00561) {
#XXX        *$AUTOLOAD = sub () { $val };
#XXX    }
#XXX    else {
            *$AUTOLOAD = sub { $val };
#XXX    }
    }
    goto &$AUTOLOAD;
}

1;
__END__
# Below is stub documentation for your module. You'd better edit it!

=head1 NAME

Cache::Memcached::GetParserXS - Perl extension for blah blah blah

=head1 SYNOPSIS

  use Cache::Memcached::GetParserXS;
  blah blah blah

=head1 DESCRIPTION

Stub documentation for Cache::Memcached::GetParserXS, created by h2xs. It looks like the
author of the extension was negligent enough to leave the stub
unedited.

Blah blah blah.

=head2 EXPORT

None by default.



=head1 SEE ALSO

Mention other useful documentation such as the documentation of
related modules or operating system documentation (such as man pages
in UNIX), or any relevant external documentation such as RFCs or
standards.

If you have a mailing list set up for your module, mention it here.

If you have a web site set up for your module, mention it here.

=head1 AUTHOR

LiveJournal user, E<lt>lj@E<gt>

=head1 COPYRIGHT AND LICENSE

Copyright (C) 2006 by LiveJournal user

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself, either Perl version 5.8.4 or,
at your option, any later version of Perl 5 you may have available.


=cut
