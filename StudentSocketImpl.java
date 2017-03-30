import java.net.*;
import java.io.*;
import java.util.*;

class StudentSocketImpl extends BaseSocketImpl {

	// SocketImpl data members:
	// protected InetAddress address;
	// protected int port;
	// protected int localport;

	//All of the states for a TCP connection
	enum State {
		CLOSED, LISTEN, SYN_SENT, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, CLOSE_WAIT, FIN_WAIT_2, LAST_ACK, TIME_WAIT, CLOSING
	}

	private Demultiplexer D;
	private Timer tcpTimer;
	private State state;
	private int seq; //Local seq number
	private InetAddress connectedAddr; //Address of other side of TCP connection
	private int connectedPort; //Port number of other side of TCP connection
	private int connectedSeq; //Current sequence number of other side of TCP connection
	private TCPPacket lastPack; //The last non-ack packet sent (saved in case it is dropped)
	private TCPPacket lastAck;	//The last ack packet sent (saved in case it is dropped)
	
	//Used to print state transitions. The string representation of the state is at the index corresponding to it's partner's ordinal in the State enum
	private final String[] stateText = { "CLOSED", "LISTEN", "SYN_SENT", "SYN_RCVD", "ESTABLISHED", "FIN_WAIT_1",
			"CLOSE_WAIT", "FIN_WAIT_2", "LAST_ACK", "TIME_WAIT", "CLOSING" };

	StudentSocketImpl(Demultiplexer D) { // default constructor
		this.D = D;
		state = State.CLOSED; //Init to closed
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
		seq = 5; //Arbitrary starting seq number
		
		connectedAddr = address;

		D.registerConnection(address, this.localport, port, this);
		TCPPacket syn = new TCPPacket(this.localport, port, seq, 8, false, true, false, 5, null);

		sendPacket(syn, connectedAddr); //Send syn packet to initiate three-way handshake
		
		printTransition(State.CLOSED, State.SYN_SENT); //After sending syn, state transition

		//This thread will sleep until the requisite packets are received to transition to ESTABLISHED.
		//When it wakes up, the function will return.
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
			if (!p.synFlag || p.ackFlag) //Garbage packet
				break;
			
			//SYN received
			
			//Init values
			seq = p.ackNum;
			connectedSeq = p.seqNum;
			connectedAddr = p.sourceAddr;

			response = new TCPPacket(localport, p.sourcePort, seq, connectedSeq + 1, true, true, false, 5, null); //SYN+ACK in response to SYN

			sendPacket(response, connectedAddr);
			printTransition(state, State.SYN_RCVD);

			//Change socket type with Demultiplexer
			try {
				D.unregisterListeningSocket(localport, this);
				D.registerConnection(p.sourceAddr, localport, p.sourcePort, this);
			} catch (IOException e) {
				e.printStackTrace();
			}

			break;

		case ESTABLISHED:
			//Receiving a SYN+ACK in this state indicates a dropped ack, resend it
			if (p.ackFlag && p.synFlag) 
				sendPacket(lastAck, connectedAddr);
	
			
			else if(p.finFlag){
				seq = p.ackNum; //Update seq on FIN and SYN
				response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null); //ACK for fin
				sendPacket(response, connectedAddr);
	
				printTransition(state, State.CLOSE_WAIT);
			}
			
			break;

		case FIN_WAIT_1:
			//Receiving a SYN+ACK in this state indicates a dropped ack, followed by a close(). Resend the ack
			if (p.ackFlag && p.synFlag)
				sendPacket(lastAck, connectedAddr);
			
			//Ack for fin
			else if (p.ackFlag){
				printTransition(state, State.FIN_WAIT_2);
				tcpTimer.cancel(); //Cancel timer for the fin being acked
				tcpTimer = null;
			}
			
			//Transition to CLOSING state, received fin before ack
			else if (p.finFlag) {
				seq = p.ackNum;
				connectedSeq = p.seqNum;

				response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null); //Ack for fin

				sendPacket(response, connectedAddr);

				printTransition(state, State.CLOSING);
			}

			break;
			
		case FIN_WAIT_2:
			if (!p.finFlag) //Garbage packet
				break;
			
			//FIN received
			
			seq = p.ackNum;
			
			response = new TCPPacket(localport, p.sourcePort, -2, connectedSeq + 1, true, false, false, 5, null); //Ack for fin
			
			sendPacket(response, connectedAddr);

			printTransition(state, State.TIME_WAIT);

			createTimerTask(30 * 1000, null); //TIME_WAIT 30 second timer

			break;
			
		case LAST_ACK:
			//A FIN in this state indicates a dropped ack. Resend it.
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
			if (p.ackFlag){
				tcpTimer.cancel(); //Cancel timer for previously sent fin
				tcpTimer = null;
				
				printTransition(state, State.TIME_WAIT);
				createTimerTask(30 * 1000, null); //TIME_WAIT 30 second timer
			}

			break;
			
		case SYN_RCVD:
			//A SYN in this state indicates a dropped SYN+ACK. Resend it.
			//(The SYN+ACK is saved as a lastPack rather than lastAck; no difference, arbitrarily chosen)
			if (!p.ackFlag && p.synFlag)
				this.sendPacket(lastPack, connectedAddr);
			
			else if (p.ackFlag){
				tcpTimer.cancel(); //Cancel timer for sent SYN+ACK
				tcpTimer = null;
				
				connectedPort = p.sourcePort;
	
				printTransition(state, State.ESTABLISHED);
			}
			
			break;

		case SYN_SENT:
			if (!p.ackFlag || !p.synFlag) //Garbage packet
				break;
			
			//SYN+ACK received 
			
			tcpTimer.cancel(); //Cancel timer for sent SYN
			tcpTimer = null;
			
			seq = p.ackNum;
			connectedSeq = p.seqNum;

			response = new TCPPacket(localport, p.sourcePort, -2, p.seqNum + 1, true, false, false, 5, null); //Ack for received SYN+ACK

			sendPacket(response, connectedAddr);

			connectedPort = p.sourcePort;

			printTransition(state, State.ESTABLISHED);

			break;
			
		case CLOSING:
			//Receiving a FIN in this state indicates a dropped ack. Resend it.
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
			else if (p.ackFlag){
				tcpTimer.cancel(); //Cancel timer for sent fin
				tcpTimer = null;
				
				printTransition(state, State.TIME_WAIT);
	
				createTimerTask(30 * 1000, null); //30 second TIME_WAIT timer
			}
			
			break;
			
		case CLOSE_WAIT:
			//The only thing that can be received here is a retransmitted fin due to a dropped ack.
			//Resend the ack.
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
			break;
			
		case TIME_WAIT:
			//The only thing that can be received here is a retransmitted fin due to a dropped ack.
			//Resend the ack.
			if (p.finFlag)
				sendPacket(lastAck, connectedAddr);
			
			break;
			
		default:
			break;

		}

		this.notifyAll(); //Wake up any threads that may be waiting on a particular state transition.

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
		D.registerListeningSocket(this.localport, this);
		printTransition(State.CLOSED, State.LISTEN);

		//Thread will sleep until the connection is established
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
		//Sanity check, should never happen
		if (connectedAddr == null)
			return;

		TCPPacket fin = new TCPPacket(this.localport, this.connectedPort, seq, connectedSeq + 1, false, false, true, 5,
				null);

		sendPacket(fin, connectedAddr);

		//Two possible states in which a close() can be called
		if (state == State.ESTABLISHED)
			printTransition(state, State.FIN_WAIT_1);

		else if (state == State.CLOSE_WAIT)
			printTransition(state, State.LAST_ACK);

		//Starts a new thread that will wait until the connection is fully closed.
		//Allows the application to return immediately from close()
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
			notifyAll();
			try {
				D.unregisterConnection(connectedAddr, localport, connectedPort, this);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		//If a timer expires in any other state, indicates that an ack was not transmitted for a given packet.
		//Resend the packet.
		else{
			sendPacket(lastPack, connectedAddr);
		}
		
	}

	/**
	 * Prints out a state transition line.
	 * 
	 * @param start beginning state
	 * @param end ending state
	 */
	private void printTransition(State start, State end) {
		System.out.println("!!! " + stateText[start.ordinal()] + "->" + stateText[end.ordinal()]);
		state = end;
	}
	
	/**
	 * Wrapper function for sending a packet. Stores the packet in case it is dropped.
	 * 
	 * @param pack packet to be sent
	 * @param addr address to which to send the packet
	 */
	private void sendPacket(TCPPacket pack, InetAddress addr){
		TCPWrapper.send(pack, addr); //Actually send the packet
		
		//For FINs, ACKs, and SYN+ACKs, send the packet and start a retransmission timer.
		//Also, save it as the lastPack sent.
		if (!pack.ackFlag || pack.synFlag){
			lastPack = pack;
			createTimerTask(1000, null);
		}
		
		//For ACKs, no retransmission. Save the packet as the lastAck sent.
		else
			lastAck = pack;
	}
	
	/**
	 * Function to get the state of the connection. Used in the CloseThread.
	 * 
	 * @return state of connection
	 */
	public State getState() {
		return state;
	}
}
