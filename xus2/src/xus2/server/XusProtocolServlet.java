package xus2.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.google.appengine.api.channel.ChannelMessage;
import com.google.appengine.api.channel.ChannelServiceFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;

@SuppressWarnings("serial")
public class XusProtocolServlet extends HttpServlet {

	private Map<String, String> data = new HashMap<String, String>();
	private Map<String, String> channels = new HashMap<String, String>();
	private Map<String, List<String>> listeners = new HashMap<String, List<String>>();
	
	enum XusProtocol {
		set("msgId", "key", "value"),
		listen("msgId", "key"),
		unlisten("msgId", "key")
		;
		
		String[] _args;
		private XusProtocol(String... args) {
			_args = args;
		}
		
		public String[] getArgs() {return _args;};
	};
	
	AtomicInteger _ai = new AtomicInteger();
	
	
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		XusProtocol cmd = null;
		try {
			cmd = XusProtocol.valueOf(req.getParameter("cmd"));
		}catch (Exception e) {
			System.err.println("Command " + req.getParameter("cmd") + " is not valid..");
			return;
		}
		
		HttpSession session = req.getSession(true);
		System.out.println("session = " + session);
		 // Get a handle on the datastore itself
		 DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

		 /*
		 // Lookup data by known key name
		 Entity userEntity = datastore.get(KeyFactory.createKey("UserInfo", email));

		 // Or perform a query
		 Query query = new Query("Task", userEntity);
		 query.addFilter("dueDate", Query.FilterOperator.LESS_THAN, today);
		 for (Entity taskEntity : datastore.prepare(query).asIterable()) {
		   if ("done".equals(taskEntity.getProperty("status"))) {
		     datastore.delete(taskEntity);
		   } else {
		     taskEntity.setProperty("status", "overdue");
		     datastore.put(taskEntity);
		   }
		 }
		  */
		
		String channelId = req.getHeader("channelId");
		
		if (channelId == null) { // we need to create a new channel
			System.out.println("Create a new one!");
			String appId = "xusClient_"+_ai.getAndIncrement();
			channelId = ChannelServiceFactory.getChannelService().createChannel(appId);
			resp.setHeader("channelId", channelId);
			System.out.println("Registering " + channelId + " => " + appId);
			channels.put(channelId, appId);
		}
		
		String params[] = cmd.getArgs();
		switch (cmd) {
		case set:
			//Entity taskEntity = new Entity(req.getParameter(params[1]), req.getParameter(params[2]));
		    //datastore.put(taskEntity);
			String key = req.getParameter(params[1]);
			
			data.put(key, req.getParameter(params[2]));

			List<String> l = listeners.get(key);
			if (l != null) {
				for (String channel : l) {
					// if (channel != channelId) { // Do we want to -not- send an update to ourselfs?
						String appId = channels.get(channel);
						System.out.println("Server: " +channel + " => " + appId);
						
						if (appId != null) ChannelServiceFactory.getChannelService().sendMessage(new ChannelMessage(appId, key + " = " + req.getParameter(params[2])));
					//}
				}
			}
			

			
			
			break;
		case listen:
			key = req.getParameter(params[1]);
			l = listeners.get(key);
			if (l==null) l = new ArrayList<String>();
			if (!l.contains(channelId)) {
				l.add(channelId);
				listeners.put(key, l);
			}
			
			break;
			
		case unlisten:
			key = req.getParameter(params[1]);
			l = listeners.get(key);
			if (l!=null) l.remove(channelId);
			
			break;
		default:
			break;
		}
		
		//datastore.get(KeyFactory.req.getParameter(params[1]));
		
	}
	
}
