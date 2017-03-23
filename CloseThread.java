
public class CloseThread extends Thread {
	private StudentSocketImpl sock;
	
	public CloseThread(StudentSocketImpl sock){
		this.sock = sock;
	}
	
	@Override
	public void run(){
		while (sock.getState() != StudentSocketImpl.State.CLOSED){
			synchronized(sock){
				try {
					sock.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
