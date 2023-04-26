package net.floodlightcontroller.unipi.amd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.restlet.resource.Delete;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ServerSubscription extends ServerResource {
	@Post
	public Map<String, Object> subscribe(String postBody) {
		// check if the body of the POST is not empty 
		if (postBody == null) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Error");
			response.put("reason", "Empty request body");
			return response;        
		}
		
		// parse the body as JSON Object
		ObjectMapper mapper = new ObjectMapper();                
		try {                        
			JsonNode root = mapper.readTree(postBody);                        
			
			// extract the values
			String ip = root.get("ip").asText();
			String mac = root.get("mac").asText();
			
			// try to cast the addresses to validate them (they will throw an exception)
			IPv4Address.of(ip);
			MacAddress.of(mac);
			
			List<String[]> services = new ArrayList<>();
			for (JsonNode service : root.get("services")) {
				String[] tempService = { service.get("port").asText(), service.get("description").asText() };
				services.add(tempService);
			}
			
			// retrieve the REST Interface defined for the AMD and request the subscription
			IAsynchronousMessageDelivery amd = (IAsynchronousMessageDelivery)getContext().getAttributes().get(IAsynchronousMessageDelivery.class.getCanonicalName());  
			String assignedVirtualIp = amd.subscribeServer(ip, mac, services);  
			
			// check if failed
			if (assignedVirtualIp == null) {
				Map<String, Object> response = new HashMap<String, Object>();
				response.put("status", "Error");
				response.put("reason", "Failed to subscribe server beacause it is already subscribed");
				return response;
			} else if (assignedVirtualIp.equals("0.0.0.0")) {
				Map<String, Object> response = new HashMap<String, Object>();
				response.put("status", "Error");
				response.put("reason", "Failed to subscribe server, no available addresses");
				return response;
			}   
			
			// return the assigned virtual IP
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Success");
			response.put("virtualIp", assignedVirtualIp);
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
	
	@Delete
	public Map<String, Object> unsubscribe(String deleteBody) { 
		// check if the body of the DELETE is not empty 
		if (deleteBody == null) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("status", "Error");
			response.put("reason", "Empty request body");
			return response;        
		}
		
		// parse the body as JSON Object
		ObjectMapper mapper = new ObjectMapper();                
		try {                        
			JsonNode root = mapper.readTree(deleteBody);                        
			
			// extract the values and try to cast in order to validate it
			String virtualIp = root.get("virtualIp").asText();
			IPv4Address.of(virtualIp);
			
			// retrieve the REST Interface defined for the AMD and request the unsubscription
			IAsynchronousMessageDelivery amd = (IAsynchronousMessageDelivery)getContext().getAttributes().get(IAsynchronousMessageDelivery.class.getCanonicalName());  
			boolean res = amd.unsubscribeServer(virtualIp);
			
			// check if failed
			if (!res) {
				Map<String, Object> response = new HashMap<String, Object>();
				response.put("status", "Error");
				response.put("reason", "Failed to unsubscribe server, no such subscribed server associated to " + virtualIp);
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
			response.put("reason", "Invalid address format");
			return response;
		}
	}
}
