/**
 * MemCached Java client, utility class for Socket IO
 * Copyright (c) 2003
 * Richard 'toast' Russo <russor@msoe.edu>
 * http://people.msoe.edu/~russor/memcached
 *
 *
 * This module is Copyright (c) 2003 Richard Russo.
 * All rights reserved.
 * You may distribute under the terms of the GNU General Public License
 * This is free software. IT COMES WITHOUT WARRANTY OF ANY KIND.
 *
 * @author  Richard 'toast' Russo <russor@msoe.edu>
 * @version 0.9.1
 */


package com.danga.MemCached;


import java.util.*;
import java.net.*;
import java.io.*;



class SockIO {
    Socket sock;
    DataInputStream in;
    DataOutputStream out;
    boolean closed = false;
    
    public SockIO(String host, int port) throws IOException {
        sock = new Socket(host,port);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
        
    }
    
    public void close() {
        closed = true;
        try {
            in.close();
            out.close();
            sock.close();
        } catch (IOException e) {
        }
        
    }
    public boolean isConnected() {
        return (closed && sock.isConnected());
    }
    
    public void readFully(byte[] b) throws IOException {
        in.readFully(b);
    }
    
    public String readLine() throws IOException {
        return in.readLine();
    }
    
    public void writeBytes(String s) throws IOException {
        out.writeBytes(s);
    }
    public void flush() throws IOException {
        out.flush();
    }
    public void write(byte[] b) throws IOException {
        out.write(b);
    }
    
}
