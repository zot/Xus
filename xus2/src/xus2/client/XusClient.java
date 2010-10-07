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
	Map<String, Map<Integer, Effect<XusSet>>> listenHandlers= new HashMap<String, Map<Integer,Effect<XusSet>>>();
	
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
			boolean handled = false;
			do {
				nKey = nKey.substring(0, i); 
				
				Map<Integer, Effect<XusSet>> ll = listenHandlers.get(nKey);
				
				if (ll != null) {
					
					for ( Effect effect: ll.values()) {
						effect.e(set);
					}
					handled = true;
				}				
			} while (!handled && (i = nKey.lastIndexOf("."))>0);
			
		}
		
		if (handler != null) handler.recievedMessage(xusCommand);
	}
	
	protected void xusListen(String key, int unique, Effect<XusSet> handler) {
		String msgId = channelId+(msgIdAi++);
		XusListen listen = new XusListen(msgId, key);
		if (handler!=null) {
			Map<Integer, Effect<XusSet>> l = listenHandlers.get(key);
			
			if (l == null) {
				l = new HashMap<Integer, Effect<XusSet>>(4);
				l.put(unique, handler);
			}
			else {
				l.put(unique, handler);
			}
			
			listenHandlers.put(key, l);
		}
		else {
			
		}
		xusSend(listen .sendString());
	}

	protected void xusSet(String key, Object val, Effect<XusSet> success, Effect<XusSet> failure) {
		String msgId = channelId+(msgIdAi++);
		XusSet set = new XusSet(msgId,key, String.valueOf(val));
		if (success!=null)setSuccessListeners.put(msgId, success);
		if (failure!=null)setFailureListeners.put(msgId, failure);
		xusSend(set.sendString());
	}
	

	protected void xusUnlisten(String key) {
		String msgId = channelId+(msgIdAi++);
		XusUnlisten unlisten = new XusUnlisten(msgId, key);
		listenHandlers.remove(key);
		xusSend(unlisten .sendString());
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
