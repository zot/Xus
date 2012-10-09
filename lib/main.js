// Generated by CoffeeScript 1.3.3
(function() {
  var Server, exports, log, parseAddr, path, pfs, run, setup, startProxy, startWebSocketServer, startXus, state, stateFd, usage;

  log = (exports = module.exports = require('./base')).log;

  startWebSocketServer = require('./websocket').startWebSocketServer;

  Server = (exports = require('./peer')).Server;

  pfs = require('./pfs');

  path = require('path');

  usage = function(args) {
    console.log("Usage: node " + args[1] + " name [-p port]");
    return process.exit();
  };

  stateFd = null;

  state = null;

  setup = function(cont) {
    var stateFile, xusDir;
    try {
      xusDir = path.join(process.env.HOME, '.xus');
      stateFile = path.join(xusDir, 'state');
      return pfs.stat(xusDir).fail(function() {
        return pfs.mkdir(xusDir);
      }).then(function() {
        return pfs.open(stateFile, 'a+');
      }).then(function(fd) {
        return pfs.flock((stateFd = fd), 'ex');
      }).then(function() {
        return pfs.readFile(stateFd);
      }).then(function(s) {
        return (cont || function() {})(s);
      }).end();
    } catch (err) {
      return console.log("Error: " + err.stack);
    }
  };

  run = function() {
    return setup(function(s) {
      var args, config, dir, httpServer, i, pattern, _ref;
      config = {
        proxy: false,
        verbose: function() {},
        addr: null,
        cmd: null
      };
      state = (s && JSON.parse(s)) || {
        servers: {}
      };
      i = 2;
      args = process.argv;
      if (i > args.length) {
        usage(args);
      }
      config.name = args[1];
      if (state.servers[config.name]) {
        console.log("Error: there is already a server named " + config.name);
        return process.exit(2);
      } else {
        while (i < args.length) {
          switch (args[i]) {
            case '-w':
              config.addr = args[++i];
              break;
            case '-e':
              require(args[++i]).main();
              break;
            case '-x':
              config.cmd = args[++i];
              break;
            case '-v':
              config.verbose = log;
              break;
            case '-p':
              config.proxy = true;
              break;
            case '-u':
              pattern = new RegExp("^" + args[++i] + "/");
              dir = path.resolve(args[++i]);
              exports.dirMap.push([pattern, new RegExp("^" + dir + "/"), "" + dir + "/"]);
          }
          i++;
        }
        _ref = parseAddr(config.addr || ':'), config.host = _ref[0], config.port = _ref[1];
        httpServer = startWebSocketServer(config, function() {
          console.log("Server " + config.name + " started on port: " + (httpServer.address().port));
          process.env.XUS_SERVER = config.name;
          process.env.XUS_PORT = httpServer.address().port;
          state.servers[config.name] = httpServer.address();
          state.servers[config.name].pid = process.pid;
          return pfs.truncate(stateFd, 0).then(function() {
            return pfs.writeFile(stateFd, JSON.stringify(state));
          }).then(function() {
            return pfs.close(stateFd);
          }).then(function() {
            if (config.cmd != null) {
              return require('child_process').spawn('/bin/sh', ['-c', config.cmd], {
                stdio: ['ignore', 1, 2]
              });
            } else {
              return 1;
            }
          }).end();
        });
        return (config.proxy ? startProxy : startXus)(config, httpServer);
      }
    });
  };

  startXus = function(config, httpServer) {
    var xusServer;
    exports.xusServer = xusServer = new Server();
    xusServer.verbose = config.verbose;
    return exports.connectXus(xusServer, httpServer);
  };

  startProxy = function(config, httpServer) {
    return exports.connectProxy(config, httpServer);
  };

  parseAddr = function(addr) {
    var host, parts, port, _ref;
    _ref = parts = addr.split(':'), host = _ref[0], port = _ref[1];
    if (parts.length > 2) {
      throw new Error("Bad address format, expected [host:]port, but got " + addr);
    }
    return (port && [host || null, port]) || [null, host || null];
  };

  exports.run = run;

  exports.setup = setup;

}).call(this);