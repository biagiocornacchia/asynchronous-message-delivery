package net.floodlightcontroller.unipi.amd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;

public class AsynchronousMessageDeliveryNoFlowMod implements IOFMessageListener, IFloodlightModule, IAsynchronousMessageDelivery {
	
	protected static Logger logger;
	
	protected IFloodlightProviderService floodlightProvider; 	// reference to the Provider
	protected IRestApiService restApiService;					// reference to Rest Api service
	protected IDeviceService deviceService;						// reference to Device Service
	protected IOFSwitchService switchService;					// reference to Switch Service
	protected IRoutingService routingService;					// reference to Routing Service
	
	protected Queue<String> availableAddresses;					// IP Addresses that can be assigned
	protected Map<String, SubscriberInfo> subscribedServers;	// subscriber info and status
	protected Map<String, Queue<Ethernet>> requestsStore;		// to store the requests when the servers are offline 
	
	protected final String[][] virtualAddresses = { 
			{"00:00:00:00:00:FE", "8.8.8.0"}, {"00:00:00:00:01:FE", "8.8.8.1"}, {"00:00:00:00:02:FE", "8.8.8.2"}, 
			{"00:00:00:00:03:FE", "8.8.8.3"}, {"00:00:00:00:04:FE", "8.8.8.4"}, {"00:00:00:00:05:FE", "8.8.8.5"} 
	};
	
	@Override
	public boolean isHandledByAMD(String srcIP, String dstIP) {
		// check if a packet is related to a subscribed server and should be translated
		for (String[] addresses : virtualAddresses) {
    		if (dstIP.equals(addresses[1]) || (subscribedServers.containsKey(addresses[1]) && srcIP.equals(subscribedServers.get(addresses[1]).getIPAddress()))) {
    			logger.info("(FORWARDER) IPv4 packet managed by AMD");
    			return true;
    		}
    	}
		
		return false;
	}

	@Override
	public String getName() {
		// return the class name
		return AsynchronousMessageDeliveryNoFlowMod.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// the collection of modules must contain IAsynchronousMessageDeliveryREST, used to expose internal operation
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IAsynchronousMessageDelivery.class);            
		return l;  
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// set this class as implementation of IAsynchronousMessageDeliveryREST
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();            
		m.put(IAsynchronousMessageDelivery.class, this);            
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// the collection of dependencies must contain the Provider Service
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(IFloodlightProviderService.class);
	    l.add(IRestApiService.class);
	    return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		// get the logger
		logger = LoggerFactory.getLogger(AsynchronousMessageDeliveryNoFlowMod.class);
		
		// get the provider reference
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		
		// get the rest api reference
	    restApiService = context.getServiceImpl(IRestApiService.class);
	    
	    // get the device service reference
	    deviceService = (IDeviceService)context.getServiceImpl(IDeviceService.class); 

	    // get the switch service reference
	    switchService = (IOFSwitchService)context.getServiceImpl(IOFSwitchService.class); 
	    
	    // get the routing service reference
	    routingService = (IRoutingService)context.getServiceImpl(IRoutingService.class);
		
		// initialize available ip structure
		availableAddresses = new LinkedList<>();
		for (String[] addresses : virtualAddresses)
			availableAddresses.add(addresses[1]);
		
		// initialize subscriber info structure
		subscribedServers = new HashMap<>();
		
		// initialize the store for the servers that are offline
		requestsStore = new HashMap<>();
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// register the module to the messages dispatcher
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		
		// assign the REST interface created for this module
		restApiService.addRestletRoutable(new AsynchronousMessageDeliveryWebRoutable());
	}

	// --------------------- Methods that handle the received Packet-In ---------------------
	
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		// cast packet
		OFPacketIn pi = (OFPacketIn) msg; 
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		
		// get the payload of the Packet-In
		IPacket pkt = eth.getPayload();
		
		// broadcast/multicast case
		if (eth.isBroadcast() || eth.isMulticast()) {			
			// handle only ARP Packets
			if (pkt instanceof ARP) {
				logger.info("S" + sw.getId().getLong() + " (receive) Received Broadcast/Multicast ARP Request");
				return handleARPRequest(sw, eth, pi);
			}
		}
		
		// other packet type
		else {			
			// handle only IP Packets
			if (pkt instanceof IPv4) {
				return handleIPPacket(sw, pi, eth);
			} else if (pkt instanceof ARP) {
				logger.info("S" + sw.getId().getLong() + " (receive) Received Unicast ARP Request");
				return handleARPRequest(sw, eth, pi);
			}
		}
		
		// allow the next module to also process this OpenFlow message (this is because the message wasn't for the server)
		return Command.CONTINUE;
	}
	
	private Command handleARPRequest(IOFSwitch sw, Ethernet eth, OFPacketIn pi) {
		// retrieve the ARP request and the input port
		ARP arpRequest = (ARP)eth.getPayload();
		OFPort inPort = pi.getMatch().get(MatchField.IN_PORT);
		
		// retrieve the server MAC Address
		String requestedIp = arpRequest.getTargetProtocolAddress().toString();
		
		// if the ip is not one of the assigned virtual ip, do not handle it
		if (!subscribedServers.keySet().contains(requestedIp))
			return Command.CONTINUE;
		
		// debug
		logger.info("S" + sw.getId().getLong() + " (handleARPRequest) ARP Request for " + requestedIp + " from " + eth.getSourceMACAddress().toString());
		
		// get the virtual MAC corresponding to virtual IP
		String MAC = null;
		for (String[] addresses : virtualAddresses) {
			if (addresses[1].equals(requestedIp)) {
				MAC = addresses[0];
				break;
			}
		}
		if (MAC == null)
			return Command.CONTINUE;
		
		// debug
		logger.info("S" + sw.getId().getLong() + " (handleARPRequest) ARP Response with " + MAC);
				
		// generate ARP reply
		IPacket arpReply = new Ethernet()
				.setSourceMACAddress(MacAddress.of(MAC))
				.setDestinationMACAddress(eth.getSourceMACAddress())
				.setEtherType(EthType.ARP)
				.setPriorityCode(eth.getPriorityCode())
				.setPayload(new ARP()
						.setHardwareType(ARP.HW_TYPE_ETHERNET)
						.setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte) 6)
						.setProtocolAddressLength((byte) 4)
						.setOpCode(ARP.OP_REPLY)
						.setSenderHardwareAddress(MacAddress.of(MAC)) 				// set virtual MAC address
						.setSenderProtocolAddress(IPv4Address.of(requestedIp)) 		// set virtual IP address
						.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
						.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		
		// initialize a Packet-Out
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(OFBufferId.NO_BUFFER);			// the switch must use the packet that the controller will include in the Packet-Out (not a packet inside its buffer)
		pob.setInPort(OFPort.ANY);						// will make it appear that the packet was sent from a host and not from the controller 
		
		// set the output action (reply to the Packet-In port)
		OFActions actions = sw.getOFFactory().actions();
        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFActionOutput output = actions.buildOutput()
        	    .setMaxLen(0xFFffFFff)
        	    .setPort(inPort)
        	    .build();
		actionList.add(output);	
		pob.setActions(actionList);
		
		// set the ARP reply as packet data 
		byte[] packetData = arpReply.serialize();
		pob.setData(packetData);
		
		// send packet
		sw.write(pob.build());
		return Command.STOP;
	}
	
	private Command handleIPPacket(IOFSwitch sw, OFPacketIn pi, Ethernet eth) {
		// cast the IP packet
		IPv4 ipv4 = (IPv4)eth.getPayload();
			
		boolean isClientToServerPacket = false;
		boolean isServerToClientPacket = false;
		String serverVirtualIp = "0.0.0.0";
		
		for (String ip : subscribedServers.keySet()) {
			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Confronto DST " + ipv4.getDestinationAddress().toString() + " : " + ip);
			
			// check the destination address
			if (ipv4.getDestinationAddress().compareTo(IPv4Address.of(ip)) == 0) {
				isClientToServerPacket = true;
				break;
			}
			

			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Confronto SRC " + ipv4.getSourceAddress().toString() + " : " + subscribedServers.get(ip).getIPAddress());
			
			// check the source address
			if (ipv4.getSourceAddress().compareTo(IPv4Address.of(subscribedServers.get(ip).getIPAddress())) == 0) {
				isServerToClientPacket = true;
				serverVirtualIp = ip;
				break;
			}
		}
		
		if (!isClientToServerPacket && !isServerToClientPacket) {
			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Received in switch " + sw.getId() + " IP packet to " + ipv4.getDestinationAddress().toString());
		}
		
		// --------------------- Rule to modify the packet host-to-virtual-server ---------------------
		
		if (isClientToServerPacket) {
			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) IP Packet from Client to Server");
			
			// retrieve server status
			SubscriberInfo server = subscribedServers.get(ipv4.getDestinationAddress().toString());
			
			// server online
			if (server.isOnline()) {
				// calculate the output port
				OFPort srcPort = pi.getMatch().get(MatchField.IN_PORT); 
				OFPort outputPort = getOuputPort(sw.getId(), MacAddress.of(server.getMACAddress()), srcPort);
				
				// initialize a Packet-Out
				OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
				pob.setBufferId(pi.getBufferId());
				pob.setInPort(srcPort);
				
				// create the actions that will substitute addresses
				OFActions actions = sw.getOFFactory().actions();
		        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		        OFOxms oxms = sw.getOFFactory().oxms();
		        
		        // action that substitutes the Virtual MAC with the real one
		        OFActionSetField setNewDstMAC = actions.buildSetField()
		        	    .setField(
		        	        oxms.buildEthDst()
		        	        .setValue(MacAddress.of(server.getMACAddress()))
		        	        .build()
		        	    ).build();
		        
		        // action that substitutes the Virtual IP with the real one
		        OFActionSetField setNewDstIP = actions.buildSetField()
		        	    .setField(
		        	        oxms.buildIpv4Dst()
		        	        .setValue(IPv4Address.of(server.getIPAddress()))
		        	        .build()
		        	    ).build();
		        
		        // action that specifies the output port
		        OFActionOutput output = actions.buildOutput()
		        	    .setMaxLen(0xFFffFFff)
		        	    .setPort(outputPort)
		        	    .build();
		        
		        actionList.add(setNewDstMAC);
		        actionList.add(setNewDstIP);
		        actionList.add(output);
		        pob.setActions(actionList);				
				
		        // if the packet is encapsulated inside the Packet-In, sent it back
				if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
					byte[] packetData = pi.getData();
					pob.setData(packetData);
				}
				
				// send the Packet-Out to switch
				sw.write(pob.build());
				return Command.STOP;
			}
			
			// server offline
			else {
				String virtualIp = ipv4.getDestinationAddress().toString();
				logger.info("S" + sw.getId().getLong() + " (handleIPPacket) IP packet buffered for server associated to " + virtualIp);
				
				// if the store contains already a queue for the server just insert the packet, otherwise create the queue
				if (requestsStore.containsKey(virtualIp)) {
					requestsStore.get(virtualIp).add(eth);
				} else {
					Queue<Ethernet> newQueue = new LinkedList<>();
					newQueue.add(eth);
					requestsStore.put(virtualIp, newQueue);
				}
				
				return Command.STOP;
			}
		}
		
		// --------------------- Rule to modify the packet physical-server-to-host --------------------
		
		else if (isServerToClientPacket) {
			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) IP Packet from Server to Client");
			
			// get the virtual MAC corresponding to virtual IP
			String MAC = "";
			for (String[] addresses : virtualAddresses) {
				if (addresses[1].equals(serverVirtualIp)) {
					MAC = addresses[0];
					break;
				}
			}
			
			// calculate the output port
			OFPort srcPort = pi.getMatch().get(MatchField.IN_PORT); 
			OFPort outputPort = getOuputPort(sw.getId(), eth.getDestinationMACAddress(), srcPort);
			
			// initialize a Packet-Out
			OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			pob.setBufferId(pi.getBufferId());			
			pob.setInPort(srcPort);
	        
			// create the actions that will substitute addresses
			OFActions actions = sw.getOFFactory().actions();
	        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
	        OFOxms oxms = sw.getOFFactory().oxms();
	        
	        // action that substitutes the Virtual MAC with the real one
	        OFActionSetField setNewSrcMAC = actions.buildSetField()
	        	    .setField(
	        	        oxms.buildEthSrc()
	        	        .setValue(MacAddress.of(MAC))
	        	        .build()
	        	    ).build();
	        
	        // action that substitutes the real IP with the virtual one
	        OFActionSetField setNewSrcIP = actions.buildSetField()
	        	    .setField(
	        	        oxms.buildIpv4Src()
	        	        .setValue(IPv4Address.of(serverVirtualIp))
	        	        .build()
	        	    ).build();
	        
	        // action that specifies the output port
	        OFActionOutput outputRev = actions.buildOutput()
	        	    .setMaxLen(0xFFffFFff)
	        	    .setPort(outputPort)
	        	    .build();

	        actionList.add(setNewSrcMAC);
	        actionList.add(setNewSrcIP);
	        actionList.add(outputRev);
	        pob.setActions(actionList);
	        
	        // if the packet is encapsulated inside the Packet-In, sent it back
			if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
				byte[] packetData = pi.getData();
				pob.setData(packetData);
			}
			
			// send the Packet-Out to switch
			sw.write(pob.build());
			return Command.STOP;
		}
		
		// --------------------------------------------------------------------------------------------
		
		return Command.CONTINUE;
	}
	
	private OFPort getOuputPort(DatapathId srcSwitchDPID, MacAddress dstHostMAC, OFPort srcPort) {
		logger.info("S" + srcSwitchDPID.getLong() + " (getOuputPort) Requested PATH from SWITCH " + srcSwitchDPID + " (in port " + srcPort + ") to HOST " + dstHostMAC);
		
		try {
			// retrieve the DatapathID of the destination switch
			IDevice device = deviceService.findDevice(dstHostMAC, VlanVid.ZERO, IPv4Address.NONE, IPv6Address.NONE, DatapathId.NONE, OFPort.ZERO);
			SwitchPort dstPort = device.getAttachmentPoints()[0];
			DatapathId dstSwitchDPID = switchService.getSwitch(dstPort.getNodeId()).getId();
			logger.info("S" + srcSwitchDPID.getLong() + " (getOuputPort) Analyzing path from " + srcSwitchDPID.toString() + " to " + dstSwitchDPID.toString());
			
			// get the shortest path and from it get the output port
			Path path = routingService.getPath(srcSwitchDPID, srcPort, dstSwitchDPID, dstPort.getPortId());
			logger.info("S" + srcSwitchDPID.getLong() + " (getOutputPort) Output port: " + path.getPath().get(1).getPortId());
		
			return path.getPath().get(1).getPortId();
		} catch (NullPointerException e) {
			logger.error("S" + srcSwitchDPID.getLong() + " (getOuputPort) No device found associated to " + dstHostMAC);
			return OFPort.FLOOD;
		}
	}
	
	// ------------------------------ Methods to handle REST --------------------------------

	@Override
	public String subscribeServer(String ip, String mac, List<String[]> services) {
		try {
			// check if the server is already registered
			for (String virtualIp : subscribedServers.keySet()) {
				if (subscribedServers.get(virtualIp).getMACAddress().equals(mac)) {
					logger.error("REST (subscribeServer) Server already subscribed");
					return null;
				}
			}
						
			// assign the virtual addresses and create the new server object
			String assignedVirtualIp = availableAddresses.remove();
			SubscriberInfo newServer = new SubscriberInfo(mac, ip, services.toArray(new String[services.size()][]), true);
			
			// register the new server
			subscribedServers.put(assignedVirtualIp, newServer);
			logger.info("REST (subscribeServer) Subscribed to " + assignedVirtualIp + ": " + newServer);
			
			return assignedVirtualIp;
		} catch (NoSuchElementException e) {
			logger.error("REST (subscribeServer) No available addresses");
			return null;
		}
	}

	@Override
	public boolean unsubscribeServer(String virtualIp) {
		// remove server from the map
		SubscriberInfo server = subscribedServers.remove(virtualIp);
		
		// if the server exists free the address and the related requests store
		if (server != null) {
			availableAddresses.add(virtualIp);
			requestsStore.remove(virtualIp);
			logger.info("REST (unsubscribeServer) Unsubscribed from " + virtualIp + ": " + server);
		} else {
			logger.error("REST (unsubscribeServer) No such subscribed server associated to " + virtualIp);
		}
		
		return (server != null);
	}

	@Override
	public int setServerOnline(String virtualIp, String ip, String mac) {
		// get the server to update
		SubscriberInfo server = subscribedServers.get(virtualIp);
		
		// if the server exists update its status
		if (server != null) {
			server.setIPAddress(ip);
			server.setMACAddress(mac);
			
			// check if the server is already online
			if (server.isOnline()) {
				logger.error("REST (setServerOnline) Server associated to " + virtualIp + " is already online");
				return -1;
			} else {
				server.setOnline(true);
				logger.info("REST (setServerOnline) Server associated to " + virtualIp + " is back online");
			}
		} else {
			logger.error("REST (setServerOnline) No such subscribed server associated to " + virtualIp);
			return -2;
		}
		
		if (!sendBufferedPackets(virtualIp, ip, mac)) {
			logger.error("REST (setServerOnline) No device found associated to " + mac);
			return -3;
		}

		return 0;
	}

	@Override
	public int setServerOffline(String virtualIp) {
		// get the server to update
		SubscriberInfo server = subscribedServers.get(virtualIp);
		
		// if the server exists update its status
		if (server != null) {
			// check if the server is already offline
			if (!server.isOnline()) {
				logger.error("REST (setServerOffline) Server associated to " + virtualIp + " is already offline");
				return -1;
			} else {
				server.setOnline(false);
				logger.info("REST (setServerOffline) Server associated to " + virtualIp + " is going offline");
			}
		} else {
			logger.error("REST (setServerOffline) No such subscribed server associated to " + virtualIp);
			return -2;
		}
		
		return 0;
	}

	@Override
	public List<List<String>> getServersInfo() {
		List<List<String>> servers = new ArrayList<>();
		
		// iterate all subscribed servers
		for (String virtualIp : subscribedServers.keySet()) {
			String[][] services = subscribedServers.get(virtualIp).getServices();
			
			// create a list that contains the virtual ip of the server
			List<String> info = new ArrayList<>();
			info.add(virtualIp);
			
			// retrieve all services associated with their port and add them to the list
			for (String[] service : services)
				info.add(service[0] + ": " + service[1]);
			
			servers.add(info);
		}
		
		return servers;
	}
	
	private boolean sendBufferedPackets(String virtualIp, String ip, String mac) {
		try {
			// retrieve the reference to the switch that will receive the buffered packets from the controller
			IDevice device = deviceService.findDevice(MacAddress.of(mac), VlanVid.ZERO, IPv4Address.NONE, IPv6Address.NONE, DatapathId.NONE, OFPort.ZERO);
			SwitchPort destinationPort = device.getAttachmentPoints()[0];
			DatapathId switchDPID = destinationPort.getNodeId();
			IOFSwitch sw = switchService.getSwitch(switchDPID);
			logger.info("REST (sendBufferedPackets) Sending buffered packets to switch " + sw.toString());
			
			// iterate all the buffered packets of the requested server
			while (!requestsStore.get(virtualIp).isEmpty()) {
				Ethernet packet = requestsStore.get(virtualIp).remove();
				
				// create the packet builder
				OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
				pob.setBufferId(OFBufferId.NO_BUFFER);
				pob.setInPort(OFPort.ANY);
				
				// create the actions that will substitute addresses
				OFActions actions = sw.getOFFactory().actions();
		        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		        OFOxms oxms = sw.getOFFactory().oxms();
		        
		        // action that substitutes the Virtual MAC with the real one
		        OFActionSetField setNewDstMAC = actions.buildSetField()
		        	    .setField(
		        	        oxms.buildEthDst()
		        	        .setValue(MacAddress.of(mac))
		        	        .build()
		        	    ).build();
		        
		        // action that substitutes the Virtual IP with the real one
		        OFActionSetField setNewDstIP = actions.buildSetField()
		        	    .setField(
		        	        oxms.buildIpv4Dst()
		        	        .setValue(IPv4Address.of(ip))
		        	        .build()
		        	    ).build();
		        
		        // action that specifies the output port
		        OFActionOutput output = actions.buildOutput()
		        	    .setMaxLen(0xFFffFFff)
		        	    .setPort(destinationPort.getPortId())
		        	    .build();
		        
		        actionList.add(setNewDstMAC);
		        actionList.add(setNewDstIP);
		        actionList.add(output);
				pob.setActions(actionList);
				
				// insert the ethernet packet in the Packet-Out and send it to the switch
				pob.setData(packet.serialize());
				sw.write(pob.build());
			}
			
			return true;
		} catch (NullPointerException e) {
			return false;
		}
	}
	
	// --------------------------------------------------------------------------------------

}
