// Generated by CoffeeScript 1.3.3
(function() {
  var Connection, ProxyMux, SocketConnection, WebSocketConnection, badPage, contentType, createHandler, dirMap, exports, extensions, fs, path, send, url, ws, _, _ref;

  exports = module.exports = require('./base');

  _ref = require('./transport'), ProxyMux = _ref.ProxyMux, SocketConnection = _ref.SocketConnection, WebSocketConnection = _ref.WebSocketConnection, Connection = _ref.Connection;

  ws = require('ws');

  _ = require('./lodash.min');

  path = require('path');

  fs = require('fs');

  send = require('send');

  url = require('url');

  exports.startWebSocketServer = function(config, ready) {
    var app;
    app = require('http').createServer(createHandler(config));
    if (config.port) {
      app.listen(config.port, config.host, ready);
    } else {
      app.listen(ready);
    }
    return app;
  };

  exports.dirMap = dirMap = [];

  extensions = {
    js: 'application/javascript',
    html: 'text/html',
    gif: 'image/gif',
    css: 'text/css',
    png: 'image/png'
  };

  createHandler = function(config) {
    return function(req, res) {
      var dir, dirPattern, file, pathname, urlPattern, _i, _len, _ref1;
      pathname = url.parse(req.url).pathname;
      for (_i = 0, _len = dirMap.length; _i < _len; _i++) {
        _ref1 = dirMap[_i], urlPattern = _ref1[0], dirPattern = _ref1[1], dir = _ref1[2];
        file = path.resolve(pathname.replace(urlPattern, dir));
        if (file.match(dirPattern)) {
          send(req, pathname.replace(urlPattern, "/")).root(dir).pipe(res);
          return;
        }
      }
      return badPage(req, res);
    };
  };

  contentType = function(file) {
    var _ref1;
    return (_ref1 = extensions[file.replace(/^.*\.([^.]*)$/, '$1')]) != null ? _ref1 : 'text/plain';
  };

  badPage = function(req, res) {
    res.writeHead(404);
    return res.end("<html><body>Web page " + req.url + " not available</body></html>");
  };

  exports.connectXus = function(xusServer, httpServer) {
    var wServer;
    xusServer.webSocketServer = httpServer;
    wServer = new ws.Server({
      noServer: true
    });
    return httpServer.on('upgrade', function(req, socket, head) {
      if (req.url === '/cmd') {
        return new SocketConnection(xusServer, socket, head);
      } else if (req.url === '/peer') {
        return wServer.handleUpgrade(req, socket, head, function(con) {
          return new WebSocketConnection(xusServer, con);
        });
      } else {
        return con.destroy();
      }
    });
  };

  exports.connectProxy = function(config, httpServer) {
    var proxy, wServer;
    proxy = new ProxyMux({
      processBatch: function(con, demuxedBatch) {
        proxy.verbose("proxy sending: " + (JSON.stringify(demuxedBatch)) + " to " + con.constructor.name);
        return con.send(demuxedBatch);
      }
    });
    proxy.verbose = config.verbose;
    wServer = new ws.Server({
      noServer: true
    });
    httpServer.on('upgrade', function(req, socket, head) {
      proxy.verbose("REQUEST: " + req.url);
      if (req.url === '/cmd') {
        return proxy.newSocketEndpoint(function(proxyCon) {
          return new SocketConnection(proxyCon, socket, head);
        });
      } else if (req.url === '/proxy') {
        return wServer.handleUpgrade(req, socket, head, function(con) {
          return new WebSocketConnection(proxy, con);
        });
      } else {
        return con.destroy();
      }
    });
    return proxy;
  };

}).call(this);