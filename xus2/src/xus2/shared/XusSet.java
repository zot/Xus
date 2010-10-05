package xus2.shared;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class XusSet extends XusCommand {
	private String msgId;
	List<KeyValue<String, String>> vals;

	public XusSet(List<String> args) {
		Iterator<String> iter = args.iterator();
		this.msgId = iter.next();
		
		vals = new ArrayList<KeyValue<String,String>>((args.size()-1)/2);
		
		try {
			while (iter.hasNext()) {
				vals.add(new KeyValue<String, String>(iter.next(), iter.next()));
			}
		}catch (NoSuchElementException e) {
			System.out.println("bad problem! ");
		}
	}
	
	public List<KeyValue<String, String>> getKeyValues() { return vals;};
	public String getMsgId() {return msgId;};
}
