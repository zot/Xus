package xus2.shared;

import java.util.ArrayList;
import java.util.List;

public class XusParser {

	
	public enum XusProtocol {
		set("msgId", "key", "value"),
		listen("msgId", "key"),
		unlisten("msgId", "key")
		;
		
		String[] _args;
		private XusProtocol(String... args) {
			_args = args;
		}
		
		public String[] getArgs() {return _args;};
	};
	
	
	static public XusCommand parse(String command) throws MalformedXusCommandException {
		command = command.trim();
		int i = command.indexOf('(');
		String argString = command.substring(i+1, command.length()-1).trim();
		
		System.out.println("Working on " + command + " -- " + command.substring(0, i).trim() + " --  " + argString);
		
		
		XusProtocol cmd = null;
		try {
			cmd = XusProtocol.valueOf(command.substring(0, i).trim());
		} catch (Exception e) {
			throw new MalformedXusCommandException("cant parse " + command);
		}
		
		List<String> args = parseArgs(argString);
		switch (cmd) {
		case set:
			if (args.size()<3) {
				throw new MalformedXusCommandException("cant parse " + command + " not enough args for protocol");
			}
			
			return new XusSet(args);
			
		case listen:
			if (args.size()>3) {
				throw new MalformedXusCommandException("cant parse " + command + " too many args for protocol");
			}
			
			return new XusListen(args);
			
		case unlisten:
			if (args.size()>2) {
				throw new MalformedXusCommandException("cant parse " + command + " too many args for protocol");
			}
			
			return new XusUnlisten(args);

		default:
			break;
		}
		
		return null;
	}
	
	private static List<String> parseArgs(String str) {
		str = str.trim();
		
		boolean inString = false;
		boolean finishedArg= false;
		boolean startedArg = true;
		
		StringBuffer currString = new StringBuffer();
		
		List<String> argsList = new ArrayList<String>(3);
		
		for (int i=0; i<str.length();i++) {
			char c = str.charAt(i);
			
			if (!inString && c == '\'') {
				currString = new StringBuffer();
				inString = true;
				finishedArg = false;
				continue;
			}
			
			if (inString && c == '\'') {
				argsList.add(currString.toString());
				inString = false;
				finishedArg = true;
				continue;
			}
			
			if (inString) {
				currString.append(c);
				continue;
			}
			
			if (finishedArg && c == ',') {
				finishedArg = false;
				startedArg = true;
				continue;
			}
			
			if (c == ' ') {
				continue;
			}
			
			System.out.println("ERROR2 in idx " + i);
			
		}
		
		return argsList; 
	}
}
