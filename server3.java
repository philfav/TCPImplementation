import java.net.ServerSocket;
import java.net.Socket;

public class server3 {
	public static void main(String[] argv){
	    
	    if(argv.length!= 1){
	      System.err.println("usage: server1 <hostport>");
	      System.exit(1);
	    }

	    try{
		    for (int x = 0; x < 5; x++){
		      TCPStart.start();
		      
		      ServerSocket sock = new ServerSocket(Integer.parseInt(argv[0]));
		      Socket connSock = sock.accept();
	
		      System.out.println("got socket "+connSock);
	
		      Thread.sleep(10*1000);
		      connSock.close();
		      sock.close();
		    }
	    }
	    catch(Exception e){
	      System.err.println("Caught exception "+e);
	    }
	  }
}
