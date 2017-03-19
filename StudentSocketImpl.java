import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private Demultiplexer D;
  private Timer tcpTimer;
  private State state;

  enum State {
	  CLOSED, LISTEN, SYN_SENT, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, CLOSE_WAIT, FIN_WAIT_2, LAST_ACK, TIME_WAIT
  }
  
  private final String[] stateText = {"CLOSED", "LISTEN", "SYN_SENT", "SYN_RCVD", "ESTABLISHED", "FIN_WAIT_1", "CLOSE_WAIT", "FIN_WAIT_2", "LAST_ACK", "TIME_WAIT"};
  
  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
    state = State.CLOSED;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException{
    localport = D.getNextAvailablePort();
    
    D.registerConnection(address, this.port, port, this);
    TCPPacket syn = new TCPPacket(this.localport, port, 5, 0, false, true, false, 5, null);
    
    TCPWrapper.send(syn, address);
    printTransition(State.CLOSED, State.SYN_SENT);
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
	  this.notifyAll();
	  
	  System.out.println(p.toString());
	  
	  switch (state){
	  case LISTEN:
		  if (!p.synFlag)
			  break;
		  
		  TCPPacket synAck = new TCPPacket(localport, p.sourcePort, 8, p.seqNum + 1, true, true, false, 5, null);
		  
		  TCPWrapper.send(synAck, p.sourceAddr);
		  printTransition(state, State.SYN_RCVD);
		  
		  try {
			D.unregisterListeningSocket(localport, this);
			D.registerConnection(p.sourceAddr, localport, p.sourcePort, this);
		  } 
		  catch (IOException e) {
			e.printStackTrace();
		  }
		  
		  break;
		  
	case CLOSED:
		break;
	case CLOSE_WAIT:
		break;
	case ESTABLISHED:
		break;
	case FIN_WAIT_1:
		break;
	case FIN_WAIT_2:
		break;
	case LAST_ACK:
		break;
	case SYN_RCVD:
		break;
	case SYN_SENT:
		break;
	case TIME_WAIT:
		break;
	default:
		break;
		  
	  }
	  
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
	  System.out.println("accpetConnection called");
	  D.registerListeningSocket(this.localport, this);
	  printTransition(State.CLOSED, State.LISTEN);
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }
  
  private void printTransition(State start, State end){
	  System.out.println("!!! " + stateText[start.ordinal()] + "->" + stateText[end.ordinal()]);
	  state = end;
  }
}
