<?php
/*
 * MemCached PHP client
 * Copyright (c) 2003
 * Ryan Gilfether <hotrodder@rocketmail.com>
 * http://www.gilfether.com
 *
 * Translated from Brad Fitzpatrick's <brad@danga.com> MemCached Perl client
 */

define("MC_VERSION", "1.0.6");
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
		{
			if($this->debug)
				print "delete(): There are no active servers available\r\n";

			return FALSE;
		}

		$sock = $this->get_sock($key);

		if(!is_resource($sock))
			return FALSE;

		if(is_array($key))
			$key = $key[1];

		// send the command to the server
		$cmd = "delete $key\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		// now send the command
		while($offset < $cmd_len)
		{
			$result = socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

			if($result !== FALSE)
				$offset += $result;
			else if($offset < $cmd_len)
			{
            	if($this->debug)
				{
					$errno = socket_last_error($sock);
					print "_delete(): socket_write() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
				}

				return FALSE;
			}
		}

		// now read the server's response
		if(($retval = socket_read($sock, MC_BUFFER_SZ, PHP_NORMAL_READ)) === FALSE)
		{
			if($this->debug)
			{
				$errno = socket_last_error($sock);
				print "_delete(): socket_read() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
			}

			return FALSE;
		}

		// remove the \r\n from the end
		$retval = rtrim($retval);

		// now read the server's response
		if($retval == "DELETED")
			return TRUE;

        if($this->debug)
			print "_delete(): Failed to receive DELETED response from server. Received $retval instead.\r\n";

		return FALSE;
	}


	// Like set(), but only stores in memcache if the key doesn't already exist.
	function add($key, $val, $exptime = 0)
	{
		return $this->_set("add", $key, $val, $exptime);
	}


	// Like set(), but only stores in memcache if the key already exists.
	function replace($key, $val, $exptime = 0)
	{
		return $this->_set("replace", $key, $val, $exptime);
	}


	// Unconditionally sets a key to a given value in the memcache.
	function set($key, $val, $exptime = 0)
	{
		return $this->_set("set", $key, $val, $exptime);
	}


	// Retrieves a key from the memcache and returns its value,
	// else returns false.
	function get($key)
	{
		$val =& $this->get_multi($key);

		if(!$val)
			return FALSE;

		return $val[$key];
	}


	// like get() but takes an array of keys
	function get_multi($keys)
	{
		$sock_keys = array();
		$socks = array();
		$val = 0;

		if(!$this->active)
			return FALSE;

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
					$socks[] = $sock;

				$sock_keys[$sock][] = $k;
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


	// increments a numerical value by the value given in $value
	// otherwise assumes 1
	// ONLY WORKS WITH NUMERIC VALUES
	function incr($key, $value = "")
	{
    	return $this->_incrdecr("incr", $key, $value);
	}


	// decrements a numerical value by the value given in $value
	// otherwise assumes 1
	// ONLY WORKS WITH NUMERIC VALUES
	function decr($key, $value = "")
	{
    	return $this->_incrdecr("decr", $key, $value);
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
		{
			if($this->debug)
				print "sock_to_host(): Host address was not in the format of host:port\r\n";

			return FALSE;
		}

		if(($this->host_dead[$host] && $this->host_dead[$host] > $now) ||
		($this->host_dead[$conn[0]] && $this->host_dead[$conn[0]] > $now))
		{
			if($this->debug)
				print "sock_to_host(): The host $host is not available.\r\n";

			return FALSE;
		}

		// connect to the server, if it fails, add it to the host_dead below
		$sock = socket_create (AF_INET, SOCK_STREAM, getprotobyname("TCP"));

		// we need surpress the error message if a connection fails
		if(!@socket_connect($sock, $conn[0], $conn[1]))
		{
			$this->host_dead[$host]=$this->host_dead[$conn[0]]=$now+60+intval(rand(0, 10));

			if($this->debug)
				print "sock_to_host(): Failed to connect to ".$conn[0].":".$conn[1]."\r\n";

			return FALSE;
		}

		// success, add to the list of sockets
		$cache_sock[$host] = $sock;

		return $sock;
	}


	// returns the socket from the key, else FALSE
	function get_sock($key)
	{
		if(!$this->active)
		{
			if($this->debug)
				print "get_sock(): There are no active servers available\r\n";

			return FALSE;
		}

		$hv = is_array($key) ? intval($key[0]) : $this->_hashfunc($key);

		if(!$this->buckets)
		{
			$bu = array();

			foreach($this->servers as $v)
			{
				if(is_array($v))
				{
					for($i = 1;  $i <= $v[1]; ++$i)
						$bu[] =  $v[0];
				}
				else
					$bu[] = $v;
			}

			$this->buckets = $bu;
			$this->bucketcount = count($this->buckets);
		}

		$tries = 0;
		while($tries < 20)
		{
			$host = $this->buckets[$hv % $this->bucketcount];
			$sock = $this->sock_to_host($host);

			if(is_resource($sock))
				return $sock;

			$hv += $this->_hashfunc($tries);
			++$tries;
		}

		if($this->debug)
			print "get_sock(): get_sock(): Unable to retrieve a valid socket\r\n";

		return FALSE;
	}


	// private function. increments or decrements a
	// numerical value in memcached. this function is
	// called from incr() and decr()
	// ONLY WORKS WITH NUMERIC VALUES
	function _incrdecr($cmdname, $key, $value)
	{
		if(!$this->active)
		{
			if($this->debug)
				print "_incrdecr(): There are no active servers available\r\n";

			return FALSE;
		}

		$sock = $this->get_sock($key);
		if(!is_resource($sock))
		{
			if($this->debug)
				print "_incrdecr(): Invalid socket returned by get_sock()\r\n";

			return FALSE;
		}

		// something about stats

		if($value == "")
			$value = 1;

		$cmd = "$cmdname $key $value\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		// now send the command
		while($offset < $cmd_len)
		{
			$result = socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

			if($result !== FALSE)
				$offset += $result;
			else if($offset < $cmd_len)
			{
            	if($this->debug)
				{
					$errno = socket_last_error($sock);
					print "_incrdecr(): socket_write() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
				}

				return FALSE;
			}
		}

		// now read the server's response
		if(($retval = socket_read($sock, MC_BUFFER_SZ, PHP_NORMAL_READ)) === FALSE)
		{
			$errno = socket_last_error($sock);
			print "_incrdecr(): socket_read() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
		}

		// strip the /r/n from the end
		$retval = trim($retval);

		if(!is_numeric($retval))
			return FALSE;

		return $retval;
	}


	// private function. sends the command to the server
	function _set($cmdname, $key, $val, $exptime = 0)
	{
		if(!$this->active)
		{
			if($this->debug)
				print "_set(): There are no active servers available\r\n";

			return FALSE;
		}

		$sock = $this->get_sock($key);
		if(!is_resource($sock))
		{
			if($this->debug)
				print "_set(): Invalid socket returned by get_sock()\r\n";

			return FALSE;
		}

		$flags = 0;
		$key = is_array($key) ? $key[1] : $key;

		$raw_val = $val;
		if($val)
		{
			// we dont want to serialize a numeric value
			// because memcache wont know how to incr or decr it
			if(!is_numeric($val))
				$val = serialize($val);

			$flags |= 1;
		}

		$len = strlen($val);
		if (!is_int($exptime))
			$exptime = 0;

		// send off the request
		$cmd = "$cmdname $key $flags $exptime $len\r\n$val\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		while($offset < $cmd_len)
		{
			$result = socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

			if($result !== FALSE)
				$offset += $result;
			else if($offset < $cmd_len)
			{
            	if($this->debug)
				{
					$errno = socket_last_error($sock);
					print "_set(): socket_write() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
				}

				return FALSE;
			}
		}

		// now read the server's response
		if(($l_szResponse = socket_read($sock, 6, PHP_NORMAL_READ)) === FALSE)
		{
			if($this->debug)
			{
				$errno = socket_last_error($sock);
				print "_set(): socket_read() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
			}

			return FALSE;
		}

		if($l_szResponse == "STORED")
		{
			if($this->debug)
				print "MemCache: $cmdname $key = $raw_val\n";

			return TRUE;
		}

		if($this->debug)
			print "_set(): Did not receive STORED as the server response! Received $l_szResponse instead\r\n";

		return FALSE;
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
			$cmd .= $sk." ";

		$cmd .="\r\n";
		$cmd_len = strlen($cmd);
		$offset = 0;

		while($offset < $cmd_len)
		{
			$result = socket_write($sock, substr($cmd, $offset, MC_BUFFER_SZ), MC_BUFFER_SZ);

			if($result !== FALSE)
				$offset += $result;
			else if($offset < $cmd_len)
			{
            	if($this->debug)
				{
					$errno = socket_last_error($sock);
					print "_load_items(): socket_write() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
				}

				return FALSE;
			}
		}

		$len = 0;
		$buf = "";
		$flags_array = array();

		// now read the response from the server
		while($line = socket_read($sock, MC_BUFFER_SZ, PHP_BINARY_READ))
		{
			// check for a socket_read error
			if($line === FALSE)
			{
				if($this->debug)
				{
					$errno = socket_last_error($sock);
					print "_load_items(): socket_read() returned FALSE. Error $errno: ".socket_strerror($errno)."\r\n";
				}

				return FALSE;
			}

			if($len == 0)
			{
				if(preg_match("/^VALUE (\S+) (\d+) (\d+)\r$/s", $line, $matches))
				{
					$rk = $matches[1];
					$flags = $matches[2];
					$len = $matches[3];

					if($flags)
						$flags_array[$rk] = $flags;

					$len_array[$rk] = $len;
					$bytes_read = 0;


					// get the left over data after the header is read
					$line = substr($line, strpos($line, "\r\n")+1, strlen($line));
				}
				else
				{
					// something went wrong, we never recieved the header
					if($this->debug)
						print "_load_items(): Failed to recieve valid header!\r\n";

					return FALSE;
				}
			}

			if($line == "\r" || $line == "\n")
				continue;

			$bytes_read += strlen($line);
			$buf .= $line;

			// we read the all of the data, take in account
			// for the /r/nEND/r/n
			if($bytes_read == ($len + 7))
			{
				$end = substr($buf, $len+2, 3);
				if($end == "END")
				{
					$val[$rk] = substr($buf, 0, $len);

					foreach($sock_keys as $sk)
					{
						if(!isset($val[$sk]))
							continue;

						if(strlen($val[$sk]) != $len_array[$sk])
							continue;

						if($flags_array[$sk] & 1)
						{
							if(!is_numeric($val[$sk]))
								$val[$sk] = unserialize($val[$sk]);
						}
					}

					return TRUE;
				}
				else
				{
                	if($this->debug)
						print "_load_items(): Failed to receive END. Received $end instead.\r\n";

					return FALSE;
				}
			}

			// take in consideration for the "\r\nEND\r\n"
			if($bytes_read > ($len + 7))
			{
				if($this->debug)
					print "_load_items(): Bytes read is greater than requested data size!\r\n";

				return FALSE;
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
 * This module is Copyright (c) 2003 Ryan Gilfether.
 * All rights reserved.
 *
 * You may distribute under the terms of the GNU General Public License
 * This is free software. IT COMES WITHOUT WARRANTY OF ANY KIND.
 *
 * See the memcached website:
 * http://www.danga.com/memcached/
 *
 * Ryan Gilfether <hotrodder@rocketmail.com>
 * http://www.gilfether.com
 */
?>









