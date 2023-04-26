package net.floodlightcontroller.unipi.amd;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ServerStatus extends ServerResource {
	@Put
	public Map<String, Object> changeStatus(String putBody) {
		// check if the body of the PUT is not empty 
		if (putBody == null) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Error");
			response.put("reason", "Empty request body");
			return response;        
		}
		
		// parse the body as JSON Object
		ObjectMapper mapper = new ObjectMapper();                
		try {                        
			JsonNode root = mapper.readTree(putBody);                        
			
			// extract the values and cast the ip in order to validate it
			String newStatus = root.get("newStatus").asText();
			String virtualIp = root.get("virtualIp").asText();
			IPv4Address.of(virtualIp);
			
			// retrieve the REST Interface defined for the AMD
			IAsynchronousMessageDelivery amd = (IAsynchronousMessageDelivery)getContext().getAttributes().get(IAsynchronousMessageDelivery.class.getCanonicalName());  
			
			if (newStatus.equals("online")) {
				// extract the other values
				String ip = root.get("ip").asText();
				String mac = root.get("mac").asText();
				
				// cast the addresses in order to validate them
				IPv4Address.of(ip);
				MacAddress.of(mac);
				
				// requests the change status to online
				int res = amd.setServerOnline(virtualIp, ip, mac);
				
				// check if failed
				if (res == -1) {
					Map<String, Object> response = new HashMap<String, Object>();
					response.put("status", "Error");
					response.put("reason", "Server associated to " + virtualIp + " is already online");
					return response;  
				} else if (res == -2) {
					Map<String, Object> response = new HashMap<String, Object>();
					response.put("status", "Error");
					response.put("reason", "No such subscribed server associated to " + virtualIp);
					return response; 
				} else if (res == -3) {
					Map<String, Object> response = new HashMap<String, Object>();
					response.put("status", "Error");
					response.put("reason", "No device found associated to " + mac);
					return response;
				}
			} else if (newStatus.equals("offline")) {
				// requests the change status to offline
				int res = amd.setServerOffline(virtualIp);
				
				// check if failed
				if (res == -1) {
					Map<String, Object> response = new HashMap<String, Object>();
					response.put("status", "Error");
					response.put("reason", "Server associated to " + virtualIp + " is already offline");
					return response;  
				} else if (res == -2) {
					Map<String, Object> response = new HashMap<String, Object>();
					response.put("status", "Error");
					response.put("reason", "No such subscribed server associated to " + virtualIp);
					return response; 
				} 
			} else {
				Map<String, Object> response = new HashMap<String, Object>();
				response.put("status", "Error");
				response.put("reason", "Invalid new status");
				return response; 
			}
			
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Success");
			return response;
		} catch (IOException e) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Error");
			response.put("reason", "Failed to parse body");
			return response;                 
		} catch (IllegalArgumentException e) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Error");
			response.put("reason", "Invalid addresses format");
			return response;
		}
	}
}
