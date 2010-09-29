package xus2.client;

public class ChannelFactory {
    public static final native Channel createChannel(String channelId) /*-{
      return new $wnd.goog.appengine.Channel(channelId);	
    }-*/;
}
