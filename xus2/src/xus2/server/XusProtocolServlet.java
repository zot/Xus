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

import xus2.shared.MalformedXusCommandException;
import xus2.shared.XusCommand;
import xus2.shared.XusListen;
import xus2.shared.XusParser;
import xus2.shared.XusSet;
import xus2.shared.XusUnlisten;

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

	
	
	AtomicInteger _ai = new AtomicInteger();
	
	DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			
			String originalCmd = req.getParameter("cmd");
			
			XusCommand cmd = XusParser.parse(originalCmd);
			
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
			
			if (cmd instanceof XusSet) {
				XusSet set = (XusSet) cmd;
				
				boolean repliedToSender = false;
				
				String msgId = set.getMsgId();
				String key = set.getKey();
				String val = set.getValue();
				
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
							
							if (channel.equals(channelId)) {
								repliedToSender = true;
							}
							
							
							String appId;
							try {
								appId = (String) datastore.get(KeyFactory.createKey("channelId", channel)).getProperty("appId");
								
								try {
									if (appId != null) ChannelServiceFactory.getChannelService().sendMessage(new ChannelMessage(appId, "set('"+msgId+"','"+key+"','"+val+"')"));
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
					
				if (!repliedToSender) {
					try {
						Entity channel = datastore.get(KeyFactory.createKey("channelId", channelId));
						String appId = (String) channel.getProperty("appId");
						if (appId != null) ChannelServiceFactory.getChannelService().sendMessage(new ChannelMessage(appId, "set('"+msgId+"','"+key+"','"+val+"')"));							
					} catch (EntityNotFoundException e) {
						e.printStackTrace();
					}
				}
			
			
			} else if (cmd instanceof XusListen) {
				XusListen listen = (XusListen) cmd;
				
				String key = listen.getKey();
				
				if (key.length() == 0) return;
				
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
			} else if (cmd instanceof XusUnlisten) {
				XusUnlisten unlisten = (XusUnlisten) cmd;
				String key = unlisten.getKey();
				
				Key listenerKey = KeyFactory.createKey("listener", key);
				try {
					Entity listener = datastore.get(listenerKey);
					List<String> channels = (List<String>) listener.getProperty("channels");
					if (channels != null) { 
						channels.remove(channelId);
						listener.setProperty("channels", channels);
						
						if (channels.size() == 0) {
							datastore.delete(listenerKey);
						}
						else {
							datastore.put(listener);
						}
					}
				} catch (EntityNotFoundException e) {
				}
			}
		
			
		} catch (MalformedXusCommandException e) {
			e.printStackTrace();
		}
	
	}
}
