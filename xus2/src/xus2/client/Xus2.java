package xus2.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.ListBox;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class Xus2 implements EntryPoint {
	/**
	 * The message displayed to the user when the server cannot be reached or
	 * returns an error.
	 */
	private static final String SERVER_ERROR = "An error occurred while "
			+ "attempting to contact the server. Please check your network "
			+ "connection and try again.";

	/**
	 * Create a remote service proxy to talk to the server-side Greeting service.
	 */
	private final GreetingServiceAsync greetingService = GWT.create(GreetingService.class);
	
	private String channelId = "none";
	private boolean channelStarted = false;

	final TextBox value = new TextBox();

	private ListBox listBox;
	/**
	 * This is the entry point method.
	 */
	public void onModuleLoad() {
		final Label errorLabel = new Label();
		System.err.println("ytytyyty " + channelId);
		// Add the nameField and sendButton to the RootPanel
		// Use RootPanel.get() to get the entire body element
		RootPanel rootPanel = RootPanel.get("setCommand");
		
		HorizontalPanel unListenPanel = new HorizontalPanel();
		
		unListenPanel.setSize("271px", "29px");
		
		Button unlisten = new Button("New button");
		unlisten.setText("unlisten");
		unListenPanel.add(unlisten);
		unlisten.setSize("100%", "27px");
		
		final TextBox unlisten_msgId = new TextBox();
		unlisten_msgId.setVisibleLength(8);
		unListenPanel.add(unlisten_msgId);
		
		final TextBox unlisten_key = new TextBox();
		unlisten_key.setVisibleLength(8);
		unlisten_key.setText("test");
		unListenPanel.add(unlisten_key);
		
		
		
		HorizontalPanel setPanel = new HorizontalPanel();
		rootPanel.add(setPanel, 92, 112);
		setPanel.setSize("271px", "29px");
		
		Button set = new Button("New button");
		setPanel.add(set);
		set.setSize("100%", "27px");
		set.setText("set");
		
		final TextBox msgId = new TextBox();
		setPanel.add(msgId);
		msgId.setVisibleLength(8);
		
		final TextBox key = new TextBox();
		key.setText("test");
		key.setVisibleLength(8);
		setPanel.add(key);
		
		value.setText("ASS");
		value.setVisibleLength(8);
		setPanel.add(value);
		
		rootPanel.add(setPanel);
		
		HorizontalPanel listenPanel = new HorizontalPanel();
		rootPanel.add(listenPanel);
		listenPanel.setSize("271px", "29px");
		
		rootPanel.add(unListenPanel);
		
		Button listen = new Button("New button");
		listen.setText("listen");
		listenPanel.add(listen);
		listen.setSize("100%", "27px");
		
		final TextBox listen_msgId = new TextBox();
		listen_msgId.setVisibleLength(8);
		listenPanel.add(listen_msgId);
		
		final TextBox listen_key = new TextBox();
		listen_key.setText("test");
		listen_key.setVisibleLength(8);
		listenPanel.add(listen_key);

		RootPanel.get("errorLabelContainer").add(errorLabel);
		
		listBox = new ListBox();
		rootPanel.add(listBox, 10, 115);
		listBox.setSize("241px", "156px");
		listBox.setVisibleItemCount(5);

		unlisten.addClickHandler(new ClickHandler() {
			public void onClick(ClickEvent event) {
				xusSend("/xus2/xus?cmd=unlisten&msgId="+unlisten_msgId.getText()+"&key="+unlisten_key.getText());
				System.out.println("not listening to " + unlisten_key.getText());
			}
		});
		listen.addClickHandler(new ClickHandler() {
			public void onClick(ClickEvent event) {
				xusSend("/xus2/xus?cmd=listen&msgId="+listen_msgId.getText()+"&key="+listen_key.getText());
				System.out.println("listening to " + key.getText());
			}
		});
		set.addClickHandler(new ClickHandler() {
			public void onClick(ClickEvent event) {
				xusSend("/xus2/xus?cmd=set&msgId="+msgId.getText()+"&key="+key.getText()+"&value="+value.getText());
			}
		});
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
								recievedPushMessage(message);
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
	
	private void recievedPushMessage(String message) {
		System.out.println("Client: Push (" +channelId + ") : "+ message + " " );
		//value.setText(message.split(" = ")[1]);
		listBox.addItem(message);
		
		
	}
}
