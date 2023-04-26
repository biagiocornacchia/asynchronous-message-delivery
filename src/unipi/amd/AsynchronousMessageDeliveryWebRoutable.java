package net.floodlightcontroller.unipi.amd;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class AsynchronousMessageDeliveryWebRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
		
		// attach the defined ServerResource Classes to the URI
		router.attach("/server/subscription/json", ServerSubscription.class);
		router.attach("/server/status/json", ServerStatus.class);
		router.attach("/server/info/json", ServerInfo.class);
		
		return router;
	}

	@Override
	public String basePath() {
		// the root path for the resources
		return "/amd";
	}

}
