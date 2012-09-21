SRC=main proto local
JS=$(SRC:%=lib/%.js)

all: $(JS) browser

browser: FRC
	node_modules/browserify/bin/cmd.js lib/main.js -o xus.js

clean: FRC
	rm -f $(JS)

FRC:

lib/%.js: src/%.coffee
	node_modules/coffee-script/bin/coffee -o lib -c $<
