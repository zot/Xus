package xus2.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.channel.ChannelFailureException;
import com.google.appengine.api.channel.ChannelMessage;
import com.google.appengine.api.channel.ChannelServiceFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.FilterOperator;

@SuppressWarnings("serial")
public class XusProtocolServlet extends HttpServlet {

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
	
	DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
	
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		
		XusProtocol cmd = null;
		try {
			cmd = XusProtocol.valueOf(req.getParameter("cmd"));
		}catch (Exception e) {
			System.err.println("Command " + req.getParameter("cmd") + " is not valid..");
			return;
		}
		
		
		//HttpSession session = req.getSession(true);
		//System.out.println("session = " + session);
		 // Get a handle on the datastore itself

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
			
			Entity channelEnt = new Entity(KeyFactory.createKey("channelId", channelId));
			channelEnt.setProperty("appId", appId);
			datastore.put(channelEnt);
		}
		
		String params[] = cmd.getArgs();
		switch (cmd) {
		case set:
			String msgId = req.getParameter(params[0]);
			String key = req.getParameter(params[1]);
			String val = req.getParameter(params[2]);
			
			Entity var = new Entity(KeyFactory.createKey("var", key));
			var.setProperty("value", val);
			
			datastore.put(var);
			
			int i = key.length();
			
			String nKey = key;
			
			// The same as below, but using queries
			Query q = new Query("listener");
			
			ArrayList<Key> k = new ArrayList<Key>();
			
			do {
				nKey = nKey.substring(0, i); 
				k.add(KeyFactory.createKey("listener",nKey));
			} while ((i = nKey.lastIndexOf("."))>0);
			
			q.addFilter("__key__", FilterOperator.IN, k);
			
			Iterator<Entity> iter = datastore.prepare(q).asIterable().iterator();
			
			while (iter.hasNext()) {
				Entity listenerEnt = iter.next();
				String curKey = listenerEnt.getKey().getName();
				
				List<String> listeners = (List<String>) listenerEnt.getProperty("channels");
				
				if (listeners != null) {
					System.out.println("holding " + listeners.size() + " listeners for key '" + curKey + "'");
					
					Iterator<String> channelIter = listeners.iterator();
					
					while (channelIter.hasNext()) {
						String channel = channelIter.next();
						String appId;
						try {
							appId = (String) datastore.get(KeyFactory.createKey("channelId", channel)).getProperty("appId");
							//
							String dVal = null;
							try {
								dVal = (String) datastore.get(KeyFactory.createKey("var", curKey)).getProperty("value");
							} catch (EntityNotFoundException e) {}
							
							try {
								if (appId != null) ChannelServiceFactory.getChannelService().sendMessage(new ChannelMessage(appId, "set('"+msgId+"', '"+curKey + "', '" + dVal + "')"));
							} catch (ChannelFailureException e) {
								System.out.println("listener's channel is dead, removing. ");
								channelIter.remove();
								
								if (listeners.isEmpty()) {
									System.out.println("no more listeners, removing..");
									datastore.delete(listenerEnt.getKey());
								} else {
									listenerEnt.setProperty("channels", listeners);
									datastore.put(listenerEnt);
								}
							}
						
						} catch (EntityNotFoundException e) {
							e.printStackTrace();
						}
					}
					
				} else {
					System.err.println("Got a null channel for listener on '" + nKey +"'. Wierd.");
					continue;
				}
			}
			/*// same as above, without queries 
			do {
				nKey = nKey.substring(0, i);
				
				System.out.println("working on key: " + nKey);
				
				Key listenerKey = KeyFactory.createKey("listener", nKey);
				
				try {
					Entity listener = datastore.get(listenerKey);
					
					List<String> l = (List<String>) listener.getProperty("channels");
					
					
					if (l != null) {
						System.out.println("holding " + l.size() + " listeners for key '" + nKey + "'");
						Iterator<String> iter = l.iterator();
						
						while (iter.hasNext()) {
							String channel = iter.next();
							String appId;
							try {
								appId = (String) datastore.get(KeyFactory.createKey("channelId", channel)).getProperty("appId");
								//System.out.println("Server: " +channel + " => " + appId);
								String dVal = null;
								try {
									dVal = (String) datastore.get(KeyFactory.createKey("var", nKey)).getProperty("value");
								} catch (EntityNotFoundException e) {}
								
								try {
									if (appId != null) ChannelServiceFactory.getChannelService().sendMessage(new ChannelMessage(appId, "set('"+msgId+"', '"+nKey + "', '" + dVal + "')"));
								} catch (ChannelFailureException e) {
									System.out.println("listener's channel is dead, removing. ");
									iter.remove();
									listener.setProperty("channels", l);
									datastore.put(listener);
								}
							
							} catch (EntityNotFoundException e) {
								e.printStackTrace();
							}
						}
					}					
				}
				catch (EntityNotFoundException e) {
				}
				
			} while ((i = nKey.lastIndexOf(".")) > 0) ;
			*/
			break;
		case listen: {
			key = req.getParameter(params[1]);
			
			Key listenerKey = KeyFactory.createKey("listener", key);
			
			Entity dl = null;
			List<String> channels = null;
			try {
				dl = datastore.get(listenerKey);
				channels =  (List<String>) dl.getProperty("channels");
				if (channels == null) channels = new ArrayList<String>(); 
			}catch (EntityNotFoundException e) {
				dl = new Entity(listenerKey);
				channels = new ArrayList<String>();
			}
			
			if (!channels.contains(channelId)) {
				channels .add(channelId);
				dl.setProperty("channels", channels);
				datastore.put(dl);
			}
		} break;
		case unlisten: {
			key = req.getParameter(params[1]);
			
			Key listenerKey = KeyFactory.createKey("listener", key);
			try {
				Entity listener = datastore.get(listenerKey);
				List<String> channels = (List<String>) listener.getProperty("channels");
				channels.remove(channelId);
				listener.setProperty("channels", channels);
				datastore.put(listener);
			} catch (EntityNotFoundException e) {
			}
		} break;
		default:
			break;
		}
		
		//datastore.get(KeyFactory.req.getParameter(params[1]));
		
	}
	
}
