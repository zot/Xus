####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

SRC=transport proto browser peer base websocket
EXAMPLES=echo computed leisureService
CMD=main pfs
JS=$(SRC:%=lib/%.js)
CMD_JS=$(CMD:%=lib/%.js)
EX_JS=$(EXAMPLES:%=lib/%.js)
ALL_JS=$(JS) $(CMD_JS) $(EX_JS)
ALL_SRC=$(SRC:%=src/%.coffee) $(CMD:%=src/%.coffee) $(EXAMPLES:%=examples/%.coffee)

all: browser

browser: xus.js

xus.js: $(ALL_JS)

lint: coffeelint

jslint: $(ALL_JS)
	for i in $(ALL_JS); do jsl --vars --white --sloppy --nomen --undef $$i; done

coffeelint: $(ALL_SRC)
	for i in $(ALL_SRC); do node_modules/coffeelint/bin/coffeelint -f coffeelint.json $$i; done

xus.js: $(JS)
	node_modules/browserify/bin/cmd.js lib/browser.js -o xus.js

clean: FRC
	rm -f $(JS) $(CMD_JS) $(EX_JS)

FRC:

lib/%.js: src/%.coffee
	node_modules/coffee-script/bin/coffee -m -o lib -c $<

lib/%.js: examples/%.coffee
	node_modules/coffee-script/bin/coffee -m -o lib -c $<
