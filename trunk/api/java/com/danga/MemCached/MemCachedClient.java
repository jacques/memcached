/**
 * MemCached Java client
 * Copyright (c) 2003
 * Richard 'toast' Russo <russor@msoe.edu>
 * http://people.msoe.edu/~russor/memcached
 *
 * Originally translated from Brad Fitzpatrick's <brad@danga.com> MemCached Perl client
 * See the memcached website:
 * http://www.danga.com/memcached/
 *
 * This module is Copyright (c) 2003 Richard Russo.
 * All rights reserved.
 * You may distribute under the terms of the GNU General Public License
 * This is free software. IT COMES WITHOUT WARRANTY OF ANY KIND.
 *
 * @author  Richard 'toast' Russo <russor@msoe.edu>
 * @version 0.9.0
 */


package com.danga.MemCached;


import java.util.zip.*;
import java.util.*;
import java.util.Map.*;
import java.io.*;



/** This is a Java client for the memcached server available from
 *  <a href="http:/www.danga.com/memcached/">http://www.danga.com/mecmched/</a>.*/
public class MemCachedClient {
    
    final int F_COMPRESSED = 2; //same as perl flag... but not implemented
    final int F_SERIALIZED = 8;
    //using 8 (1 << 3) so other clients don't try to unpickle/unstore/whatever
    //things that are serialized... I don't think they'd like it. :)
    
    ArrayList buckets;
    HashMap host_dead;
    HashMap stats;
    HashMap sockets;
    boolean debug = false;
    boolean forceserial = false;
    
    double compress_savings = 0.20;
    boolean compress_enable = true;
    int compress_threshold = 1024; // FIXME: is this a reasonable default??
    
    /** Creates a new instance of MemCachedClient.
     *
     * By default, compression is enabled, with a threshold of 1024, and required
     * savings of 0.2; debug is disabled; forced serialization is disabld; and the
     * server list is empty.
     */
    public MemCachedClient() {
    }
    
    
    /** Sets the required change in size for value storage to use compression.
     * This rate is expressed as a decimal, for example 0.20 means that unless the data
     * becomes 20% smaller (it will then be at 80% of its original size), it will be
     * stored in uncompressed form.
     *
     * The value defaults to 0.20.
     * @param d required compression to store compressed data
     */
    public void set_compress_savings(double d) {
        compress_savings = d;
    }
    
    /** Enable storing compressed data, provided it meets the threshold and savings
     * requirements. If enabled, data will be stored in compressed form if it is
     * longer than the threshold length set with {@link #set_compress_threshold(int) set_compress_threshold()}
     * and compression results in a space savings of at least the rate set with
     * {@link #set_compress_savings(double) set_compress_savings()}.
     *
     * The default is that compression is enabled.
     *
     * Even if compression is disabled, compressed data will be automatically
     * decompressed.
     * @param b <CODE>true</CODE> to enable compression, <CODE>false</CODE> to disable compression
     */
    public void set_compress_enable(boolean b) {
        compress_enable = b;
    }
    
    /** Sets the required lenght for data to be considered for compression. If the
     * lenght of the data to be stored is not equal or larger than this value, it will
     * not be compressed.
     *
     * This defaults to 1024.
     * @param i required length of data to consider compression
     */
    public void set_compress_threshold(int i) {
        compress_threshold = i;
    }
    
    /** This function lets you tell the client to always serialize data.  This is mostly
     * useful for debugging; or if you don't want to store stringified numbers.
     * @param b True if you want to always serialize data, false if you want to let the client
     * decide.
     *
     */
    public void set_serial(boolean b) {
        forceserial = b;
    }
    
    /** Turns on or off debugging information.  If enabled, the client will print
     * status messages that may be helpful in tracking the progress of data through the
     * system.  If you're not having problems, this is probably not useful for you.
     * @param b <CODE>true</CODE>, if debugging messages are desired, <CODE>false</CODE> if debugging messages are not
     * desired
     */    
    public void set_debug(boolean b) {
        debug = b;
    }
    
    /** This lets you determine if the client will serialize everything, or only data
     * types that it doesn't know to stringify
     * @return true, if the client is serializing everything
     */
    public boolean get_serial() {
        return forceserial;
    }
    
    /** Set the list of servers to use. All servers have an equal weight (1)
     * @param serverlist An array of servers to use; servers should be in the form ip:port.  Hostnames
     * are acceptable.
     */
    public void set_servers(String[] serverlist) {
        set_servers(serverlist, null);
    }
    
    /** Sets the list of servers, and their weights; For best results keep the weights
     * as low as possible. If the weightlist is shorter than the serverlist, remaining
     * servers are defaulted to a weight of 1.
     * @param serverlist A list of servers, in the form ip:port. Hostnames are also acceptable
     * @param weightlist A list of weights for the servers. Negative or zero values will be ignored, and
     * the default weight of 1 will be used instead
     *
     */
    public void set_servers(String[] serverlist, int[] weightlist) {
/* I'm just going to construct the bucket list here, since this is the only place
 * that the server list is going to change. The perl client constructs it elsewhere. */
        buckets = new ArrayList();
        host_dead = new HashMap();
        sockets = new HashMap();
        
        for (int i = 0; i < serverlist.length; ++i) {
            if (weightlist != null && weightlist.length >i) {
                for (int j = 0; j < weightlist[i]; ++j) {
                    buckets.add(serverlist[i]);
                }
            } else {
                buckets.add(serverlist[i]);
            }
        }
    }
    
    private SockIO get_sock(Object key) {
        if (key.getClass() == Integer.class) {
            return get_sock(((Integer)key).intValue());
        } else {
            return get_sock(key.toString());
        }
    }
    
    
    private SockIO get_sock(String key) {
        return get_sock(hashfunc(key));
    }
    
    private SockIO get_sock(int key) {
        if ( buckets.size() == 0) {
            return null;
        }
        
        int tries = 0;
        int hv = key;
        while (tries++ < 20) {
            String host = (String) buckets.get(hv % buckets.size());
            SockIO sock = sock_to_host(host);
            if (sock != null) {
                return sock;
            }
            hv += hashfunc("" + tries + key);  // stupid, but works
        }
        return null;
    }
    
    private SockIO sock_to_host(String host) {
        Date now = new Date();
        int tmp = host.indexOf(":");
        String[] ip ={ host.substring(0,tmp), host.substring(tmp+1)};
        
        if ((host_dead.containsKey(host) && now.before((Date)host_dead.get(host))) ||
        (host_dead.containsKey(ip[0]) && now.before((Date)host_dead.get(ip[0])))) {
            return null;
        }
        
        if (sockets.containsKey(host) && ((SockIO)sockets.get(host)).isConnected()) {
            return (SockIO) sockets.get(host);
        }
        
        SockIO sock;
        try {
            sock = new SockIO(ip[0], Integer.decode(ip[1]).intValue());
            
        } catch (Exception e) {
            sock = null;
        }
        if (sock != null) {
            sockets.put(host, sock);
            host_dead.remove(host);
            host_dead.remove(ip);
            return sock;
        }
        now = new Date();
        host_dead.put(host, new Date(now.getTime() + 60000 + (int)(java.lang.Math.random() * 10000)));
        host_dead.put(ip[0], new Date(now.getTime() + 60000 + (int)(java.lang.Math.random() * 10000)));
        if (debug) {
            System.out.println("MemCachedClient: marking " + host + " (" + ip[0] + ") dead\n");
        }
        return null;
    }
    
    private int hashfunc(String key) {
        int hash = 0;
        for (int i = 0; i < key.length(); ++i) {
            hash = hash*33 + key.charAt(i);
        }
        return hash;
    }
    
    /** Forget that servers were unreachable. This is useful if a network connection has
     * been restored, and servers that were recently unreachable have become reachable
     * again.
     */
    public void forget_dead_hosts() {
        host_dead.clear();
    }
    
    /** Disconnect from all memcached servers. */
    public void disconnect_all() {
        Iterator i = sockets.values().iterator();
        while (i.hasNext()) {
            ((SockIO)i.next()).close();
            i.remove();
        }
    }
    
    /* wouldn't it be nice if java had default parameters like c? */
    
    /** Deletes a key from the server; only the key is specified.
     * @param key the key to be removed
     * @return true, if the data was deleted succesfully
     */
    public boolean delete(String key) {
        return delete(key,null, null);
    }
    /** Deletes a key from the server; the key, and a hash value are specified.
     * @return true, if the data was deleted succesfully
     * @param hash used to determine which server is responsible for the specified key
     * @param key the key to be removed
     */
    public boolean delete(String key, int hash) {
        return delete(key,null,new Integer(hash));
    }
    /** Deletes a key from the server; the key, and a hash value are specified.
     * @return true, if the data was deleted succesfully
     * @param hash used to determine which server is responsible for the specified key
     * @param key the key to be removed
     */
    public boolean delete(String key, Integer hash) {
        return delete(key, null, hash);
    }
    /** Deletes a key from the server; the key, and a delete time are specified. The item
     *  is immediately made non retreivable, however {@link #add(String, Object) add} and
     *  {@link #replace(String, Object) replace} will fail when used with the same key will
     *  fail, until the server reaches the specified time. However,
     *  {@link #set(String, Object) set} will succeed, and the new value will not
     *  be deleted.
     *
     * @return true, if the data was deleted succesfully
     * @param expiry when to expire the record
     * @param key the key to be removed
     */
    
    public boolean delete(String key, Date expiry) {
        return delete(key,expiry,null);
    }
    /** Deletes a key from the server; the key, a delete time are specified, and a hash value
     *  are specified. The item is immediately made non retreivable, however
     *  {@link #add(String, Object) add} and {@link #replace(String, Object) replace}
     *  will fail when used with the same key will fail, until the server reaches the
     *  specified time. However, {@link #set(String, Object) set} will succeed,
     *  and the new value will not be deleted.
     *
     * @return true, if the data was deleted succesfully
     * @param expiry when to expire the record.
     * @param hash used to determine which server is responsible for the specified key
     * @param key the key to be removed
     */
    public boolean delete(String key, Date expiry, int hash) {
        return delete(key, expiry, new Integer(hash));
    }
    
    /** Deletes a key from the server; the key, a delete time are specified, and a hash value
     *  are specified. The item is immediately made non retreivable, however
     *  {@link #add(String, Object) add} and {@link #replace(String, Object) replace}
     *  will fail when used with the same key will fail, until the server reaches the
     *  specified time. However, {@link #set(String, Object) set} will succeed,
     *  and the new value will not be deleted.
     *
     * @return true, if the data was deleted succesfully
     * @param expiry when to expire the record.
     * @param hash used to determine which server is responsible for the specified key
     * @param key the key to be removed
     */
    public boolean delete(String key, Date expiry, Object hash) {
        SockIO sock;
        if (hash != null) {
            sock = get_sock(hash);
        } else {
            sock = get_sock(key);
        }
        
        if (sock == null) {
            return false;
        }
        String command = "delete " + key;
        if (expiry != null) {
            command = command + " " + expiry.getTime() / 1000;
        }
        command = command + "\r\n";
        
        try {
            sock.out().writeBytes(command);
            sock.out().flush();
            
            command = sock.in().readLine();
            if (command.equals("DELETED")) {
                return true;
            }
        } catch (IOException e) {
            sock.close();
        }
        return false;
    }
    
    /* Blah.... Default parameters would be _REALLY_ nice here */
    
    /** Stores data on the server; only the key and the value are specified.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value) {
        return set("set", key, value, null, null);
    }
    /** Stores data on the server; the key, value, and a hash value are specified.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value, int hash) {
        return set("set",key,value,null,new Integer(hash));
    }
    /** Stores data on the server; the key, value, and a hash value are specified.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value, Integer hash) {
        return set("set",key,value, null, hash);
    }
    
    /** Stores data on the server; the key, value, and an expiration time are specified.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value, Date expiry) {
        return set("set",key,value,expiry,null);
    }
    /** Stores data on the server; the key, value, an expiration time, and a hash value are specified.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value, Date expiry, int hash) {
        return set("set",key,value, expiry, new Integer(hash));
    }
    /** Stores data on the server; the key, value, an expiration time, and a hash value are specified.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean set(String key, Object value, Date expiry, Object hash) {
        return set("set",key,value, expiry, hash);
    }
    
    
    /** Adds data to the server; only the key and the value are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value) {
        return set("add", key, value, null, null);
    }
    /** Adds data to the server; the key, value, and a hash value are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value, int hash) {
        return set("add",key,value,null,new Integer(hash));
    }
    /** Adds data to the server; the key, value, and a hash value are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value, Integer hash) {
        return set("add",key,value, null, hash);
    }
    
    /** Adds data to the server; the key, value, and an expiration time are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value, Date expiry) {
        return set("add",key,value,expiry,null);
    }
    /** Adds data to the server; the key, value, an expiration time, and a hash value are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value, Date expiry, int hash) {
        return set("add",key,value, expiry, new Integer(hash));
    }
    /** Adds data to the server; the key, value, an expiration time, and a hash value are specified.
     *  If data already exists for this key on the server or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean add(String key, Object value, Date expiry, Object hash) {
        return set("add",key,value, expiry, hash);
    }
    
    /** Updates data on the server; only the key and the value are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value) {
        return set("replace", key, value, null, null);
    }
    /** Updates data on the server; the key, value, and a hash value are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value, int hash) {
        return set("replace",key,value,null,new Integer(hash));
    }
    /** Updates data on the server; the key, value, and a hash value are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value, Integer hash) {
        return set("replace",key,value, null, hash);
    }
    
    /** Updates data on the server; the key, value, and an expiration time are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value, Date expiry) {
        return set("replace",key,value,expiry,null);
    }
    /** Updates data on the server; the key, value, an expiration time, and a hash value are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value, Date expiry, int hash) {
        return set("replace",key,value, expiry, new Integer(hash));
    }
    /** Updates data on the server; the key, value, an expiration time, and a hash value are specified.
     *  If data does not already exist for this key on the server, or if the key is being
     *  deleted, the specified value will not be stored.
     *  The server will automatically delete the value when the expiration time has been reached.
     *  If the value is not a String or numeric type, or if {@link #set_serial(boolean) set_serial(true)}
     *  has been called; the data will be Serialized prior to storage.
     *
     *  If compression is enabled, and the data is longer than the compression threshold,
     *  and compresses by at least the compression savings, the data will be stored in
     *  compressed form.
     *
     * @param key key to store data under
     * @param value value to store
     * @param hash used to determine which server is responsible for the specified key
     * @param expiry when to expire the record
     * @return true, if the data was successfully stored
     */
    public boolean replace(String key, Object value, Date expiry, Object hash) {
        return set("replace",key,value, expiry, hash);
    }
    
    
    private boolean set(String cmdname, String key, Object value, Date expiry, Object hash) {
        SockIO sock;
        if (hash != null) {
            sock = get_sock(hash);
        } else {
            sock = get_sock(key);
        }
        
        if (sock == null) {
            return false;
        }
        
        if (expiry == null) {
            expiry = new Date(0);
        }
        int flags = 0;
        
        byte[] val;
        
        if (!forceserial && (value.getClass() == String.class ||
        value.getClass() == Double.class ||
        value.getClass() == Float.class ||
        value.getClass() == Integer.class ||
        value.getClass() == Long.class ||
        value.getClass() == Byte.class ||
        value.getClass() == Short.class)) {
            val = value.toString().getBytes();
        } else  {
            if (debug) {
                System.out.println("MemCache: Serializing " + value.getClass().getName());
            }
            flags|= F_SERIALIZED;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                (new ObjectOutputStream(bos)).writeObject(value);
                val = bos.toByteArray();
            } catch (IOException e) {
                val = value.toString().getBytes();
            }
            
        }
        
        if (compress_enable && val.length > compress_threshold) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(val.length);
                GZIPOutputStream gos = new GZIPOutputStream(bos);
                gos.write(val);
                gos.finish();
                
                
                if (bos.size() <= ( int)((1 - compress_savings) *(double) val.length)) {
                    val = bos.toByteArray();
                    flags|= F_COMPRESSED;
                }
                
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }
        try {
            String cmd = cmdname + " " + key + " " + flags + " " +
            expiry.getTime() / 1000 + " " + val.length + "\r\n";
            sock.out().writeBytes(cmd);
            sock.out().write(val);
            sock.out().writeBytes("\r\n");
            sock.out().flush();
            
            String tmp = sock.in().readLine();
            if (tmp.equals("STORED")) {
                if (debug) {
                    System.out.println("MemCache: " + cmdname + " " + key + " = " + val);
                }
                return true;
            } else {
                System.out.println("MemCache:" + cmd + tmp);
            }
            
        } catch (IOException e) {
            sock.close();
        }
        return false;
        
        
        
    }
    
    
    /** Increment the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key) {
        return incrdecr("incr", key, 1, null);
    }
    /** Increment the value at the specified key by the specified increment,
     *  and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key, long inc) {
        return incrdecr("incr", key, inc, null);
    }
    /** Increment the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key, Object hash) {
        return incrdecr("incr", key, 1, hash);
    }
    /** Increment the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key, int hash) {
        return incrdecr("incr", key, 1, new Integer(hash));
    }
    /** Increment the value at the specified key by the specified increment,
     *  and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key, long inc, int hash) {
        return incrdecr("incr", key, inc, new Integer(hash));
    }
    /** Increment the value at the specified key by the specified increment,
     *  and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and does not check
     *  for overflow. Because Java lacks unsigned types, the value is returned as
     *  a 64-bit integer. The server will only increment a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long incr(String key, long inc, Object hash) {
        return incrdecr("incr", key, inc, hash);
    }
    
    /** Decrement the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key) {
        return incrdecr("decr", key, 1, null);
    }
    /** Decrement the value at the specified key by the specified increment,
     *  and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key, long inc) {
        return incrdecr("decr", key, inc, null);
    }
    /** Decrement the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key, Object hash) {
        return incrdecr("decr", key, 1, hash);
    }
    /** Decrement the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key, int hash) {
        return incrdecr("decr", key, 1, new Integer(hash));
    }
    
    /** Decrement the value at the specified key by the specified increment,
     *  and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key, long inc, int hash) {
        return incrdecr("decr", key, inc, new Integer(hash));
    }
    /** Decrement the value at the specified key by 1, and then return it.
     *
     *  Note that the server uses a 32-bit unsigned integer, and checks for
     *  underflow. In the event of underflow, the result will be zero.  Because
     *  Java lacks unsigned types, the value is returned as a 64-bit integer.
     *  The server will only decrement a value if it already exists;
     *  if a value is not found, -1 will be returned.
     *
     * @param key key where the data is stored
     * @param inc how much to increment by
     * @param hash used to determine which server is responsible for the specified key
     * @return -1, if the key is not found, the value after incrementing otherwise
     */
    public long decr(String key, long inc, Object hash) {
        return incrdecr("decr", key, inc, hash);
    }
    
    private long incrdecr(String cmdname, String key, long inc, Object hash) {
        SockIO sock;
        if (hash != null) {
            sock = get_sock(hash);
        } else {
            sock = get_sock(key);
        }
        
        if (sock == null) {
            return -1;
        }
        
        try {
            sock.out().writeBytes(cmdname + " " + key + " " + inc + "\r\n");
            sock.out().flush();
            String tmp = sock.in().readLine();
            return Long.decode(tmp).longValue();
        } catch (IOException e) {
            sock.close();
        } catch (NumberFormatException e) {
        }
        
        return -1;
    }
    
    /** Retrieve a key from the server.
     *
     *  If the data was compressed or serialized when compressed, it will automatically
     *  be decompressed or serialized, as appropriate. (Inclusive or)
     *
     *  Non-serialized data will be returned as a string, so explicit conversion to
     *  numeric types will be necessisary, if desired
     *
     * @param key key where data is stored
     * @return the object that was previously stored, or null if it was not previously stored
     */
    public Object get(String key) {
        return get(key, null);
    }
    
    /** Retrieve a key from the server, using a specific hash.
     *
     *  If the data was compressed or serialized when compressed, it will automatically
     *  be decompressed or serialized, as appropriate. (Inclusive or)
     *
     *  Non-serialized data will be returned as a string, so explicit conversion to
     *  numeric types will be necessisary, if desired
     *
     * @param key key where data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return the object that was previously stored, or null if it was not previously stored
     */
    public Object get(String key, int hash) {
        return get(key, new Integer(hash));
    }
    
    /** Retrieve a key from the server, using a specific hash.
     *
     *  If the data was compressed or serialized when compressed, it will automatically
     *  be decompressed or serialized, as appropriate. (Inclusive or)
     *
     *  Non-serialized data will be returned as a string, so explicit conversion to
     *  numeric types will be necessisary, if desired
     *
     * @param key key where data is stored
     * @param hash used to determine which server is responsible for the specified key
     * @return the object that was previously stored, or null if it was not previously stored
     */
    public Object get(String key, Object hash) {
        String[] k = { key };
        Object[] h = { hash };
        return get_multi(k,h).get(key);
    }
    
    
    /** Retrieve multiple keys from the memcache.
     *
     *  This is recommended over repeated calls to {@link #get(String) get()}, since it
     *  is more efficent.
     * @return a hashmap with entries for each key is found by the server,
     *      keys that are not found are not entered into the hashmap, but attempting to
     *      retrieve them from the hashmap gives you null.
     * @param keys keys to retrieve
     */
    public HashMap get_multi(String[] keys) {
        return get_multi(keys, null);
    }
    
    /** Retrieve multiple keys from the memcache. 
     *
     *  This is recommended over repeated calls to {@link #get(String) get()}, since it
     *  is more efficent.
     * @param keys keys to retrieve
     * @param hashes hash values used to determine which server to use for each key;
     *      if a hash is not provided for a key (either because there are more keys
     *      than hashes, or a hash value is specifically null) a hash will be computed.
     * @return a hashmap with entries for each key is found by the server,
     *      keys that are not found are not entered into the hashmap, but attempting to
     *      retrieve them from the hashmap gives you null.
     */
    public HashMap get_multi(String[] keys, Object[] hashes) {
        ArrayList socks = new ArrayList();
        HashMap sock_keys = new HashMap();
        for (int i = 0; i < keys.length; ++i) {
            SockIO sock;
            if (hashes!= null && hashes.length > i && hashes[i] != null) {
                sock = get_sock(hashes[i]);
            } else {
                sock = get_sock(keys[i]);
            }
            if (sock == null) {
                continue;
            }
            if (!sock_keys.containsKey(sock)) {
                sock_keys.put(sock, new StringBuffer());
                socks.add(sock);
            }
            ((StringBuffer)sock_keys.get(sock)).append(" " + keys[i]);
        }
        
        // Pass 1: send out requests
        
        for (int i = 0; i < socks.size(); ++i) {
            SockIO sock =null;
            try {
                sock = (SockIO) socks.get(i);
                sock.out().writeBytes("get" + (StringBuffer)sock_keys.get(sock) + "\r\n");
                sock.out().flush();
            } catch (IOException e) {
                sock.close();
            }
        }
        
        HashMap ret = new HashMap();
        // Pass 2: get results
        for (int i = 0; i < socks.size(); ++i) {
            load_items((SockIO) socks.get(i), ret);
        }
        
        if (debug) {
            Iterator i = ret.entrySet().iterator();
            while (i.hasNext()) {
                Entry e = (Entry)i.next();
                System.out.println("MemCache: got " + e.getKey() + " = " + e.getValue());
            }
        }
        return ret;
    }
    
    private void load_items(SockIO sock, HashMap hm) {
        try {
            while (true) {
                String line = sock.in().readLine();
                if (line == null) {
                    return;
                } else if (line.startsWith("VALUE")) {
                    StreamTokenizer st = new StreamTokenizer(new StringReader(line));
                    st.ordinaryChars(48, 57);
                    st.wordChars(48, 57); // add numbers to words
                    st.nextToken(); // skip VALUE token
                    st.nextToken();
                    String key = st.sval;
                    st.nextToken();
                    int flag = new Integer(st.sval).intValue();
                    st.nextToken();
                    int length = new Integer(st.sval).intValue();
                    byte[] buf = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int tmp = sock.in().read(buf, read, length - read);
                        if (tmp == -1) {
                            return;
                        }
                        read+= tmp;
                    }
                    
                    sock.in().readLine(); // clear out \r\n that should be left
                    // check for compression
                    Object o;
                    
                    if ((flag & F_COMPRESSED) != 0) {
                        try {
                            GZIPInputStream gzi = new GZIPInputStream(new ByteArrayInputStream(buf));
                            ByteArrayOutputStream bos = new ByteArrayOutputStream(buf.length);
                            // read the input stream, and write to a byte array output stream since
                            // we have to read into a byte array, but we don't know how large it
                            // will need to be, and we don't want to resize it a bunch
                            
                            byte[] tmp = new byte[1024];
                            int count;
                            while ((count = gzi.read(tmp)) != -1) {
                                bos.write(tmp,0,count);
                            }
                            
                            buf = bos.toByteArray();
                        } catch (IOException e) {
                        }
                    }
                    if ((flag & F_SERIALIZED) != 0) {
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(buf));
                        try {
                            o = ois.readObject();
                            if (debug) {
                                System.out.println("MemCache: Deserializing " + o.getClass().getName());
                            }
                        } catch (ClassNotFoundException e) {
                            o = new String(buf);
                        }
                    } else {
                        o = new String(buf);
                    }
                    hm.put(key, o);
                } else if (line.equals("END")) {
                    return;
                }
            }
        } catch (NumberFormatException e) {
            //this shouldn't happen...
            System.out.println("MemCache: The sever passed us bad numbers in get");
        } catch (IOException e) {
            sock.close();
        }
        
        
        
    }
    
}
