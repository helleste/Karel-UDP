// Author: Štěpán Heller (helleste)

package robot;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;

public class Robot {
	
	public static void main(String[] args) {
		if(args.length == 0) {
			System.out.println("Usage/Photo: java robot.Robot <hostname>");
			System.out.println("Usage/Firmware: java robot.Robot <hostname> <firmware.bin>");
		} else if(args.length == 1) {
			PhotoClient photoClient = new PhotoClient(args[0]);
			photoClient.run();
		} else if(args.length == 2) {
//			Client client = new Client(args[0], Integer.parseInt(args[1]));
//			client.run();
		}
	}
}

class PhotoClient {
	
	private DatagramSocket socket;
	private DatagramPacket packet;
	private static final int PORT = 4000; // port number on baryk
	private InetAddress address = null;
	
	private static final int SYN = 4; // SYN flag
	private static final int FIN = 2; // FIN flag
	private static final int RST = 1	; // RST flag
	
	private static final byte[] DOWNLOAD = {0x01}; // Download a photo
	
	private ArrayList<Boolean> flags = new ArrayList<Boolean>(); // Array of flags according to received packets
	private int lastAck = 0; // Last send ACK
	
	private ArrayList<PhotoPacket> receivedPackets = new ArrayList<PhotoPacket>(); // Raw photo data
	
	int overflow = 0; // How many times seqNum overflowed
	int ack = 0; // Expected seqNum
	boolean of = false; // Overflow flag
	int conNum = 0; // Connection number of the current communication
	
	public PhotoClient(String host) {
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
	
	public void run() {
		
		PhotoPacket ppacket = null;
		
		try {
			ppacket = init(ppacket);
			
			// Receive second packet from baryk with real photo data
			this.packet = new DatagramPacket(new byte[265], 265, this.address, PORT);
			this.socket.receive(this.packet);
			System.out.println("\n" + this.packet.getLength());
			ppacket = new PhotoPacket(this.packet.getData());
			System.out.print("\nRCVD: ");
			ppacket.printPacket();
			// TODO Save photo data
			
			int index = 0;
			
			while(!ppacket.fin) {
				if(ppacket.conNum == this.conNum) {
					index = calcIndex(ppacket.seqNum);
					setSign(index); // Set flag in the array from received seqNum
					System.out.println(flags.toString());
					System.out.println("FLAGS SIZE: " + flags.size());
					savePacket(index, ppacket); // Save PhotoPacket to the array
					this.ack = findAck(); // Find ACK to send
					
					// Send confirmation packet to baryk
					ppacket = new PhotoPacket(ppacket.conNum, 0, this.ack, 0, new byte[0]);
					packPacket(ppacket);
					System.out.print("\nSEND: ");
					ppacket.printPacket();
					this.socket.send(this.packet);
				}
				
				// Receive new packet
				this.packet = new DatagramPacket(new byte[265], 265, this.address, PORT);
				this.socket.receive(this.packet);
				System.out.println("\n" + this.packet.getLength());
				ppacket = new PhotoPacket(this.packet.getData());
				System.out.print("\nRCVD: ");
				ppacket.printPacket();
			}
			
			// We have ppacket with fin flag on
			// TODO Send FIN packet to baryk
			// TODO Save photo
			System.out.println("RECEIVING DATA FINISHED!");
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	// Establish communication with baryk
	private PhotoPacket init(PhotoPacket ppacket) throws IOException {
		
		ppacket = new PhotoPacket(0, 0, 0, SYN, DOWNLOAD);
		packPacket(ppacket);
		System.out.print("SEND: ");
		ppacket.printPacket();
		
		// Send packet to baryk
		// TODO Make sure that baryk received my SYN packet
		this.socket.send(this.packet);
		
		do {
			try {
				// Receive packet from baryk
				this.socket.receive(this.packet);
				System.out.println(this.packet.getLength());
							
				// Create PhotoPacket from datagram
				ppacket = new PhotoPacket(this.packet.getData());
				
				// Print header of received packet
				System.out.print("\nRCVD: ");
				ppacket.printPacket();
			}
			catch (IOException e) {
				System.out.println("Timeout occured.");
				try {
					this.socket.send(this.packet);
					ppacket = new PhotoPacket(0, 0, 0, 0, DOWNLOAD);
				}
				catch (IOException f) {
					e.printStackTrace();
				}
				
				continue;
			}
			
		} while (!ppacket.syn);
		
		// Here we received syn and the connection is established
		this.socket.setSoTimeout(0);
		this.conNum = ppacket.conNum;
		return ppacket;
	}
	
	// Fill my DatagramPacket with my custom PhotoPacket's data
	public void packPacket(PhotoPacket ppacket) {
		
		byte myPacket[] = new byte[10];
		
		// Add connection number first
		myPacket[0] = (byte) ((ppacket.conNum >> 24) % 256);
		myPacket[1] = (byte) ((ppacket.conNum >> 16) % 256);
		myPacket[2] = (byte) ((ppacket.conNum >> 8) % 256);
		myPacket[3] = (byte) ((ppacket.conNum) % 256);
		
		// Then add the sequence number
		myPacket[4] = (byte) ((ppacket.seqNum >> 8) % 256);
		myPacket[5] = (byte) ((ppacket.seqNum) % 256);
		
		myPacket[7] = (byte) ((ppacket.ackNum) % 256);
		// Then add the acknowledgment number
		myPacket[6] = (byte) ((ppacket.ackNum >> 8) % 256);
		
		// The header ends with set of flags
		myPacket[8] = (byte) (ppacket.signs);
		
		// At the end we fill the packet with data
		if(ppacket.data != null) {
			for (int i = 0; i < ppacket.data.length; i++) {
				myPacket[9 + i] = ppacket.data[i];
			}
		}
		
		// Decide the sending length of the packet
		int lengthPacket = 9;
		if(ppacket.syn) lengthPacket = 10;
		
		// Create the final DatagramPacket
		this.packet = new DatagramPacket(myPacket, lengthPacket, this.address, PORT);
		
	}
	
	// Calculate index from received seqNum
	private int calcIndex(int seqNum) {
		
		if((char) this.ack < 2048) // ACK overflown or not yet
			this.of = false;
			
		if(!this.of && (seqNum < ((int) (char) (this.ack) - 2048)) && ((char)(seqNum) < (char) (this.ack + 2048))) {
			this.of = true;
			this.overflow++;
			System.out.println("\nOVERFLOW DETECTED! overflow= " + this.overflow + " ack= " + (int) (char) (this.ack) + " ack-w= " +  ((int) (char)(this.ack)-2048));
		}
		
		if(this.of && seqNum > 2048) { // In overflow mode but seqNum is before overflow
			int index = (this.overflow -1)* Character.MAX_VALUE + seqNum + this.overflow -1; // Possibly + overflow
			System.out.println("\n OF1. Calculated index: " + index/255);
			return index/255;
		}
		
		if(!this.of && this.overflow > 0) { // Ack overflown, no overflow mode
			int index = this.overflow * Character.MAX_VALUE + seqNum + this.overflow; // Possibly + overflow
			System.out.println("\n OF2. Calculated index: " + index/255);
			return index/255;
		}
		
		if(this.of && this.overflow > 0 && ((char)(seqNum) < (char) (this.ack + 2048))) { // Overflow mode, ack is old, seqNum is overflow
			int index = this.overflow * Character.MAX_VALUE + seqNum + this.overflow; // Possibly + overflow
			System.out.println("\n OF3. Calculated index: " + index/255);
			return index/255;
		}
		
		System.out.println("\nCalculated index: " + seqNum/255);
		return seqNum/255; 
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
		int ack = 0;
		boolean c;
		
		for(int i = 0; i < this.flags.size(); i++) {
			c = this.flags.get(i);
			if(c == false) break; // Found a hole in the array TODO Write a position of the hole to log
			ack += 255;
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
	
	// Convert byte to int
	private int bToI(byte b) {
		
		return b & 0xFF;
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
		
		System.out.print(log.toString());
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
