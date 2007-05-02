use Test::More tests => 2;
BEGIN { use_ok('Cache::Memcached::GetParserXS') };

my $parser = Cache::Memcached::GetParserXS->new({}, 0, sub {});
ok($parser, "Parser object was created");
