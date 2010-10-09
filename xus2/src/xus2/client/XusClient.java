package xus2.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xus2.shared.Effect;
import xus2.shared.MalformedXusCommandException;
import xus2.shared.XusCommand;
import xus2.shared.XusListen;
import xus2.shared.XusParser;
import xus2.shared.XusSet;
import xus2.shared.XusUnlisten;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

public class XusClient  {

	
	private String channelId = "none";
	private boolean channelStarted = false;

	int msgIdAi = 0;
	
	public XusClient () {
		
	}
	
	XusIncommingHandler handler = null;

	public void addIncomingHandler(XusIncommingHandler xusIncommingHandler) {
		handler = xusIncommingHandler;
	}
	
	Map<String, Effect<XusSet>> setSuccessListeners = new HashMap<String, Effect<XusSet>>();
	Map<String, Effect<XusSet>> setFailureListeners = new HashMap<String, Effect<XusSet>>();
	
	// A local map, of keys to there listeners. a key can contain more then one listener 
	Map<String, List<Effect<XusSet>>> listenHandlers= new HashMap<String, List<Effect<XusSet>>>();
	
	// an empty listener
	private static final Effect<XusSet> noHandler = new Effect<XusSet>() {@Override public void e(XusSet t) {}};
	
	private void onRecievedMessage(XusCommand xusCommand) {
		if (xusCommand instanceof XusSet) {
			
			// if we received set message, so we naturally succeeded, 
			// remove the failure for this msgId
			XusSet set = (XusSet) xusCommand;
			Effect<XusSet> sl = setSuccessListeners.get(set.getMsgId());
			setFailureListeners.remove(set.getMsgId());
			if (sl != null) {
				setSuccessListeners.remove(set.getMsgId());
				sl.e(set);
			}
			
			String nKey = set.getKey();
			int i = nKey.length();
			
			do {
				nKey = nKey.substring(0, i); 
				
				 List<Effect<XusSet>> ll = listenHandlers.get(nKey);
				
				if (ll != null) {
					
					for ( Effect effect: ll) {
						effect.e(set);
					}
				}				
			} while ((i = nKey.lastIndexOf("."))>0);
			
		}
		
		if (handler != null) handler.recievedMessage(xusCommand);
	}
	
	protected Effect<XusSet> xusListen(String key) {
		return xusListen(key, null);
	}
	
	protected Effect<XusSet> xusListen(String key, Effect<XusSet> handler) {
		
		boolean send = false;
		
		if (handler == null) handler = noHandler;
		
		// check if we already have a listener on this key
		List<Effect<XusSet>> l = listenHandlers.get(key);
		
		// if we dont have we will have to register the listener with the server
		if (l==null) {
			l = new ArrayList<Effect<XusSet>>();
			send = true;
		}
		
		// add the handler to the list
		l.add(handler);
		
		// register the handler list with the map for that key
		listenHandlers.put(key, l);
		
		// we do need to send, so generate a xusListen and send it
		if (send) {			
			String msgId = channelId+(msgIdAi++);
			xusSend(new XusListen(msgId, key).sendString());
		}
		
		return handler;
	}

	
	protected void xusUnlisten(String key) {
		xusUnlisten(key, null);
	}
	
	
	/*
	 * if handler is null - remove all local, send remove key to server
	 * 
	 * remove handler from listenHandlers list - if after removal, we have no listeners, send remove key to server
	 */
	protected void xusUnlisten(String key, Effect<XusSet> handler) {
		
		boolean send = false;
		
		// if no handler was supplied, remove the entire list of listeners for this key
		if (handler == null) {
			listenHandlers.remove(key);
			send = true;
		} else {
		
			// a handler was supplied, so find it in the listener list for this key
			List<Effect<XusSet>> l = listenHandlers.get(key);
			
			// if there's no listener, nothing to do really..
			if (l != null) {
				
				// There is a listener, so remove this handler from the listener list
				l.remove(handler);
				
				// if the size is 0, remove it from the server
				send = l.size() == 0;
			}			
		}
		
		if (send) {
			// send an unlisten to the server
			String msgId = channelId+(msgIdAi++);
			xusSend(new XusUnlisten(msgId, key).sendString());
		}
		
	}

	protected void xusSet(String key, Object val, Effect<XusSet> success, Effect<XusSet> failure) {
		String msgId = channelId+(msgIdAi++);
		XusSet set = new XusSet(msgId,key, String.valueOf(val));
		if (success!=null)setSuccessListeners.put(msgId, success);
		if (failure!=null)setFailureListeners.put(msgId, failure);
		xusSend(set.sendString());
	}
	

	public void xusSend(String url) {
		RequestBuilder req = new RequestBuilder(RequestBuilder.GET, url);
		try {
			if (channelStarted) req.setHeader("channelId", channelId);
			req.sendRequest("", new RequestCallback() {
				
				@Override
				public void onResponseReceived(Request request, Response response) {
					if (!channelStarted) {
						channelId = response.getHeader("channelId");
						System.out.println("client " + channelId);
						channelStarted = true;
						
						Channel channel = ChannelFactory.createChannel(channelId);
						channel.open(new SocketListener() {
							
							@Override
							public void onOpen() {
								
							}
							
							@Override
							public void onMessage(String message) {
								//if (handler!=null)handler.recievedMessage(message);
								try {
									onRecievedMessage(XusParser.parse(message));
								} catch (MalformedXusCommandException e) {
									e.printStackTrace();
								}
							}
						});
						
									
						
						System.out.println("=> set up channel " + channelId);
					}
				}
				
				@Override
				public void onError(Request request, Throwable exception) {
					exception.printStackTrace();
					
				}
			});
		} catch (RequestException e) {
			e.printStackTrace();
		}
	}
	


}
