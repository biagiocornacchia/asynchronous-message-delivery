package net.floodlightcontroller.unipi.amd;

import java.util.List;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IAsynchronousMessageDelivery extends IFloodlightService {
	// method used by the forwarder
	public boolean isHandledByAMD(String srcIP, String dstIP);
	
	// subscription management
	public String subscribeServer(String ip, String mac, List<String[]> services);
	public boolean unsubscribeServer(String virtualIp);
	
	// status management
	public int setServerOnline(String virtualIp, String ip, String mac);
	public int setServerOffline(String virtualIp);
	
	// information retrieving
	public List<List<String>> getServersInfo();
}
