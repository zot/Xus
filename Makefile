####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

SRC=transport proto browser peer base
EXAMPLES=echo computed
CMD=main pfs websocket
JS=$(SRC:%=lib/%.js)
CMD_JS=$(CMD:%=lib/%.js)
EX_JS=$(EXAMPLES:%=lib/%.js)
ALL_JS=$(JS) $(CMD_JS) $(EX_JS)
ALL_SRC=$(SRC:%=src/%.coffee) $(CMD:%=src/%.coffee) $(EXAMPLES:%=examples/%.coffee)

all: $(ALL_JS) browser

browser: xus.js

lint: coffeelint

jslint: $(ALL_JS)
	for i in $(ALL_JS); do jsl --vars --white --sloppy --nomen --undef $$i; done

coffeelint: $(ALL_SRC)
	for i in $(ALL_SRC); do node_modules/coffeelint/bin/coffeelint -f coffeelint.json $$i; done

xus.js: $(CMD_JS) $(JS)
	node_modules/browserify/bin/cmd.js lib/browser.js -o xus.js

clean: FRC
	rm -f $(JS) $(CMD_JS) $(EX_JS)

FRC:

lib/%.js: src/%.coffee
	node_modules/coffee-script/bin/coffee -o lib -c $<

lib/%.js: examples/%.coffee
	node_modules/coffee-script/bin/coffee -o lib -c $<
