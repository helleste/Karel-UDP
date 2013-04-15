// Author: Štěpán Heller (helleste)

package robot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.acl.LastOwnerException;
import java.util.ArrayList;

public class Robot {
	private static final byte[] DOWNLOAD = {0x01}; // Download a photo
	private static final byte[] UPLOAD = {0x02}; // Upload a photo

	public static void main(String[] args) throws IOException {
		if(args.length == 0) {
			System.out.println("Usage/Photo: java robot.Robot <hostname>");
			System.out.println("Usage/Firmware: java robot.Robot <hostname> <firmware.bin>");
		} else if(args.length == 1) {
			Connection connection = new Connection(args[0]);
			connection.init(DOWNLOAD);
			PhotoClient photoClient = new PhotoClient(connection);
			photoClient.run();
		} else if(args.length == 2) {
			Connection connection = new Connection(args[0]);
			connection.init(UPLOAD);
			FirmwareSender fwSender = new FirmwareSender(connection, args[1]);
			fwSender.run();
		}
	}
}

class Connection {
	public DatagramSocket socket;
	public DatagramPacket packet;
	public int conNum;
	public InetAddress address;
	private static final int SYN = 4; // SYN flag
	public static final int PORT = 4000; // port number on baryk
	private static final int SYN_LENGTH = 10; // syn packet length

	public Connection(String host) {
		try {
			this.address = InetAddress.getByName(host);
			this.socket = new DatagramSocket();
			this.socket.setSoTimeout(100); // Setting timeout for receive()
		}
		catch(UnknownHostException e) {
			System.out.println("Unknown host. Exiting.");
			System.exit(-1);
		}
		catch (SocketException e) {
			e.printStackTrace();
		}
	}

	// Establish connection with baryk
	public void init(byte[] command) throws IOException {
		boolean isSyn = false;
		PhotoPacket ppacket = new PhotoPacket(0, 0, 0, SYN, command);
		this.packet = ppacket.packPacket(this.address, PORT, SYN_LENGTH);
		System.out.print("SEND: ");
		ppacket.printPacket();

		// Send packet to baryk
		this.socket.send(this.packet);

		while(!isSyn) {
			DatagramPacket first = new DatagramPacket(new byte[265], 265);

			try{
				this.socket.receive(first);
			}
			catch(IOException e) {
				System.out.println("Timeout occured.");
				try {
					this.socket.send(this.packet);
				}
				catch(IOException f) {}

				continue;
			}

			// Create PhotoPacket from datagram
			ppacket = new PhotoPacket(first.getData());
			System.out.print("RCVD: ");
			ppacket.printPacket();

			if(ppacket.syn) {
				// Here we received syn and the connection is established
				this.socket.setSoTimeout(0);
				this.conNum = ppacket.conNum;
				isSyn = true;
				return;
			}
		}

		return;
	}
}

class PhotoClient {

	private DatagramPacket packet;
	private Connection connection;

	private static final int SYN = 4; // SYN flag
	private static final int FIN = 2; // FIN flag
	private static final int RST = 1	; // RST flag
	private static final short WIDTH = 2048; // width of a sliding window
	private static final int ACK_LENGTH = 9; // length of ack packet
	private static final int FIN_LENGTH = 9; // length of a fin packet

	private ArrayList<Boolean> flags = new ArrayList<Boolean>(); // Array of flags according to received packets
	private ArrayList<PhotoPacket> receivedPackets = new ArrayList<PhotoPacket>(); // Raw photo data

	int ack = 0; // Expected seqNum

	public PhotoClient(Connection connection) {
		this.connection = connection;
	}

	public void run() {

		PhotoPacket ppacket = null;

		try {

			// Receive second packet from baryk with real photo data
			this.packet = new DatagramPacket(new byte[265], 265, connection.address, connection.PORT);
			connection.socket.receive(this.packet);
			ppacket = new PhotoPacket(this.packet.getData(),this.packet.getLength());
			System.out.print("RCVD: ");
			ppacket.printPacket();

			int index = 0;

			while(!ppacket.fin) {
				if(isValid(ppacket)) {
					index = calcIndex(ppacket.seqNum);
					setSign(index); // Set flag in the array from received seqNum
					System.out.println("FLAGS SIZE: " + flags.size());
					savePacket(index, ppacket); // Save PhotoPacket to the array
					this.ack = findAck(); // Find ACK to send

					// Send confirmation packet to baryk
					ppacket = new PhotoPacket(connection.conNum, 0, this.ack, 0, new byte[0]);
					this.packet = ppacket.packPacket(connection.address, connection.PORT, ACK_LENGTH);
					System.out.print("\nSEND: ");
					ppacket.printPacket();
					connection.socket.send(this.packet);
				} 
//				else {
//					// TODO Send RST?
//					ppacket = new PhotoPacket(this.conNum, 0, this.ack, RST, new byte[0]);
//					
//					packPacket(ppacket);
//					System.out.println("SENDING RST!!!");
//					System.out.print("SEND: ");
//					ppacket.printPacket();
//					//this.socket.close();
//					//System.exit(0);
//				}

				// Receive new packet
				this.packet = new DatagramPacket(new byte[265], 265, connection.address, connection.PORT);
				connection.socket.receive(this.packet);
				ppacket = new PhotoPacket(this.packet.getData(), this.packet.getLength());
				System.out.print("RCVD: ");
				ppacket.printPacket();
				System.out.println("RCVD SIZE: " + this.packet.getLength());
			}

			// We have ppacket with fin flag on
			System.out.println("RECEIVING DATA FINISHED!");
			close();
			connection.socket.close();
			System.out.println("Saving photo…");
			savePhoto();
			System.out.println("PHOTO DATA SAVED.");
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}

	private void savePhoto() throws FileNotFoundException, IOException {
		FileOutputStream fos = new FileOutputStream("./fotka.png", false);

		for (int i = 0; i < this.receivedPackets.size(); i++) {
			try {
				fos.write(this.receivedPackets.get(i).data);
			}
			catch(IOException e) {
				e.printStackTrace();
			}
		}

		fos.close();
	}

	// Check if packet header is valid
	private boolean isValid(PhotoPacket ppacket) {

		if(ppacket.conNum == connection.conNum) return true;

		return false;
	}

	// End communication with baryk
	private void close() throws IOException{
		PhotoPacket ppacket = new PhotoPacket(connection.conNum, 0, this.ack, FIN, new byte[0]);

		this.packet = ppacket.packPacket(connection.address, connection.PORT, FIN_LENGTH);
		System.out.print("SEND: ");
		ppacket.printPacket();

		for (int i = 0; i < 20; i++) {
			// Send packet to baryk
			connection.socket.send(this.packet);
		}
	}

	// Calculate index from received seqNum
	private int calcIndex(int seqNum) {

		int mod = seqNum % 255;

		if(mod == 0) return seqNum/255;
		else {
			int overflow = 255 - mod;
			return (overflow * Character.MAX_VALUE + seqNum + overflow)/255;
		}
	}

	// Set flag in the array according to received index
	private void setSign(int index) {

		if(index >= this.flags.size()) {
			for (int i = this.flags.size(); i <= index; i++) {
				this.flags.add(i, false);
			}
		}

		this.flags.set(index, true);
		return;
	}

	// Add received packet to the array
	private void savePacket(int index, PhotoPacket photoPacket) {

		if(index >= this.receivedPackets.size()) {
			for (int i = this.receivedPackets.size(); i <= index; i++) {
				this.receivedPackets.add(i, null);
			}
		}

		this.receivedPackets.set(index, photoPacket);
		return;
	}

	// Find ACK to send
	private int findAck() {
		int ack = this.ack;
		boolean c;

		for(int i = calcIndex(this.ack); i < this.flags.size(); i++) {
			c = this.flags.get(i);
			if(c == false) break; // Found a hole in the array 
			if(i == this.flags.size() - 1) ack += this.receivedPackets.get(i).dLength;
			else ack += 255;
		}

		return ack;
	}
}

class PhotoPacket {

	public static final int HEADER_LENGTH = 9;

	public int conNum; // connection number from Robot after SYN
	public int seqNum; // sequence number
	public int ackNum; // acknowledgment number
	public int signs; // signs (SYN,FIN,RST)
	public boolean syn; // SYN sign
	public boolean fin; // FIN sign
	public boolean rst; // RST sign
	public byte[] data; // photo data
	public int dLength; // actual datagram length

	// Create new PhotoPacket with all parameters
	public PhotoPacket(int conNum, int seqNum, int ackNum, int signs, byte data[]) {

		this.conNum = conNum;
		this.seqNum = seqNum;
		this.ackNum = ackNum;
		this.signs = signs;
		this.data = data;
		if(this.data == null) this.data = new byte[0];
		parseSigns(signs);
	}

	// Create new PhotoPacket from received datagram
	public PhotoPacket(byte datagram[]) {

		this.conNum = (bToI(datagram[0]) << 24) + (bToI(datagram[1]) << 16)
				+ (bToI(datagram[2]) << 8) + bToI(datagram[3]);
		this.seqNum = (bToI(datagram[4]) << 8) + bToI(datagram[5]);
		this.ackNum = (bToI(datagram[6]) << 8) + bToI(datagram[7]);
		this.signs = bToI(datagram[8]);
		parseSigns(signs);

		int dataLength = datagram.length - HEADER_LENGTH;
		this.data = new byte[dataLength];

		for(int i = 0; i < dataLength; i++) {
			this.data[i] = datagram[HEADER_LENGTH + i];
		}
	}
	
	// Create new PhotoPacket from received datagram and given length
	public PhotoPacket(byte datagram[], int dataLength) {

		this.conNum = (bToI(datagram[0]) << 24) + (bToI(datagram[1]) << 16)
				+ (bToI(datagram[2]) << 8) + bToI(datagram[3]);
		this.seqNum = (bToI(datagram[4]) << 8) + bToI(datagram[5]);
		this.ackNum = (bToI(datagram[6]) << 8) + bToI(datagram[7]);
		this.signs = bToI(datagram[8]);
		parseSigns(signs);

		this.dLength= dataLength - HEADER_LENGTH;
		this.data = new byte[this.dLength];

		for(int i = 0; i < this.dLength; i++) {
			this.data[i] = datagram[HEADER_LENGTH + i];
		}
	}

	// Convert byte to int
	private int bToI(byte b) {

		return b & 0xFF;
	}

	// Fill my DatagramPacket with my custom PhotoPacket's data
	public DatagramPacket packPacket(InetAddress address, int port, int packetLength) {

		byte myPacket[] = new byte[packetLength];

		// Add connection number first
		myPacket[0] = (byte) ((this.conNum >> 24) % 256);
		myPacket[1] = (byte) ((this.conNum >> 16) % 256);
		myPacket[2] = (byte) ((this.conNum >> 8) % 256);
		myPacket[3] = (byte) ((this.conNum) % 256);

		// Then add the sequence number
		myPacket[4] = (byte) ((this.seqNum >> 8) % 256);
		myPacket[5] = (byte) ((this.seqNum) % 256);

		myPacket[7] = (byte) ((this.ackNum) % 256);
		// Then add the acknowledgment number
		myPacket[6] = (byte) ((this.ackNum >> 8) % 256);

		// The header ends with set of flags
		myPacket[8] = (byte) (this.signs);

		// At the end we fill the packet with data
		if(this.data != null) {
			for (int i = 0; i < this.data.length; i++) {
				myPacket[9 + i] = this.data[i];
			}
		}

		// Decide the sending length of the packet
		if(this.syn) packetLength = 10;

		// Create the final DatagramPacket
		return new DatagramPacket(myPacket, packetLength, address, port);

	}
	
	// Fill my DatagramPacket with my custom PhotoPacket's data
	public DatagramPacket packagePacket(InetAddress address, int port, int packetLength) {
		//TODO redo this and merge with the previous function
		byte myPacket[] = new byte[packetLength + 9];

		// Add connection number first
		myPacket[0] = (byte) ((this.conNum >> 24) % 256);
		myPacket[1] = (byte) ((this.conNum >> 16) % 256);
		myPacket[2] = (byte) ((this.conNum >> 8) % 256);
		myPacket[3] = (byte) ((this.conNum) % 256);

		// Then add the sequence number
		myPacket[4] = (byte) ((this.seqNum >> 8) % 256);
		myPacket[5] = (byte) ((this.seqNum) % 256);

		myPacket[7] = (byte) ((this.ackNum) % 256);
		// Then add the acknowledgment number
		myPacket[6] = (byte) ((this.ackNum >> 8) % 256);

		// The header ends with set of flags
		myPacket[8] = (byte) (this.signs);

		// At the end we fill the packet with data
		if(this.data != null) {
			for (int i = 0; i < packetLength; i++) {
				myPacket[9 + i] = this.data[i];
			}
		}

		// Decide the sending length of the packet
		if(this.syn) packetLength = 10;

		// Create the final DatagramPacket
		return new DatagramPacket(myPacket, packetLength + 9, address, port);

	}

	// Set flags from signs
	private void parseSigns(int signs) {

		switch (signs) {
		case 0:
			this.syn = false;
			this.fin = false;
			this.rst = false;
			break;
		case 1:
			this.rst = true;
			this.syn = false;
			this.fin = false;
			break;
		case 2:
			this.fin = true;
			this.syn = false;
			this.rst = false;
			break;
		case 4:
			this.syn = true;
			this.fin = false;
			this.rst = false;
			break;

		default:
			System.out.println("Reached default in parseSigns. This should never happen.");
			break;
		}
	}

	// Print PhotoPacket's data
	public void printPacket() {

		StringBuilder log = new StringBuilder();
		log.append("conNum: " + Integer.toHexString(this.conNum));
		log.append(" seqNum: " + this.seqNum);
		log.append(" signs: " + this.signs);
		log.append (" ack: " + (int) (char) this.ackNum);
		log.append(" SYN: " + this.syn);
		log.append(" FIN: " + this.fin);
		log.append(" RST: " + this.rst);
		if(this.data != null) {
			log.append(" dataLength: " + this.data.length);
			log.append(" DATA: " + bytesToHex(this.data));
		}

		System.out.println(log.toString());
	}

	// Create string from data's hexa
	// Inspiration from: http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
	public static String bytesToHex(byte[] bytes) {

		final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
		char[] hexChars = new char[bytes.length * 2];
		int v;

		for (int i = 0; i < bytes.length; i++) {
			v = bytes[i] & 0xFF;
			hexChars[i * 2] = hexArray[v >>> 4];
			hexChars[i * 2 + 1] = hexArray[v & 0x0F];
		}

		return new String(hexChars);
	}
}

/* UPLOAD */
class FirmwareSender{
	
	private DatagramPacket packet;
	private Connection connection; // Connection object for this sender
	private File fw; // Firmware file descriptor
	private FileInputStream fws; // Handler for reading from firmware binary
	private int windowStart; // First seq in current window
	private int windowEnd; // Last seq in current window
	private byte[] fileBytes; // Firmware binary byte storage
	private byte[] packetBytes; // Storage for bytes to send
	private int prevAck = -1; // Previous ack
	private int ackCount = 0; // Number of occurrences of this ack
	private int curAck = -1; // Current ack
	private boolean end = false; // End sign
	
	private static final int SYN = 4; // SYN flag
	private static final int FIN = 2; // FIN flag
	private static final int RST = 1	; // RST flag
	private static final short WIDTH = 2048; // width of a sliding window
	private static final int DATA_LENGTH = 264; // length od a data packet
	private static final int FIN_LENGTH = 9; // length of a fin packet
	
	public FirmwareSender(Connection connection, String filename) {
		this.connection = connection;
		
		try{
			this.fw = new File(filename);
			this.fws = new FileInputStream(this.fw);
			this.fileBytes = new byte[(int) fw.length()];
			System.out.println("FILE SIZE: " + this.fileBytes.length);
			this.packetBytes = new byte[255];
			this.fws.read(fileBytes);
			this.connection.socket.setSoTimeout(100);
		}
		catch(FileNotFoundException f) {
			System.out.println("File not found. Exiting.");
			System.exit(-1);
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		
		this.windowStart = 0;
		this.windowEnd = 1785;
	}
	
	public void run() throws IOException {
		PhotoPacket ppacket = null;

		sendFullWindow(ppacket);
		
		// Receive new packet
		this.packet = new DatagramPacket(new byte[9], 9, connection.address, connection.PORT);
		connection.socket.receive(this.packet);
		ppacket = new PhotoPacket(this.packet.getData());
		System.out.print("RCVD: ");
		ppacket.printPacket();
		System.out.println("RCVD SIZE: " + this.packet.getLength());
		
		
		while(!this.end) {
			if(ppacket.conNum == connection.conNum && this.curAck >= this.windowStart) {
				if(this.curAck == this.prevAck) this.ackCount++;
				else this.ackCount = 0;
				
				switch (this.ackCount) {
				case 0:
					this.prevAck = this.curAck;
					if(this.curAck > this.windowEnd) {
						this.windowStart = this.curAck;
						this.windowEnd = this.windowStart + 1785;
						sendFullWindow(ppacket);
					}
					else moveWindow(ppacket);
					break;
				case 1:
					break;
	
				default:
					sendPacket(ppacket);
					break;
				}
			}
			
			// Receive new packet
			this.packet = new DatagramPacket(new byte[9], 9, connection.address, connection.PORT);
			safeReceive(this.packet, ppacket);
			ppacket = new PhotoPacket(this.packet.getData());
			System.out.print("RCVD: ");
			ppacket.printPacket();
			this.curAck = calcAck(ppacket.ackNum);
			System.out.println("curAck: " + this.curAck);
			System.out.println("RCVD SIZE: " + this.packet.getLength());
		}
		
		System.out.println("SENDING FINISHED!");
	}
	
	// Send full Window of data to baryk
	private void sendFullWindow(PhotoPacket ppacket) throws IOException {
		int lastSize = 0;
		for (int i = this.windowStart; i <= this.windowEnd; i+=255) {
			// Send file packet to baryk
			if(this.fileBytes.length - i >= 255)
				System.arraycopy(this.fileBytes, i, this.packetBytes, 0, 255);
			else {
				lastSize = this.fileBytes.length - i;
				System.out.println("i: " + i);
				System.out.println("PREFINAL PACKET SIZE: " + lastSize);
				System.arraycopy(this.fileBytes, i, this.packetBytes, 0, lastSize);
				this.end = true;
			}
			ppacket = new PhotoPacket(connection.conNum, i, 0, 0, packetBytes);
			if(!end) this.packet = ppacket.packPacket(connection.address, connection.PORT, DATA_LENGTH);
			else this.packet = ppacket.packagePacket(connection.address, connection.PORT, lastSize);
			System.out.println("pckt length " + this.packet.getLength());
			System.out.print("\nSEND: ");
			ppacket.printPacket();
			connection.socket.send(this.packet);
			if(this.end) break;
		}
	}
	
	// Move window according to received ack
	private void moveWindow(PhotoPacket ppacket) throws IOException {
		int pW = (this.windowEnd - this.curAck) / 255;
		int lastSize = 0;
		this.windowStart = this.curAck;
		
		for (int i = this.windowEnd + 255; i <= this.windowStart + 1785; i+=255) {
			// Send file packet to baryk
			if(this.fileBytes.length - i >= 255)
				System.arraycopy(this.fileBytes, i, this.packetBytes, 0, 255);
			else { // TODO this.curAck must be same as this.fileBytes.length
				lastSize = this.fileBytes.length - i;
				System.out.println("i: " + i);
				System.out.println("PREFINAL PACKET SIZE: " + lastSize);
				System.arraycopy(this.fileBytes, i, this.packetBytes, 0, lastSize);
				this.end = true;
			}
			ppacket = new PhotoPacket(connection.conNum, i, 0, 0, packetBytes);
			if(!end) this.packet = ppacket.packPacket(connection.address, connection.PORT, DATA_LENGTH);
			else this.packet = ppacket.packagePacket(connection.address, connection.PORT, lastSize);
			System.out.println("pckt length " + this.packet.getLength());
			System.out.print("\nSEND: ");
			ppacket.printPacket();
			connection.socket.send(this.packet);
			if(this.end) break;
		}
		this.windowEnd = this.windowStart + 1785;
	}
	
	// Send one packet to baryk
	private void sendPacket(PhotoPacket ppacket) throws IOException {
		// Send file packet to baryk
		System.arraycopy(this.fileBytes, this.windowStart, this.packetBytes, 0, 255);
		ppacket = new PhotoPacket(connection.conNum, this.windowStart, 0, 0, packetBytes);
		this.packet = ppacket.packPacket(connection.address, connection.PORT, DATA_LENGTH);
		System.out.print("\nSEND: ");
		ppacket.printPacket();
		connection.socket.send(this.packet);
	}
	
	// Calculate appropriate ack
	private int calcAck(int ack) {

		int mod = ack % 255;

		if(mod == 0) return ack;
		else {
			int overflow = 255 - mod;
			return (overflow * Character.MAX_VALUE + ack + overflow);
		}
	}
	
	// Safe way to send a packet
	private void safeReceive(DatagramPacket packet, PhotoPacket ppacket) throws IOException {
		try {
			connection.socket.receive(packet);
		}
		catch(SocketTimeoutException e) {
			System.out.println("Timeout occurred.");
			sendFullWindow(ppacket);
			safeReceive(packet, ppacket);
		}
		
		return;
	}
	
	// End communication with baryk
	private void close() throws IOException{
		// TODO proper finish
		PhotoPacket ppacket = new PhotoPacket(connection.conNum, this.fileBytes.length, 0, FIN, new byte[0]);

		this.packet = ppacket.packPacket(connection.address, connection.PORT, FIN_LENGTH);
		System.out.print("SEND: ");
		ppacket.printPacket();
		
		connection.socket.send(this.packet);
		connection.socket.receive(packet);
		ppacket = new PhotoPacket(this.packet.getData());
		System.out.print("RCVD: ");
		ppacket.printPacket();
		
		while(!ppacket.fin) {
			ppacket = new PhotoPacket(connection.conNum, this.fileBytes.length, 0, FIN, new byte[0]);
			this.packet = ppacket.packPacket(connection.address, connection.PORT, FIN_LENGTH);
			connection.socket.send(this.packet);
			
			try {
				connection.socket.receive(packet);
				ppacket = new PhotoPacket(this.packet.getData());
				System.out.print("RCVD: ");
				ppacket.printPacket();
			}
			catch(SocketTimeoutException e) {
				System.out.println("Timeout occurred.");
				continue;
			}
		}
		
		return;
	}
}