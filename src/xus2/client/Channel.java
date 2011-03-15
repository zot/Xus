package xus2.client;

import com.google.gwt.core.client.JavaScriptObject;

public class Channel extends JavaScriptObject {
    protected Channel() {
    }

    public final native Socket open(SocketListener listener) /*-{
	var socket = this.open();
        socket.onopen = function(event) {
          listener.@xus2.client.SocketListener::onOpen()();
        };
        socket.onmessage = function(event) {
          listener.@xus2.client.SocketListener::onMessage(Ljava/lang/String;)(event.data);
        };
        return socket;
    }-*/;
}
