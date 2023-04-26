package net.floodlightcontroller.unipi.amd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

public class ServerInfo extends ServerResource {
	@Get("json")
	public Map<String, Object> GetInfo() {
		// retrieve the REST Interface defined for the AMD
		IAsynchronousMessageDelivery amd = (IAsynchronousMessageDelivery)getContext().getAttributes().get(IAsynchronousMessageDelivery.class.getCanonicalName());  
		List<List<String>> res = amd.getServersInfo();
		
		// build the list of servers info
		List<Object> availableServices = new ArrayList<>();
		for (List<String> serverInfo : res) {
			// get the server info
			Map<String, Object> server = new HashMap<String, Object>();
			String virtualIp = serverInfo.remove(0);
			server.put("virtualIp", virtualIp);
			server.put("services", serverInfo);
			
			// add the server object to the array
			availableServices.add(server);
		}
		
		// build the response
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("availableServices", availableServices);
		return response;
	}
}
