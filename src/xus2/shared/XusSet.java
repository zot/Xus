package xus2.shared;

public class XusSet extends XusCommand {
	private String msgId;
	private String key, value;

	public XusSet(String msgId, String key, Object val) {
		this.msgId = msgId;
		this.key = key;
		this.value = String.valueOf(val);	
	}
	
	public String getMsgId() {return msgId;}

	public String sendString() {
		return "/xus2/xus?cmd=set('"+msgId+"','"+key+"','"+value+"')";
	}

	public String getKey() {
		return key;
	}

	public String getValue() {
		return value;
	}
}
