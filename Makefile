SRC=transport proto browser peer test base
CMD=main pfs websocket socket
JS=$(SRC:%=lib/%.js) lib/websocket-html.js
CMD_JS=$(CMD:%=lib/%.js)

all: $(CMD_JS) $(JS) browser

browser: xus.js

xus.js: $(CMD_JS) $(JS)
	node_modules/browserify/bin/cmd.js lib/browser.js -o xus.js

clean: FRC
	rm -f $(JS) $(CMD_JS)

FRC:

lib/websocket-html.js: src/websocket.html
	echo -n "module.exports = " > $@
	jshon -Q -s "`cat src/websocket.html`" >> $@

lib/%.js: src/%.coffee
	node_modules/coffee-script/bin/coffee -o lib -c $<
