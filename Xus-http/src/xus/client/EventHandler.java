/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;

/**
 * @author bill Jun 28, 2010
 *
 */
public abstract class EventHandler {
	public abstract void onEvent(Event e);
	
	public String type;

	
	public EventHandler(String eventType) {
		type = eventType;
	}
	public void install(Element el) {
		el.setPropertyJSO(type, createFunction());
	}
	private native JavaScriptObject createFunction() /*-{
		var th = this;

		return function(evt) {
			th.@xus.client.EventHandler::onEvent(Lcom/google/gwt/user/client/Event;)(evt);
		};
	}-*/;
}
