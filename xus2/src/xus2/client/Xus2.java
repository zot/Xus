package xus2.client;

import xus2.shared.MalformedXusCommandException;
import xus2.shared.XusCommand;
import xus2.shared.XusParser;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextBox;

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
	

	final TextBox value = new TextBox();

	private ListBox listBox;
	/**
	 * This is the entry point method.
	 */
	public void onModuleLoad() {
		
		System.err.println("ytytyyty ");
		final XusClient xusClient = new XusClient();
		
		final DivElement output = DOM.getElementById("output").cast();

		final Button  clearBtn    = Button.wrap(DOM.getElementById("clear"));
		
		final Button  listenBtn    = Button.wrap(DOM.getElementById("listen"));
		final TextBox listenMsgId = TextBox.wrap(DOM.getElementById("listenMsgId"));
		final TextBox listenKey   = TextBox.wrap(DOM.getElementById("listenKey"));
		
		final Button  unlistenBtn    = Button.wrap(DOM.getElementById("unlisten"));
		final TextBox unlistenMsgId = TextBox.wrap(DOM.getElementById("unlistenMsgId"));
		final TextBox unlistenKey   = TextBox.wrap(DOM.getElementById("unlistenKey"));
		
		final Button  setBtn    = Button.wrap(DOM.getElementById("set"));
		final TextBox setMsgId = TextBox.wrap(DOM.getElementById("setMsgId"));
		final TextBox setkeyVal[] = {
				TextBox.wrap(DOM.getElementById("setKey1")), TextBox.wrap(DOM.getElementById("setVal1")),
				TextBox.wrap(DOM.getElementById("setKey2")), TextBox.wrap(DOM.getElementById("setVal2")),
				TextBox.wrap(DOM.getElementById("setKey3")), TextBox.wrap(DOM.getElementById("setVal3"))
			};
		
		
		output.setInnerHTML("Started");
		
		clearBtn.addClickHandler(new ClickHandler() {
			
			@Override
			public void onClick(ClickEvent event) {
				output.setInnerHTML("Cleared");
			}
		});
		
		xusClient.addIncomingHandler(new XusIncommingHandler(){
			@Override
			public void recievedMessage(String msg) {
				output.setInnerHTML(output.getInnerHTML()+"<li>" + msg + "</li>");
				System.out.println("(client) Got " + msg);
				
				try {
					// parse the input
					XusCommand p = XusParser.parse(msg);
					
					System.out.println(p);
					
				} catch (MalformedXusCommandException e) {
					e.printStackTrace();
				}
			}
		});
		

		listenBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				xusClient.xusListen(listenMsgId.getText(), listenKey.getText());
			}
		});
		
		unlistenBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				xusClient.xusUnlisten(unlistenMsgId.getText(), unlistenKey.getText());
			}
		});
		
		setBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				
				String keyVal[] = new String[setkeyVal.length];
				
				int i=0;
				for (TextBox textBox : setkeyVal) {
					keyVal[i++] = textBox.getText();
				}
				
				xusClient.xusSet(setMsgId.getText(), keyVal);
			}
		});
	}
}
