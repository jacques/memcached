<?php
/*
 * MemCached PHP client
 * Copyright (c) 2003
 * Ryan Gilfether <hotrodder@rocketmail.com>
 * http://www.gilfether.com
 *
 * Translated from Brad Fitzpatrick's <brad@danga.com> MemCached Perl client
 */

define("MC_VERSION", "1.0.4");
define("MC_BUFFER_SZ", 1024);

class MemCachedClient
{
	var $host_dead;
	var $cache_sock;
	var $debug;
	var $servers;
	var $active;
	var $buckets;
	var $bucketcount;


	function MemCachedClient($options = 0)
	{
		if(is_array($options))
		{
			$this->set_servers($options["servers"]);
			$this->debug = $options["debug"];
			$this->cache_sock = array();
		}

		return $this;
	}

	// $servers must be in the format described in the constructor
	function set_servers($servers)
	{
		$this->servers = $servers;
		$this->active = count($this->servers);
		$this->buckets = "";
		$this->bucketcount = 0;

		return $this;
	}


	function set_debug($do_debug)
	{
		$this->debug = $do_debug;
	}


	function forget_dead_hosts()
	{
		unset($this->host_dead);
	}


	// disconnect all open connections
	function disconnect_all()
	{
		foreach($this->cache_sock as $sock)
			socket_close($sock);

		unset($this->cache_sock);
	}


	// delete the key, return true on success, false on error
	function delete($key)
	{
		if(!$this->active)
			return 0;

		$sock = $this->get_sock($key);

		if(!$sock)
			return 0;

		if(is_array($key))
			$key = $key[1];

		// send the command to the server
		$cmd = "delete $key\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		while($offset < $cmd_len)
			$offset += socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

		// now read the server's response
		if(socket_read($sock, 7, PHP_NORMAL_READ) == "DELETED")
			return 1;

		return 0;
	}


	// Like set(), but only stores in memcache if the key doesn't already exist.
	function add($key, $val, $exptime = 0)
	{
		$this->_set("add", $key, $val, $exptime);
	}


	// Like set(), but only stores in memcache if the key already exists.
	function replace($key, $val, $exptime = 0)
	{
		$this->_set("replace", $key, $val, $exptime);
	}


	// Unconditionally sets a key to a given value in the memcache.
	function set($key, $val, $exptime = 0)
	{
		$this->_set("set", $key, $val, $exptime);
	}


	// Retrieves a key from the memcache.
	function get($key)
	{
		$val =& $this->get_multi($key);

		if(!$val)
			return null;

		return $val[$key];
	}


	// like get() but takes an array of keys
	function get_multi($keys)
	{
		$sock_keys = array();
		$socks = array();

		if(!$this->active)
			return null;

		if(!is_array($keys))
		{
			$arr[] = $keys;
			$keys = $arr;
		}

		foreach($keys as $k)
		{
			$sock = $this->get_sock($k);

			if($sock)
			{
				$k = is_array($k) ? $k[1] : $k;

				if(@!is_array($sock_keys[$sock]))
					$sock_keys[$sock] = array();

				// if $sock_keys[$sock] doesn't exist, create it
				if(!$sock_keys[$sock])
					array_push($socks, $sock);

				array_push($sock_keys[$sock], $k);
			}
		}

		if(!is_array($socks))
		{
			$arr[] = $socks;
			$socks = $arr;
		}

		foreach($socks as $s)
		{
			$this->_load_items($s, $val, $sock_keys[$sock]);
		}

		if($this->debug)
		{
			while(list($k, $v) = each($val))
				print "MemCache: got $k = $v\n";
		}

		return $val;
	}




	/**************************************************
	 * PRIVATE FUNCTIONS
	 **************************************************/

	// connects to the server
	function sock_to_host($host)
	{
		if(is_array($host))
			$host = array_shift($host);

		$now = time();

		// seperate the ip from the port, index 0 = ip, index 1 = port
		$conn = explode(":", $host);
		if(count($conn) != 2)
			return 0;

		if(($this->host_dead[$host] && $this->host_dead[$host] > $now) ||
		($this->host_dead[$conn[0]] && $this->host_dead[$conn[0]] > $now))
			return 0;

		// connect to the server, if it fails, add it to the host_dead
		$sock = socket_create (AF_INET, SOCK_STREAM, getprotobyname("TCP"));

		// we need surpress the error message if a connection fails
		if(!@socket_connect($sock, $conn[0], $conn[1]))
		{
			$this->host_dead[$host]=$this->host_dead[$conn[0]]=$now+60+intval(rand(0, 10));

			// only print an error if in debug mode
			if($this->debug)
				print "sock_to_host(): Failed to connect to ".$conn[0].":".$conn[1]."\n";

			return 0;
		}

		// success, add to the list of sockets
		$cache_sock[$host] = $sock;

		return $sock;
	}


	// returns the socket from the key
	function get_sock($key)
	{
		if(!$this->active)
			return null;

		$hv = is_array($key) ? intval($key[0]) : $this->_hashfunc($key);

		if(!$this->buckets)
		{
			$bu = array();

			foreach($this->servers as $v)
			{
				if(is_array($v))
				{
					for($i = 1;  $i <= $v[1]; ++$i)
						array_push($bu, $v[0]);
				}
				else
					array_push($bu, $v);
			}

			$this->buckets = $bu;
			$this->bucketcount = count($this->buckets);
		}

		$tries = 0;
		while($tries++ < 20)
		{
			$host = $this->buckets[$hv % $this->bucketcount];
			$sock = $this->sock_to_host($host);

			if($sock)
				return $sock;

			$hv += $this->_hashfunc($tries);
		}

		return null;
	}


	// private function. sends the command to the server
	function _set($cmdname, $key, $val, $exptime = 0)
	{
		if(!$this->active)
			return 0;

		$sock = $this->get_sock($key);
		if(!$sock)
			return 0;

		$flags = 0;
		$key = is_array($key) ? $key[1] : $key;

		$raw_val = $val;
		if($val)
		{
			$val = serialize($val);
			$flags |= 1;
		}

		$len = strlen($val);

		// send off the request
		$cmd = "$cmdname $key $flags $exptime $len\r\n$val\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		while($offset < $cmd_len)
			$offset += socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

		// now read the server's response
		if(socket_read($sock, 6, PHP_NORMAL_READ) == "STORED")
		{
			if($this->debug)
				print "MemCache: $cmdname $key = $raw_val\n";

			return 1;
		}
		else
		{
			if($this->debug)
				print "_set(): Did not receive STORED as the server response!\r\n";
		}

		return 0;
	}


	// private function. retrieves the value, and returns it unserialized
	function _load_items($sock, &$val, $sock_keys)
	{
		$val = array();
		$cmd = "get ";

		if(!is_array($sock_keys))
		{
			$arr[] = $sock_keys;
			$sock_keys = $arr;
		}

		foreach($sock_keys as $sk)
		{
			$cmd .= $sk." ";
		}

		$cmd .="\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		while($offset < $cmd_len)
			$offset += socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

		while(true)
		{
			$line = socket_read($sock, MC_BUFFER_SZ, PHP_NORMAL_READ);
			if(preg_match("/^VALUE (\S+) (\d+) (\d+)\r$/s", $line, $matches))
			{
				$rk = $matches[1];
				$flags = $matches[2];
				$len = $matches[3];

				if($flags)
					$flags_array[$rk] = $flags;

				$len_array[$rk] = $len;
				$bytes_read = 0;
				$buf = "";

				while($line = socket_read($sock, MC_BUFFER_SZ, PHP_NORMAL_READ))
				{
					// for some reason, we initally get a line containing only a /n
					// so let skip if we only get a /n or /r
					if($line == "\n" || $line == "\r")
						continue;

					$bytes_read += strlen($line);
					$buf .= $line;


					if($bytes_read == $len + 1)
					{
						$val[$rk] = substr($buf, 0, strlen($buf) - 1); // chop the \r
						continue 2;
					}


					if($bytes_read > $len)
					{
						if($this->debug)
							print "_load_items(): Received invalid data from the server!\r\n";

						return 0;
					}
				}

				continue;
			}

			if(substr($line, 0, 3) == "END")
			{
				foreach($sock_keys as $sk)
				{
					if(!isset($val[$sk]))
						continue;

					if(strlen($val[$sk]) != $len_array[$sk])
						continue;

					if($flags_array[$sk] & 1)
						$val[$sk] = unserialize($val[$sk]);
				}

				return 1;
			}

			if(strlen($line) == 0)
			{
				if($this->debug)
					print "_load_items(): Failed to receive END response from server!\r\n";

				return 0;
			}
		}
	}


	// private function. creates our hash
	function _hashfunc($num)
	{
		$hash = 0;

		foreach(preg_split('//', $num, -1, PREG_SPLIT_NO_EMPTY) as $v)
		{
			$hash = $hash * 33 + ord($v);
		}

		return $hash;
	}
}




/*
METHODS:
	// Takes one parameter, a array of options.  The most important key is
	// options["servers"], but that can also be set later with the set_servers()
	// method.  The servers must be an array of hosts, each of which is
	// either a scalar of the form <10.0.0.10:11211> or an array of the
	// former and an integer weight value.  (the default weight if
	// unspecified is 1.)  It's recommended that weight values be kept as low
	// as possible, as this module currently allocates memory for bucket
	// distribution proportional to the total host weights.
	// $options["debug"] turns the debugging on if set to true
	MemCachedClient::MemCachedClient($options);

	// sets up the list of servers and the ports to connect to
	// takes an array of servers in the same format as in the constructor
	MemCachedClient::set_servers($servers);

	// Retrieves a key from the memcache.  Returns the value (automatically
	// unserialized, if necessary) or null.
	// The $key can optionally be an array, with the first element being the
	// hash value, if you want to avoid making this module calculate a hash
	// value.  You may prefer, for example, to keep all of a given user's
	// objects on the same memcache server, so you could use the user's
	// unique id as the hash value.
	MemCachedClient::get($key);

	// just like get(), but takes an array of keys
	MemCachedClient::get_multi($keys)

	// Unconditionally sets a key to a given value in the memcache.  Returns true
	// if it was stored successfully.
	// The $key can optionally be an arrayref, with the first element being the
	// hash value, as described above.
	MemCachedClient::set($key, $value, $exptime);

	// Like set(), but only stores in memcache if the key doesn't already exist.
	MemCachedClient::add($key, $value, $exptime);

	// Like set(), but only stores in memcache if the key already exists.
	MemCachedClient::replace($key, $value, $exptime);

	// removes the key from the MemCache
	MemCachedClient::delete($key);

	// disconnects from all servers
	MemCachedClient::disconnect_all();

	// if $do_debug is set to true, will print out
	// debugging info, else debug is turned off
	MemCachedClient::set_debug($do_debug);

	MemCachedClient::forget_dead_hosts();


EXAMPLE:
	<?php
	require("MemCachedClient.inc.php");

	// set the servers, with the last one having an interger weight value of 3
	$options["servers"] = array("10.0.0.15:11000","10.0.0.16:11001",array("10.0.0.17:11002", 3));
	$options["debug"] = false;

	$memc = new MemCachedClient($options);


	// STORE AN ARRAY
	$myarr = array("one","two", 3);
	$memc->set("key_one", $myarr);
	$val = $memc->get("key_one");
	print $val[0]."\n";	// prints 'one'
	print $val[1]."\n";	// prints 'two'
	print $val[2]."\n";	// prints 3


	print "\n";


	// STORE A CLASS
	class tester
	{
		var $one;
		var $two;
		var $three;
	}

	$t = new tester;
	$t->one = "one";
	$t->two = "two";
	$t->three = 3;
	$memc->set("key_two", $t);
	$val = $memc->get("key_two");
	print $val->one."\n";
	print $val->two."\n";
	print $val->three."\n";


	print "\n";


	// STORE A STRING
	$memc->set("key_three", "my string");
	$val = $memc->get("key_three");
	print $val;		// prints 'my string'

	$memc->delete("key_one");
	$memc->delete("key_two");
	$memc->delete("key_three");

	$memc->disconnect_all();

	?>


This module is Copyright (c) 2003 Ryan Gilfether.
All rights reserved.

You may distribute under the terms of the GNU General Public License
This is free software. IT COMES WITHOUT WARRANTY OF ANY KIND.

See the memcached website:
   http://www.danga.com/memcached/

Ryan Gilfether <hotrodder@rocketmail.com>
http://www.gilfether.com
*/
?>









