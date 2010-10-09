package xus2.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import xus2.shared.Effect;
import xus2.shared.XusSet;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.ListBox;
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
	
	/**
	 * This is the entry point method.
	 */
	public void onModuleLoad() {
		
		System.err.println("ytytyyty ");
		final XusClient xusClient = new XusClient();
		
		final ListBox list = ListBox.wrap(DOM.getElementById("output"));
		
		//final DivElement output = DOM.getElementById("output").cast();
		
		final DivElement output = DOM.getElementById("Canvas").cast();
		final Button  listenRectBtn    = Button.wrap(DOM.getElementById("listenRect"));
		final Button  unlistenRectBtn    = Button.wrap(DOM.getElementById("unlistenRect"));

		final Button  clearBtn    = Button.wrap(DOM.getElementById("clear"));
		
		final Button  listenBtn    = Button.wrap(DOM.getElementById("listen"));
		final TextBox listenKey   = TextBox.wrap(DOM.getElementById("listenKey"));
		
		
		final Button  unlistenBtn    = Button.wrap(DOM.getElementById("unlisten"));
		final TextBox unlistenKey   = TextBox.wrap(DOM.getElementById("unlistenKey"));
		
		final Button  setBtn    = Button.wrap(DOM.getElementById("set"));
		final TextBox setKey   = TextBox.wrap(DOM.getElementById("setKey"));
		final TextBox setVal   = TextBox.wrap(DOM.getElementById("setVal"));
			
		output.setClassName("square");
		
		final HashMap<String, Integer> rect = new HashMap<String, Integer>(4);
		
		final List<Effect<XusSet>> l = new ArrayList<Effect<XusSet>>();
		
		listenRectBtn.addClickHandler(new ClickHandler() {
			
			@Override
			public void onClick(ClickEvent event) {
				
				Effect<XusSet> e = xusClient.xusListen("rect", new Effect<XusSet>() {
					
					@Override
					public void e(XusSet t) {
						System.out.println("got rect");
						rect.put(t.getKey(), Integer.valueOf(t.getValue()));
						
						output.getStyle().setTop(rect.containsKey("rect.t") ? rect.get("rect.t") : 0, Unit.PX);
						output.getStyle().setLeft(rect.containsKey("rect.l") ? rect.get("rect.l") : 0, Unit.PX);
						output.getStyle().setWidth(rect.containsKey("rect.w") ? rect.get("rect.w") : 0, Unit.PX);
						output.getStyle().setHeight(rect.containsKey("rect.h") ? rect.get("rect.h") : 0, Unit.PX);
					}
				});
				
				l.add(e);
			}
		});
		
		unlistenRectBtn.addClickHandler(new ClickHandler() {
			
			@Override
			public void onClick(ClickEvent event) {
				for (Effect<XusSet> effect : l) {
					xusClient.xusUnlisten("rect", effect);					
				}
				
				l.clear();
				
			}
		});
		
		
		//xusClient.xusSet("rect.width", 0, null, null);
		//xusClient.xusSet("rect.height", 0, null, null);
		//xusClient.xusSet("rect.top", 0, null, null);
		//xusClient.xusSet("rect.left", 0, null, null);
		
		//output.setInnerHTML("Started");
		list.addItem("Started");
		
		clearBtn.addClickHandler(new ClickHandler() {
			
			@Override
			public void onClick(ClickEvent event) {
				list.clear();
				list.addItem("Cleared");
				
				//output.setInnerHTML("Cleared");
			}
		});
		
		/*
		xusClient.addIncomingHandler(new XusIncommingHandler(){
			@Override
			public void recievedMessage(XusCommand cmd) {
				output.setInnerHTML(output.getInnerHTML()+"<li>" + cmd.toString() + "</li>");
				System.out.println("(client) Got " + cmd.toString());
				
			}
		});
		*/

		listenBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				xusClient.xusListen(listenKey.getText(), new Effect<XusSet>() {
					
					@Override
					public void e(XusSet set) {
						//output.setInnerHTML(output.getInnerHTML()+"<li>" + set.getKey() + " = " + set.getValue() + "</li>");
						list.addItem(set.getKey() + " = " + set.getValue());
						System.out.println("(listen) Got " + set.sendString());
					}
				});
			}
		});
		
		unlistenBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				xusClient.xusUnlisten(unlistenKey.getText());
			}
		});
		
		setBtn.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				
				xusClient.xusSet(setKey.getText(), setVal.getText() ,new Effect<XusSet>() {
					
					@Override
					public void e(XusSet set) {
						System.out.println("(set) Got " + set.sendString());
					}
				}, null);
			}
		});
		
		xusClient.xusSend("/xus2/xus?cmd=init");
	}
}
