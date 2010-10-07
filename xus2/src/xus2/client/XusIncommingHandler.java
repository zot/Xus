package xus2.client;

import xus2.shared.XusCommand;

public interface XusIncommingHandler {
	public void recievedMessage(XusCommand cmd);
}
