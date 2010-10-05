package xus2.shared;

import java.util.List;

public class XusListen extends XusCommand {

	private String msgId;
	private String key;

	public XusListen(List<String> args) {
		this.msgId = args.get(0);
		this.key = args.get(1);
	}
	
	public String getKey() {return key;};

}
