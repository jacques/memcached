#!/usr/bin/perl
use strict;
use Cache::Memcached;
use Getopt::Long;
use Time::HiRes qw(gettimeofday tv_interval);

use Data::Dumper;

$|++;

sub wrap_sub {
    my ($name, %args) = @_;
    no strict 'refs';
    no warnings 'redefine';
    my $oldcv = *{$name}{CODE};

    warn "Attempting to wrap a subroutine ('$name') which doesn't exist yet." unless $oldcv;
    *{$name} = sub {
        my @toafter;
        if ($args{before}) {
            @toafter = eval { $args{before}->(@_) };
            warn "before $name caused error: $@\n" if $@;
        }
        my $wa = wantarray;
        my @rv;
        if ($wa) {
            @rv = $oldcv->(@_);
        } else {
            $rv[0] = $oldcv->(@_);
        }
        if ($args{after}) {
            eval { $args{after}->(\@rv, @toafter) };
            warn "after $name caused error: $@\n" if $@;
        }
        return $wa ? @rv : $rv[0];
    };
}

my $indent = 0;

my @wrap_subs;

#push @wrap_subs, "Cache::Memcached::GetParser::new";
# push @wrap_subs, "Cache::Memcached::GetParserXS::parse_buffer";
foreach my $name (@wrap_subs) {
    wrap_sub($name,
                before => sub {
                    local $Data::Dumper::Indent = 0;
                    my $spaces = "  " x $indent++;
                    warn "${spaces}$name CALL   (" . Dumper(\@_) . ");\n";
                },
                after => sub {
                    local $Data::Dumper::Indent = 0;
                    my $spaces = "  " x --$indent;
                    warn "${spaces}$name RETURN (" . Dumper($_[0]) . ");\n";
                },
            );
}

my $gets = 1000;
my $max_key = 1000;
my $data_min = 2000;
my $data_max = 12_000;

die unless GetOptions(
		      'gets=i' => \$gets,
		      'maxkey=i' => \$max_key,
		      'data_min=i' => \$data_min,
		      'data_max=i' => \$data_max,
);

my $memd = new Cache::Memcached {
    'servers' => [ "127.0.0.1:11211",],
    'debug' => 0,
};

my %correct;
my $data_delta = $data_max - $data_min;

for my $k (1..$max_key) {
    my $val = join('', map { chr(64 + int rand (60)) } (1..($data_min + int rand $data_delta)));
    $correct{$k} = $val;
    my $rv = $memd->set($k, $val)
        or die "Failed to set $k";
    warn "init $k/$max_key\n" if $k % 100 == 0;
}

my $start_time = [gettimeofday];
my ($start_cpu_user, $start_cpu_sys) = times;

my $bad = 0;

for (1..$gets) {
    warn "round $_ of $gets\n" if $_ % 100 == 0;
    my @need;
    for (1..200) {
        my $k = 1 + int rand $max_key;
        push @need, $k;
    }
    my $vals = $memd->get_multi(@need);
    foreach my $k (@need) {
        #warn "Testing $k\n";
        if ($vals->{$k} eq $correct{$k}) {
       #     warn "Right value for $k\n";
        }
        else {
            warn "Wrong value for $k: length expected '" . length($correct{$k}) . "' got '" . length($vals->{$k}) . "'\n";
            $bad++;
        }
    }
}

my $elapsed = tv_interval ($start_time);

my ($end_cpu_user, $end_cpu_sys) = times;

my $cpu_user = $end_cpu_user - $start_cpu_user;
my $cpu_sys = $end_cpu_sys - $start_cpu_sys;

warn "$bad bad results\n";

warn "elapsed times: $elapsed wallclock, $cpu_user user, $cpu_sys system\n";

print "$data_min, $data_max, $elapsed, $cpu_user, $cpu_sys\n";
