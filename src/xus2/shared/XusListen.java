package xus2.shared;


public class XusListen extends XusCommand {

	private String msgId;
	private String key;

	public XusListen(String... args) {
		this.msgId = String.valueOf(args[0]);
		this.key = String.valueOf(args[1]);
	}
	
	public String getKey() {return key;}
	public String getMsgId() { return msgId; };

	public String sendString() {
		return "/xus2/xus?cmd=listen('"+msgId+"','"+key+"')";
	}


}
