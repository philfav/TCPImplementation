import java.net.*;
import java.io.*;
import java.util.*;

class StudentSocketImpl extends BaseSocketImpl {

	// SocketImpl data members:
	// protected InetAddress address;
	// protected int port;
	// protected int localport;

	enum State {
		CLOSED, LISTEN, SYN_SENT, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, CLOSE_WAIT, FIN_WAIT_2, LAST_ACK, TIME_WAIT, CLOSING
	}

	private Demultiplexer D;
	private Timer tcpTimer;
	private State state;
	private int seq;
	private InetAddress connectedAddr;
	private int connectedPort;
	private int connectedSeq;
	//private HashMap timerMap;
	private TCPPacket lastPack;
	private TCPPacket lastAck;
	
	private final String[] stateText = { "CLOSED", "LISTEN", "SYN_SENT", "SYN_RCVD", "ESTABLISHED", "FIN_WAIT_1",
			"CLOSE_WAIT", "FIN_WAIT_2", "LAST_ACK", "TIME_WAIT", "CLOSING" };

	StudentSocketImpl(Demultiplexer D) { // default constructor
		this.D = D;
		state = State.CLOSED;
		//timerMap = new HashMap<State,TCPTimerTask>();
	}

	/**
	 * Connects this socket to the specified port number on the specified host.
	 *
	 * @param address
	 *            the IP address of the remote host.
	 * @param port
	 *            the port number.
	 * @exception IOException
	 *                if an I/O error occurs when attempting a connection.
	 */
	@Override
	public synchronized void connect(InetAddress address, int port) throws IOException {
		localport = D.getNextAvailablePort();
		seq = 5;
		
		connectedAddr = address;

		D.registerConnection(address, this.localport, port, this);
		TCPPacket syn = new TCPPacket(this.localport, port, seq, 8, false, true, false, 5, null);

		sendPacket(syn, connectedAddr);
		
		printTransition(State.CLOSED, State.SYN_SENT);

		while (state != State.ESTABLISHED) {
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Called by Demultiplexer when a packet comes in for this connection
	 * 
	 * @param p
	 *            The packet that arrived
	 */
	public synchronized void receivePacket(TCPPacket p) {

		TCPPacket response;

		switch (state) {
		case LISTEN:
			if (!p.synFlag || p.ackFlag)
				break;

			seq = p.ackNum;
			connectedSeq = p.seqNum;
			connectedAddr = p.sourceAddr;

			response = new TCPPacket(localport, p.sourcePort, seq, connectedSeq + 1, true, true, false, 5, null);

			sendPacket(response, connectedAddr);
			printTransition(state, State.SYN_RCVD);

			try {
				D.unregisterListeningSocket(localport, this);
				D.registerConnection(p.sourceAddr, localport, p.sourcePort, this);
			} catch (IOException e) {
				e.printStackTrace();
			}

			break;

		case ESTABLISHED:
			if (p.ackFlag && p.synFlag){
				tcpTimer.cancel();
				tcpTimer = null;
				sendPacket(lastPack, connectedAddr);
			}
			
			else if(p.finFlag){
				response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null);
				sendPacket(response, connectedAddr);
	
				printTransition(state, State.CLOSE_WAIT);
			}
			
			break;

		case FIN_WAIT_1:
			if (p.ackFlag && p.synFlag){
				sendPacket(lastPack, connectedAddr);
				tcpTimer.cancel();
				tcpTimer = null;
			}
			
			else if (p.ackFlag){
				printTransition(state, State.FIN_WAIT_2);
				tcpTimer.cancel();
				tcpTimer = null;
			}
			
			else if (p.finFlag) {
				seq = p.ackNum;
				connectedSeq = p.seqNum;

				response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null);
				lastAck = response;
				
				sendPacket(response, connectedAddr);

				printTransition(state, State.CLOSING);
			}

			break;
		case FIN_WAIT_2:
			if (!p.finFlag)
				break;
			
			response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null);
			lastAck = response;
			
			sendPacket(response, connectedAddr);

			printTransition(state, State.TIME_WAIT);

			createTimerTask(30, null);


			break;
		case LAST_ACK:
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
			if (p.ackFlag){
				tcpTimer.cancel();
				tcpTimer = null;
				
				printTransition(state, State.TIME_WAIT);
				createTimerTask(30, null);
			}

			break;
		case SYN_RCVD:
			if (!p.ackFlag && p.synFlag)
				this.sendPacket(lastPack, connectedAddr);
			
			else if (p.ackFlag){
				tcpTimer.cancel();
				tcpTimer = null;
				
				connectedPort = p.sourcePort;
	
				printTransition(state, State.ESTABLISHED);
			}
			
			break;

		case SYN_SENT:
			if (!p.ackFlag || !p.synFlag)
				break;
			
			tcpTimer.cancel();
			tcpTimer = null;
			
			connectedSeq = p.seqNum;

			response = new TCPPacket(localport, p.sourcePort, -2, p.seqNum + 1, true, false, false, 5, null);

			sendPacket(response, connectedAddr);

			connectedPort = p.sourcePort;

			printTransition(state, State.ESTABLISHED);

			break;
		case CLOSING:
			if (p.finFlag)
				sendPacket(lastPack, connectedAddr);
			
			else if (p.ackFlag){
				tcpTimer.cancel();
				tcpTimer = null;
				
				printTransition(state, State.TIME_WAIT);
	
				createTimerTask(30, null);
			}
			
			break;
			
		case CLOSE_WAIT:
			if (p.finFlag)
				sendPacket(lastPack, connectedAddr);
			
			break;
			
		case TIME_WAIT:
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
		default:
			break;

		}

		this.notifyAll();

	}

	/**
	 * Waits for an incoming connection to arrive to connect this socket to
	 * Ultimately this is called by the application calling
	 * ServerSocket.accept(), but this method belongs to the Socket object that
	 * will be returned, not the listening ServerSocket. Note that localport is
	 * already set prior to this being called.
	 */
	@Override
	public synchronized void acceptConnection() throws IOException {
		System.out.println("accpetConnection called");
		D.registerListeningSocket(this.localport, this);
		printTransition(State.CLOSED, State.LISTEN);

		while (state != State.ESTABLISHED) {
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Returns an input stream for this socket. Note that this method cannot
	 * create a NEW InputStream, but must return a reference to an existing
	 * InputStream (that you create elsewhere) because it may be called more
	 * than once.
	 *
	 * @return a stream for reading from this socket.
	 * @exception IOException
	 *                if an I/O error occurs when creating the input stream.
	 */
	@Override
	public InputStream getInputStream() throws IOException {
		// project 4 return appIS;
		return null;

	}

	/**
	 * Returns an output stream for this socket. Note that this method cannot
	 * create a NEW InputStream, but must return a reference to an existing
	 * InputStream (that you create elsewhere) because it may be called more
	 * than once.
	 *
	 * @return an output stream for writing to this socket.
	 * @exception IOException
	 *                if an I/O error occurs when creating the output stream.
	 */
	@Override
	public OutputStream getOutputStream() throws IOException {
		// project 4 return appOS;
		return null;
	}

	/**
	 * Closes this socket.
	 *
	 * @exception IOException
	 *                if an I/O error occurs when closing this socket.
	 */
	@Override
	public synchronized void close() throws IOException {

		if (connectedAddr == null)
			return;

		TCPPacket fin = new TCPPacket(this.localport, this.connectedPort, seq, connectedSeq + 1, false, false, true, 5,
				null);

		if (state == State.CLOSE_WAIT)
			lastAck = lastPack;
		
		sendPacket(fin, connectedAddr);

		if (state == State.ESTABLISHED)
			printTransition(state, State.FIN_WAIT_1);

		else if (state == State.CLOSE_WAIT)
			printTransition(state, State.LAST_ACK);

		CloseThread closer = new CloseThread(this);
		closer.run();
	}

	/**
	 * create TCPTimerTask instance, handling tcpTimer creation
	 * 
	 * @param delay
	 *            time in milliseconds before call
	 * @param ref
	 *            generic reference to be returned to handleTimer
	 */
	private TCPTimerTask createTimerTask(long delay, Object ref) {
		if (tcpTimer == null)
			tcpTimer = new Timer(false);
		return new TCPTimerTask(tcpTimer, delay, this, ref);
	}

	/**
	 * handle timer expiration (called by TCPTimerTask)
	 * 
	 * @param ref Generic reference that can be used by the timer to return
	 *            information.
	 */
	@Override
	public synchronized void handleTimer(Object ref) {

		tcpTimer.cancel();
		tcpTimer = null;
		
		// this must run only once the last timer (30 second timer) has expired
		if (state == State.TIME_WAIT){
			printTransition(state, State.CLOSED);
			try {
				D.unregisterConnection(connectedAddr, localport, connectedPort, this);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else{
			sendPacket(lastPack, connectedAddr);
		}
		
	}

	private void printTransition(State start, State end) {
		System.out.println("!!! " + stateText[start.ordinal()] + "->" + stateText[end.ordinal()]);
		state = end;
	}
	
	private void sendPacket(TCPPacket pack, InetAddress addr){
		TCPWrapper.send(pack, addr);
		lastPack = pack;
		if (!pack.ackFlag || pack.synFlag)
			createTimerTask(2000, null);
		
	}
	
	public State getState() {
		return state;
	}
}
