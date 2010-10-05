package xus2.client;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

public class XusClient  {

	
	private String channelId = "none";
	private boolean channelStarted = false;

	
	public XusClient () {
		
	}
	
	XusIncommingHandler handler = null;

	public void addIncomingHandler(XusIncommingHandler xusIncommingHandler) {
		handler = xusIncommingHandler;
		
	}
	
	protected void xusListen(String msgId, String key) {
		xusSend("/xus2/xus?cmd=listen('"+msgId+"','"+key+"')");
		
	}

	protected void xusSet(String msgId, String... vals ) {

		StringBuilder b = new StringBuilder().append("/xus2/xus?cmd=set('").append(msgId).append("',");
		
		for (String val: vals) {
			if (val != null && val.length()!=0) b.append("'").append(val).append("',");
		}
		
		b.delete(b.length()-1, b.length()).append(")");;
		
		xusSend(b.toString());
	}

	protected void xusUnlisten(String msgId, String key) {
		xusSend("/xus2/xus?cmd=unlisten('"+msgId+"','"+key+"')");
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
								if (handler!=null)handler.recievedMessage(message);
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
