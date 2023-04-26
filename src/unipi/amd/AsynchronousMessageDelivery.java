package net.floodlightcontroller.unipi.amd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
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
import net.floodlightcontroller.util.FlowModUtils;

public class AsynchronousMessageDelivery implements IOFMessageListener, IFloodlightModule, IAsynchronousMessageDelivery {
	
	protected static Logger logger;
	
	protected IFloodlightProviderService floodlightProvider; 	// reference to the Provider
	protected IRestApiService restApiService;					// reference to Rest Api service
	protected IDeviceService deviceService;						// reference to Device Service
	protected IOFSwitchService switchService;					// reference to Switch Service
	protected IRoutingService routingService;					// reference to Routing Service
	
	protected Queue<String> availableAddresses;					// IP Addresses that can be assigned
	protected Map<String, SubscriberInfo> subscribedServers;	// subscriber info and status
	protected Map<String, Queue<Ethernet>> requestsStore;		// to store the requests when the servers are offline 
	
	// timeouts in seconds of rules sent to the switch
	private final static short IDLE_TIMEOUT = 120;
	private static short HARD_TIMEOUT = 240;
	
	protected final String[][] virtualAddresses = { 
			{"00:00:00:00:00:FE", "8.8.8.0"}, {"00:00:00:00:01:FE", "8.8.8.1"}, {"00:00:00:00:02:FE", "8.8.8.2"}, 
			{"00:00:00:00:03:FE", "8.8.8.3"}, {"00:00:00:00:04:FE", "8.8.8.4"}, {"00:00:00:00:05:FE", "8.8.8.5"} 
	};
	
	private String getVirtualMACAddress(String virtualIp) {
		for (String[] addresses : virtualAddresses) {
			if (virtualIp.equals(addresses[1])) {
				return addresses[0];
			}
		}
		
		return null;
	}
	
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
		return AsynchronousMessageDelivery.class.getSimpleName();
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
		logger = LoggerFactory.getLogger(AsynchronousMessageDelivery.class);
		
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
		String MAC = getVirtualMACAddress(requestedIp);
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
		SubscriberInfo server = null;
		
		Map<String, Object> flowModParameters = new HashMap<String, Object>();
		Map<String, Object> flowModParametersRev = new HashMap<String, Object>();
		
		for (String ip : subscribedServers.keySet()) {
			// the received packet is from client to server
			if (ipv4.getDestinationAddress().compareTo(IPv4Address.of(ip)) == 0) {
				isClientToServerPacket = true;
				server = subscribedServers.get(ip);
				
				// translate server addresses from virtual to physical
				flowModParameters.put("matchMAC", eth.getDestinationMACAddress());
				flowModParameters.put("matchIP", ipv4.getDestinationAddress());
				flowModParameters.put("newMAC", MacAddress.of(server.getMACAddress()));
				flowModParameters.put("newIP", IPv4Address.of(server.getIPAddress()));
				flowModParameters.put("outputPort", getOuputPort(sw.getId(), MacAddress.of(server.getMACAddress()), pi.getMatch().get(MatchField.IN_PORT)));
				
				// forwarding rule for the returning packet
				flowModParametersRev.put("matchMAC", eth.getDestinationMACAddress());
				flowModParametersRev.put("matchIP", ipv4.getDestinationAddress());
				flowModParametersRev.put("outputPort", pi.getMatch().get(MatchField.IN_PORT));
				
				break;
			}
			
			// the received packet is from server to client
			if (ipv4.getSourceAddress().compareTo(IPv4Address.of(subscribedServers.get(ip).getIPAddress())) == 0) {
				isServerToClientPacket = true;
				server = subscribedServers.get(ip);
				
				// translate server addresses from physical to virtual
				flowModParametersRev.put("matchMAC", eth.getSourceMACAddress());
				flowModParametersRev.put("matchIP", ipv4.getSourceAddress());
				flowModParametersRev.put("newMAC", MacAddress.of(getVirtualMACAddress(ip)));
				flowModParametersRev.put("newIP", IPv4Address.of(ip));
				flowModParametersRev.put("outputPort", getOuputPort(sw.getId(), eth.getDestinationMACAddress(), pi.getMatch().get(MatchField.IN_PORT)));
				
				// forwarding rule for the returning packet
				flowModParameters.put("matchMAC", eth.getSourceMACAddress());
				flowModParameters.put("matchIP", ipv4.getSourceAddress());
				flowModParameters.put("outputPort", pi.getMatch().get(MatchField.IN_PORT));
				
				break;
			}
		}
		
		// if the packet must not be translated, stop here
		if (!isClientToServerPacket && !isServerToClientPacket) {
			logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Received in switch " + sw.getId() + " IP packet to " + ipv4.getDestinationAddress().toString());
			return Command.CONTINUE;
		}
		
		// server online
		if (server.isOnline()) {
			// list of actions to be applied to the Packet-Out 
			ArrayList<OFAction> actionListPob = null;
			
			// --------------------- Rule to modify the packet client-to-server ---------------------
			
			if (isClientToServerPacket)	{
				// create a flow table modification message to add a rule
				OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
		        fmb.setIdleTimeout(IDLE_TIMEOUT);
		        fmb.setHardTimeout(HARD_TIMEOUT);
		        fmb.setBufferId(OFBufferId.NO_BUFFER);
		        fmb.setOutPort(OFPort.ANY);
		        fmb.setCookie(U64.of(0));
		        fmb.setPriority(FlowModUtils.PRIORITY_MAX);
		
		        // create the match structure for the switch rule
		        Match.Builder mb = sw.getOFFactory().buildMatch();
		        mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)							
		        	.setExact(MatchField.IPV4_DST, (IPv4Address)flowModParameters.get("matchIP"))
		        	.setExact(MatchField.ETH_DST, (MacAddress)flowModParameters.get("matchMAC"));
				
				// create the actions that will substitute addresses
				OFActions actions = sw.getOFFactory().actions();
		        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		        OFOxms oxms = sw.getOFFactory().oxms();
		        
		        // check if translation is required
		        if (flowModParameters.containsKey("newMAC") && flowModParameters.containsKey("newIP")) {
			        // action that substitutes the Virtual MAC with the real one
			        OFActionSetField setNewDstMAC = actions.buildSetField()
			        	    .setField(
			        	        oxms.buildEthDst()
			        	        .setValue((MacAddress)flowModParameters.get("newMAC"))
			        	        .build()
			        	    ).build();
			        
			        // action that substitutes the Virtual IP with the real one
			        OFActionSetField setNewDstIP = actions.buildSetField()
			        	    .setField(
			        	        oxms.buildIpv4Dst()
			        	        .setValue((IPv4Address)flowModParameters.get("newIP"))
			        	        .build()
			        	    ).build();
			        
			        actionList.add(setNewDstMAC);
			        actionList.add(setNewDstIP);
		        }
		        
		        // action that specifies the output port
		        OFActionOutput output = actions.buildOutput()
		        	    .setMaxLen(0xFFffFFff)
		        	    .setPort((OFPort)flowModParameters.get("outputPort"))
		        	    .build();
		        actionList.add(output);
		        
		        // setup the Flow-Mod and send it to the switch
		        fmb.setActions(actionList);
		        fmb.setMatch(mb.build());
		        sw.write(fmb.build());
				
		        actionListPob = actionList;
		        
				// test
				logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Installed rule [matching with DST] " + flowModParameters);
			}
			
			// --------------------- Rule to modify the packet server-to-client ---------------------
			
			else if (isServerToClientPacket) {
				// create a flow table modification message to add a rule
				OFFlowAdd.Builder fmbRev = sw.getOFFactory().buildFlowAdd();
				fmbRev.setIdleTimeout(IDLE_TIMEOUT);
				fmbRev.setHardTimeout(HARD_TIMEOUT);
				fmbRev.setBufferId(OFBufferId.NO_BUFFER);
				fmbRev.setOutPort(OFPort.ANY); 
				fmbRev.setCookie(U64.of(0));
				fmbRev.setPriority(FlowModUtils.PRIORITY_MAX);
	
				// create the match structure for the switch rule
		        Match.Builder mbRev = sw.getOFFactory().buildMatch();
		        mbRev.setExact(MatchField.ETH_TYPE, EthType.IPv4)									
		        	.setExact(MatchField.IPV4_SRC, (IPv4Address)flowModParametersRev.get("matchIP"))
		        	.setExact(MatchField.ETH_SRC, (MacAddress)flowModParametersRev.get("matchMAC"));
		        
				// create the actions that will substitute addresses
				OFActions actionsRev = sw.getOFFactory().actions();
		        ArrayList<OFAction> actionListRev = new ArrayList<OFAction>();
		        OFOxms oxmsRev = sw.getOFFactory().oxms();
		        
		        // check if translation is required
		        if (flowModParametersRev.containsKey("newMAC") && flowModParametersRev.containsKey("newIP")) {
			        // action that substitutes the Virtual MAC with the real one
			        OFActionSetField setNewSrcMAC = actionsRev.buildSetField()
			        	    .setField(
			        	        oxmsRev.buildEthSrc()
			        	        .setValue((MacAddress)flowModParametersRev.get("newMAC"))
			        	        .build()
			        	    ).build();
			        
			        // action that substitutes the real IP with the virtual one
			        OFActionSetField setNewSrcIP = actionsRev.buildSetField()
			        	    .setField(
			        	        oxmsRev.buildIpv4Src()
			        	        .setValue((IPv4Address)flowModParametersRev.get("newIP"))
			        	        .build()
			        	    ).build();
			        
			        actionListRev.add(setNewSrcMAC);
			        actionListRev.add(setNewSrcIP);
		        }
		        
		        // action that specifies the output port
		        OFActionOutput outputRev = actionsRev.buildOutput()
		        	    .setMaxLen(0xFFffFFff)
		        	    .setPort((OFPort)flowModParametersRev.get("outputPort"))
		        	    .build();
		        actionListRev.add(outputRev);
		        
		        // setup the Flow-Mod and send it to the switch
		        fmbRev.setActions(actionListRev);
		        fmbRev.setMatch(mbRev.build());
		        sw.write(fmbRev.build());
		        
		        actionListPob = actionListRev;
				
				// test
				logger.info("S" + sw.getId().getLong() + " (handleIPPacket) Installed rule [matching with SRC] " + flowModParametersRev);
			}
			
			// ---------------------------- Apply the rule to the packet ----------------------------
			
			// create the Packet-Out, set basic data for it (buffer id and in port) and set the actions
			OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			pob.setBufferId(pi.getBufferId());
			pob.setInPort(pi.getMatch().get(MatchField.IN_PORT));
			pob.setActions(actionListPob);
			
			// if the packet is encapsulated (not buffered, buffer-id is none) in Packet-In sent it back
			if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
	            byte[] packetData = pi.getData();
	            pob.setData(packetData);     
			}		
			
			sw.write(pob.build());
			
			// --------------------------------------------------------------------------------------
			
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
			
			// enable the virtual MAC on all switches
			String assignedVirtualMAC = getVirtualMACAddress(assignedVirtualIp.toString());
			enableSecurityPolicy(MacAddress.of(assignedVirtualMAC));
			
			return assignedVirtualIp;
		} catch (NoSuchElementException e) {
			logger.error("REST (subscribeServer) No available addresses");
			return "0.0.0.0";
		}
	}

	@Override
	public boolean unsubscribeServer(String virtualIp) {
		if (subscribedServers.containsKey(virtualIp)) {
			// free the buffer and flush the flow rules related to the server
			requestsStore.remove(virtualIp);
			flushFlowRules(IPv4Address.of(virtualIp));
			
			// remove server from the map
			SubscriberInfo server = subscribedServers.remove(virtualIp);
			logger.info("REST (unsubscribeServer) Unsubscribed from " + virtualIp + ": " + server);
			
			// disable the virtual MAC on all switches
			String virtualMAC = getVirtualMACAddress(virtualIp.toString());
			disableSecurityPolicy(MacAddress.of(virtualMAC));
			
			// free the virtual address assigned to server
			availableAddresses.add(virtualIp);
		} else {
			logger.error("REST (unsubscribeServer) No such subscribed server associated to " + virtualIp);
			return false;
		}
		
		return true;
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
				flushFlowRules(IPv4Address.of(virtualIp));
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
	
	private void flushFlowRules(IPv4Address serverVirtualIP) {
		// get all the needed addresses
		IPv4Address serverPhysicalIP = IPv4Address.of(subscribedServers.get(serverVirtualIP.toString()).getIPAddress());
		MacAddress serverPhysicalMAC = MacAddress.of(subscribedServers.get(serverVirtualIP.toString()).getMACAddress());
		MacAddress serverVirtualMAC = MacAddress.of(getVirtualMACAddress(serverVirtualIP.toString()));
				
		// get the DPID of all access switches
		Set<DatapathId> accessSwitches = new HashSet<>();
		DatapathId serverAccessSwitch = null;
		for (IDevice host : deviceService.getAllDevices()) {
			// get only the used access switches (if an access switch is used by an host, the host.getIPv4Address() will return a non-empty array)
			if (host.getIPv4Addresses().length > 0) {
				// get the DatapathID of the switch directly connected to the server that is going offline/unsubscribing
				if (host.getIPv4Addresses()[0].equals(serverPhysicalIP)) {
					serverAccessSwitch = host.getAttachmentPoints()[0].getNodeId();
					logger.info("(flushFlowRules) server {} is attached to {}", host.getIPv4Addresses()[0], serverAccessSwitch.toString());
				} 
				
				// get all the others access switches
				else {
					for (SwitchPort attachmentPoint : host.getAttachmentPoints()) {
						accessSwitches.add(attachmentPoint.getNodeId());
						logger.info("(flushFlowRules) host {} is attached to {}", host.getIPv4Addresses()[0], attachmentPoint.getNodeId().toString());
					}
				}
			}
		}
		
		// --------------------- Delete the rule client-to-server ---------------------
		
		for (DatapathId switchDPID : accessSwitches) {
			// retrieve the IOFSwitch reference
			IOFSwitch sw = switchService.getSwitch(switchDPID);			
			
			// create the match rule
			Match.Builder mb = sw.getOFFactory().buildMatch();
	        mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)									
	        	.setExact(MatchField.IPV4_DST, serverVirtualIP)
	        	.setExact(MatchField.ETH_DST, serverVirtualMAC);
	        
	        // create the delete rule
	        OFFlowDelete fd = sw.getOFFactory().buildFlowDelete()
	        		.setMatch(mb.build())
	                .setOutPort(OFPort.ANY)
	                .setCookieMask(U64.NO_MASK)
	                .setCookie(U64.of(0))
	                .setBufferId(OFBufferId.NO_BUFFER)
	                .build();       
	        
	        // send the rule to the switch
	        sw.write(fd);
		}
		
		// --------------------- Delete the rule server-to-client ---------------------

		// if the server access switch is not found, there is no need to flush the flow rules, because they are already expired
		if (serverAccessSwitch == null)
			return;
		
		// retrieve the IOFSwitch reference
		IOFSwitch sw = switchService.getSwitch(serverAccessSwitch);
					
		// create the match rule
		Match.Builder mbRev = sw.getOFFactory().buildMatch();
        mbRev.setExact(MatchField.ETH_TYPE, EthType.IPv4)									
        	.setExact(MatchField.IPV4_SRC, serverPhysicalIP)
        	.setExact(MatchField.ETH_SRC, serverPhysicalMAC);
	        
        // create the delete rule
        OFFlowDelete fdRev = sw.getOFFactory().buildFlowDelete()
        		.setMatch(mbRev.build())
                .setOutPort(OFPort.ANY)
                .setCookieMask(U64.NO_MASK)
                .setCookie(U64.of(0))
                .setBufferId(OFBufferId.NO_BUFFER)
                .build();
        
        // send the rule to the switch
        sw.write(fdRev);
	        
		// ----------------------------------------------------------------------------
	}
	
	private void enableSecurityPolicy(MacAddress mac) {
		// create the flow on all switches
		for (DatapathId switchDPID : switchService.getAllSwitchDpids()) {
			// retrieve the IOFSwitch reference
			IOFSwitch sw = switchService.getSwitch(switchDPID);
			
			// create a flow table modification message to add a rule
			OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
			fmb.setCookie(U64.of(0));
			fmb.setPriority(1);
	
			// create the match structure for the switch rule
	        Match.Builder mb = sw.getOFFactory().buildMatch();
	        mb.setExact(MatchField.ETH_SRC, mac);
	        
	        // action that allows the switch to manage packet with the specified external source MAC Address
			OFActions actions = sw.getOFFactory().actions();
	        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
	        OFActionOutput output = actions.buildOutput()
	        	    .setMaxLen(0xFFffFFff)
	        	    .setPort(OFPort.NORMAL)
	        	    .build();
	        actionList.add(output);
	        
	        // setup the Flow-Mod and send it to the switch
	        fmb.setActions(actionList);
	        fmb.setMatch(mb.build());
	        sw.write(fmb.build());
	        
	        logger.info("(enableSecurityPolicy) Installed security policy for {} on {}", mac.toString(), switchDPID.toString());
		}
	}
	
	private void disableSecurityPolicy(MacAddress mac) {
		// remove the flow on all switches
		for (DatapathId switchDPID : switchService.getAllSwitchDpids()) {
			// retrieve the IOFSwitch reference
			IOFSwitch sw = switchService.getSwitch(switchDPID);
						
			// create the match rule
			Match.Builder mb = sw.getOFFactory().buildMatch();
	        mb.setExact(MatchField.ETH_SRC, mac);
		        
	        // create the delete rule
	        OFFlowDelete fd = sw.getOFFactory().buildFlowDelete()
	        		.setMatch(mb.build())
	                .setCookieMask(U64.NO_MASK)
	                .setCookie(U64.of(0))
	                .build();
	        
	        // send the rule to the switch
	        sw.write(fd);
	        
	        logger.info("(disableSecurityPolicy) Removed security policy for {} on {}", mac.toString(), switchDPID.toString());
		}
	}
	
	// --------------------------------------------------------------------------------------

}
