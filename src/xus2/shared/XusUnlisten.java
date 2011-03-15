package xus2.shared;


public class XusUnlisten extends XusCommand {
	private String msgId;
	private String key;

	public XusUnlisten(Object... args) {
		this.msgId = String.valueOf(args[0]);
		this.key = String.valueOf(args[1]);		
	}

	public String getMsgId() {return msgId;};
	public String getKey() {return key;}

	public String sendString() {
		return "/xus2/xus?cmd=unlisten('"+msgId+"','"+key+"')";
	};
}
