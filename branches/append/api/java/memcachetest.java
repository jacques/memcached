/*
 * memcachetest.java
 *
 * Created on September 23, 2003, 12:23 AM
 */

/**
 *
 * @author  toast
 */
import com.danga.MemCached.*;
import java.util.ArrayList;

/** This is an example program using a MemCacheClient. */
public class memcachetest {
    
    /** Creates a new instance of memcachetest */
    public memcachetest() {
    }
    
    /** This runs through some simple tests of the MemCacheClient.
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        MemCachedClient mcc = new MemCachedClient();
        String[] serverlist = { "localhost:12345"}; //, "localhost:12346"};
        mcc.set_compress_enable(true);
        mcc.set_compress_savings(0.0); // compress everthing
        mcc.set_compress_threshold(0); // compress everthing
        mcc.set_servers(serverlist);
        //mcc.set_serial(true);
//       Integer foo = new Integer(-2);
        mcc.set("foo", "Your mother eats army boots, in the day time, with her friends. " +
                "English text should be nice and compressible.");
        
        Object tmp = mcc.get("foo");
        System.out.println(tmp);
        System.out.println("Sleeping ...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }

        while (mcc.get("foo") == null) {
            System.out.print(".");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }
            
        System.out.println(mcc.get("foo"));
       
        
        
        
        
        
    }
    
}
