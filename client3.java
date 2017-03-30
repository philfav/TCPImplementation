import java.net.Socket;

public class client3 {
	public static void main(String[] argv){
	    
	    if(argv.length!= 2){
	      System.err.println("usage: client1 <hostname> <hostport>");
	      System.exit(1);
	    }

	    try{
	    	TCPStart.start();
		    for (int x = 0; x < 5; x++){
		      
		      
		      Socket sock = new Socket(argv[0], Integer.parseInt(argv[1]));
	
		      System.out.println("got socket "+sock);
		      
		      Thread.sleep(10*1000);
	
		      sock.close();
		    }
	    }
	    catch(Exception e){
	      System.err.println("Caught exception:");
	      e.printStackTrace();
	    }
	  }
}
