package xus2.shared;

import java.util.List;

public class XusUnlisten extends XusCommand {
	private String msgId;
	private String key;

	public XusUnlisten(List<String> args) {
		this.msgId = args.get(0);
		this.key = args.get(1);		
	}

	public String getMsgId() {return msgId;};
	public String getKey() {return key;};
}
