package net.floodlightcontroller.unipi.amd;

import java.io.Serializable;

public class SubscriberInfo implements Serializable {

	private String MACAddress;	
	private String IPAddress;
	private final String[][] services;
	private boolean online;
	
	public SubscriberInfo(String MACAddress, String IPAddress, String[][] services, boolean online) {
		this.MACAddress = MACAddress;
		this.IPAddress = IPAddress;
		this.services = services;
		this.online = online;
	}

	public String getIPAddress() {
		return IPAddress;
	}

	public void setIPAddress(String IPAddress) {
		this.IPAddress = IPAddress;
	}

	public boolean isOnline() {
		return online;
	}

	public void setOnline(boolean online) {
		this.online = online;
	}

	public String getMACAddress() {
		return MACAddress;
	}
	
	public void setMACAddress(String MACAddress) {
		this.MACAddress = MACAddress;
	}

	public String[][] getServices() {
		return services;
	}

	@Override
	public String toString() {
		String res = this.IPAddress + " (" + MACAddress + ") | status : ";
		String status = this.online ? "online" : "offline";
		return res + status;
	}
}