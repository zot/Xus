SRC=proto socket browser
CMD=main pfs
JS=$(SRC:%=lib/%.js)
CMD_JS=$(CMD:%=lib/%.js)

all: $(CMD_JS) $(JS) browser

browser: FRC
	node_modules/browserify/bin/cmd.js lib/browser.js -o xus.js

clean: FRC
	rm -f $(JS)

FRC:

lib/%.js: src/%.coffee
	node_modules/coffee-script/bin/coffee -o lib -c $<
