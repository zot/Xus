(function(){var require = function (file, cwd) {
    var resolved = require.resolve(file, cwd || '/');
    var mod = require.modules[resolved];
    if (!mod) throw new Error(
        'Failed to resolve module ' + file + ', tried ' + resolved
    );
    var cached = require.cache[resolved];
    var res = cached? cached.exports : mod();
    return res;
};

require.paths = [];
require.modules = {};
require.cache = {};
require.extensions = [".js",".coffee",".json"];

require._core = {
    'assert': true,
    'events': true,
    'fs': true,
    'path': true,
    'vm': true
};

require.resolve = (function () {
    return function (x, cwd) {
        if (!cwd) cwd = '/';
        
        if (require._core[x]) return x;
        var path = require.modules.path();
        cwd = path.resolve('/', cwd);
        var y = cwd || '/';
        
        if (x.match(/^(?:\.\.?\/|\/)/)) {
            var m = loadAsFileSync(path.resolve(y, x))
                || loadAsDirectorySync(path.resolve(y, x));
            if (m) return m;
        }
        
        var n = loadNodeModulesSync(x, y);
        if (n) return n;
        
        throw new Error("Cannot find module '" + x + "'");
        
        function loadAsFileSync (x) {
            x = path.normalize(x);
            if (require.modules[x]) {
                return x;
            }
            
            for (var i = 0; i < require.extensions.length; i++) {
                var ext = require.extensions[i];
                if (require.modules[x + ext]) return x + ext;
            }
        }
        
        function loadAsDirectorySync (x) {
            x = x.replace(/\/+$/, '');
            var pkgfile = path.normalize(x + '/package.json');
            if (require.modules[pkgfile]) {
                var pkg = require.modules[pkgfile]();
                var b = pkg.browserify;
                if (typeof b === 'object' && b.main) {
                    var m = loadAsFileSync(path.resolve(x, b.main));
                    if (m) return m;
                }
                else if (typeof b === 'string') {
                    var m = loadAsFileSync(path.resolve(x, b));
                    if (m) return m;
                }
                else if (pkg.main) {
                    var m = loadAsFileSync(path.resolve(x, pkg.main));
                    if (m) return m;
                }
            }
            
            return loadAsFileSync(x + '/index');
        }
        
        function loadNodeModulesSync (x, start) {
            var dirs = nodeModulesPathsSync(start);
            for (var i = 0; i < dirs.length; i++) {
                var dir = dirs[i];
                var m = loadAsFileSync(dir + '/' + x);
                if (m) return m;
                var n = loadAsDirectorySync(dir + '/' + x);
                if (n) return n;
            }
            
            var m = loadAsFileSync(x);
            if (m) return m;
        }
        
        function nodeModulesPathsSync (start) {
            var parts;
            if (start === '/') parts = [ '' ];
            else parts = path.normalize(start).split('/');
            
            var dirs = [];
            for (var i = parts.length - 1; i >= 0; i--) {
                if (parts[i] === 'node_modules') continue;
                var dir = parts.slice(0, i + 1).join('/') + '/node_modules';
                dirs.push(dir);
            }
            
            return dirs;
        }
    };
})();

require.alias = function (from, to) {
    var path = require.modules.path();
    var res = null;
    try {
        res = require.resolve(from + '/package.json', '/');
    }
    catch (err) {
        res = require.resolve(from, '/');
    }
    var basedir = path.dirname(res);
    
    var keys = (Object.keys || function (obj) {
        var res = [];
        for (var key in obj) res.push(key);
        return res;
    })(require.modules);
    
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        if (key.slice(0, basedir.length + 1) === basedir + '/') {
            var f = key.slice(basedir.length);
            require.modules[to + f] = require.modules[basedir + f];
        }
        else if (key === basedir) {
            require.modules[to] = require.modules[basedir];
        }
    }
};

(function () {
    var process = {};
    
    require.define = function (filename, fn) {
        if (require.modules.__browserify_process) {
            process = require.modules.__browserify_process();
        }
        
        var dirname = require._core[filename]
            ? ''
            : require.modules.path().dirname(filename)
        ;
        
        var require_ = function (file) {
            var requiredModule = require(file, dirname);
            var cached = require.cache[require.resolve(file, dirname)];

            if (cached && cached.parent === null) {
                cached.parent = module_;
            }

            return requiredModule;
        };
        require_.resolve = function (name) {
            return require.resolve(name, dirname);
        };
        require_.modules = require.modules;
        require_.define = require.define;
        require_.cache = require.cache;
        var module_ = {
            id : filename,
            filename: filename,
            exports : {},
            loaded : false,
            parent: null
        };
        
        require.modules[filename] = function () {
            require.cache[filename] = module_;
            fn.call(
                module_.exports,
                require_,
                module_,
                module_.exports,
                dirname,
                filename,
                process
            );
            module_.loaded = true;
            return module_.exports;
        };
    };
})();


require.define("path",function(require,module,exports,__dirname,__filename,process){function filter (xs, fn) {
    var res = [];
    for (var i = 0; i < xs.length; i++) {
        if (fn(xs[i], i, xs)) res.push(xs[i]);
    }
    return res;
}

// resolves . and .. elements in a path array with directory names there
// must be no slashes, empty elements, or device names (c:\) in the array
// (so also no leading and trailing slashes - it does not distinguish
// relative and absolute paths)
function normalizeArray(parts, allowAboveRoot) {
  // if the path tries to go above the root, `up` ends up > 0
  var up = 0;
  for (var i = parts.length; i >= 0; i--) {
    var last = parts[i];
    if (last == '.') {
      parts.splice(i, 1);
    } else if (last === '..') {
      parts.splice(i, 1);
      up++;
    } else if (up) {
      parts.splice(i, 1);
      up--;
    }
  }

  // if the path is allowed to go above the root, restore leading ..s
  if (allowAboveRoot) {
    for (; up--; up) {
      parts.unshift('..');
    }
  }

  return parts;
}

// Regex to split a filename into [*, dir, basename, ext]
// posix version
var splitPathRe = /^(.+\/(?!$)|\/)?((?:.+?)?(\.[^.]*)?)$/;

// path.resolve([from ...], to)
// posix version
exports.resolve = function() {
var resolvedPath = '',
    resolvedAbsolute = false;

for (var i = arguments.length; i >= -1 && !resolvedAbsolute; i--) {
  var path = (i >= 0)
      ? arguments[i]
      : process.cwd();

  // Skip empty and invalid entries
  if (typeof path !== 'string' || !path) {
    continue;
  }

  resolvedPath = path + '/' + resolvedPath;
  resolvedAbsolute = path.charAt(0) === '/';
}

// At this point the path should be resolved to a full absolute path, but
// handle relative paths to be safe (might happen when process.cwd() fails)

// Normalize the path
resolvedPath = normalizeArray(filter(resolvedPath.split('/'), function(p) {
    return !!p;
  }), !resolvedAbsolute).join('/');

  return ((resolvedAbsolute ? '/' : '') + resolvedPath) || '.';
};

// path.normalize(path)
// posix version
exports.normalize = function(path) {
var isAbsolute = path.charAt(0) === '/',
    trailingSlash = path.slice(-1) === '/';

// Normalize the path
path = normalizeArray(filter(path.split('/'), function(p) {
    return !!p;
  }), !isAbsolute).join('/');

  if (!path && !isAbsolute) {
    path = '.';
  }
  if (path && trailingSlash) {
    path += '/';
  }
  
  return (isAbsolute ? '/' : '') + path;
};


// posix version
exports.join = function() {
  var paths = Array.prototype.slice.call(arguments, 0);
  return exports.normalize(filter(paths, function(p, index) {
    return p && typeof p === 'string';
  }).join('/'));
};


exports.dirname = function(path) {
  var dir = splitPathRe.exec(path)[1] || '';
  var isWindows = false;
  if (!dir) {
    // No dirname
    return '.';
  } else if (dir.length === 1 ||
      (isWindows && dir.length <= 3 && dir.charAt(1) === ':')) {
    // It is just a slash or a drive letter with a slash
    return dir;
  } else {
    // It is a full dirname, strip trailing slash
    return dir.substring(0, dir.length - 1);
  }
};


exports.basename = function(path, ext) {
  var f = splitPathRe.exec(path)[2] || '';
  // TODO: make this comparison case-insensitive on windows?
  if (ext && f.substr(-1 * ext.length) === ext) {
    f = f.substr(0, f.length - ext.length);
  }
  return f;
};


exports.extname = function(path) {
  return splitPathRe.exec(path)[3] || '';
};

});

require.define("__browserify_process",function(require,module,exports,__dirname,__filename,process){var process = module.exports = {};

process.nextTick = (function () {
    var canSetImmediate = typeof window !== 'undefined'
        && window.setImmediate;
    var canPost = typeof window !== 'undefined'
        && window.postMessage && window.addEventListener
    ;

    if (canSetImmediate) {
        return window.setImmediate;
    }

    if (canPost) {
        var queue = [];
        window.addEventListener('message', function (ev) {
            if (ev.source === window && ev.data === 'browserify-tick') {
                ev.stopPropagation();
                if (queue.length > 0) {
                    var fn = queue.shift();
                    fn();
                }
            }
        }, true);

        return function nextTick(fn) {
            queue.push(fn);
            window.postMessage('browserify-tick', '*');
        };
    }

    return function nextTick(fn) {
        setTimeout(fn, 0);
    };
})();

process.title = 'browser';
process.browser = true;
process.env = {};
process.argv = [];

process.binding = function (name) {
    if (name === 'evals') return (require)('vm')
    else throw new Error('No such module. (Possibly not yet loaded)')
};

(function () {
    var cwd = '/';
    var path;
    process.cwd = function () { return cwd };
    process.chdir = function (dir) {
        if (!path) path = require('path');
        cwd = path.resolve(dir, cwd);
    };
})();

});

require.define("/lib/base.js",function(require,module,exports,__dirname,__filename,process){// Generated by CoffeeScript 1.6.3
(function() {
  var exports;

  exports = module.exports;

  exports.d = function(obj) {
    return obj.constructor.name;
  };

  exports.log = function(str) {
    return console.log(str);
  };

}).call(this);

/*
//@ sourceMappingURL=base.map
*/

});

require.define("/lib/proto.js",function(require,module,exports,__dirname,__filename,process){// Generated by CoffeeScript 1.6.3
(function() {
  var Server, VarStorage, caresAbout, cmds, error_bad_master, error_bad_message, error_bad_peer_request, error_bad_storage_mode, error_duplicate_peer_name, error_private_variable, error_variable_not_array, error_variable_not_object, exports, keysForPrefix, prefixes, renameVars, setCmds, storageModes, storage_memory, storage_peer, storage_permanent, storage_transient, warning_no_storage, warning_peer_request, _,
    __indexOf = [].indexOf || function(item) { for (var i = 0, l = this.length; i < l; i++) { if (i in this && this[i] === item) return i; } return -1; },
    __slice = [].slice;

  require('source-map-support').install();

  exports = module.exports = require('./base');

  require('./transport');

  _ = require('./lodash.min');

  cmds = ['response', 'value', 'set', 'put', 'splice', 'removeFirst', 'removeAll', 'removeTree'];

  exports.setCmds = setCmds = ['set', 'put', 'splice', 'removeFirst', 'removeAll', 'removeTree'];

  warning_no_storage = 'warning_no_storage';

  warning_peer_request = 'warning_peer_request';

  error_bad_message = 'error_bad_message';

  error_bad_storage_mode = 'error_bad_storage_mode';

  error_variable_not_object = 'error_variable_not_object';

  error_variable_not_array = 'error_variable_not_array';

  error_duplicate_peer_name = 'error_duplicate_peer_name';

  error_private_variable = 'error_private_variable';

  error_bad_master = 'error_bad_master';

  error_bad_peer_request = 'error_bad_peer_request';

  storage_memory = 'memory';

  storage_transient = 'transient';

  storage_permanent = 'permanent';

  storage_peer = 'peer';

  storageModes = [storage_transient, storage_memory, storage_permanent, storage_peer];

  exports.Server = Server = (function() {
    Server.prototype.verbose = function() {};

    Server.prototype.newKeys = false;

    Server.prototype.anonymousPeerCount = 0;

    function Server() {
      console.log("NEW XUS SERVER");
      this.connections = [];
      this.peers = {};
      this.varStorage = new VarStorage(this);
      this.storageModes = {};
      this.linksToPeers = {};
      this.changedLinks = null;
      this.pendingRequests = {};
      this.pendingRequestNum = 0;
    }

    Server.prototype.createPeer = function(peerFactory) {
      return exports.createDirectPeer(this, peerFactory);
    };

    Server.prototype.newPeer = function() {
      return this.createPeer(function(con) {
        return new xus.Peer(con);
      });
    };

    Server.prototype.processBatch = function(con, batch, nolinks) {
      var c, msg, _i, _j, _len, _len1, _ref, _results;
      while (batch.length) {
        this.nextBatch = [];
        for (_i = 0, _len = batch.length; _i < _len; _i++) {
          msg = batch[_i];
          this.verbose("RECEIVED " + (JSON.stringify(msg)));
          this.processMsg(con, msg, msg, nolinks);
        }
        nolinks = true;
        this.varStorage.sortKeys();
        if (this.newListens) {
          this.setListens(con);
          this.newListens = false;
        }
        if (this.newConLinks) {
          this.setLinks(con);
          this.newConLinks = false;
        }
        if (this.changedLinks) {
          this.processLinks(con, this.changedLinks);
          this.changedLinks = null;
        }
        batch = this.nextBatch;
      }
      _ref = this.connections;
      _results = [];
      for (_j = 0, _len1 = _ref.length; _j < _len1; _j++) {
        c = _ref[_j];
        _results.push(c != null ? c.send() : void 0);
      }
      return _results;
    };

    Server.prototype.processMsg = function(con, _arg, msg, noLinks) {
      var isMyPeerKey, key, name, tmpMsg, x, x1, x2,
        _this = this;
      name = _arg[0];
      if (con.isConnected()) {
        if (__indexOf.call(cmds, name) >= 0) {
          if (name === 'response') {
            x1 = msg[0], x2 = msg[1], tmpMsg = msg[2];
          } else {
            tmpMsg = msg;
          }
          x = tmpMsg[0], key = tmpMsg[1];
          if (typeof key === 'string') {
            key = tmpMsg[1] = key.replace(new RegExp('^this/'), "" + con.peerPath + "/");
          }
          isMyPeerKey = key.match("^" + con.peerPath + "/");
          if (!isMyPeerKey && !noLinks && key.match("^peer/") && !key.match("^.*/public(/|$)")) {
            return this.primDisconnect(con, error_private_variable, "Error, " + con.name + " (key = " + key + ", peerPath = " + con.peerPath + ", match = " + (key.match("^" + con.peerPath)) + ") attempted to change another peer's private variable: '" + key + "' in message: " + (JSON.stringify(msg)));
          } else {
            if (isMyPeerKey) {
              switch (key) {
                case con.listenPath:
                  this.newListens = true;
                  break;
                case !noLinks && con.linksPath:
                  this.verbose("Setting links: " + msg);
                  this.newConLinks = true;
              }
            }
            if (!noLinks && this.linksToPeers[key]) {
              if (!this.changedLinks) {
                this.changedLinks = {};
              }
              this.changedLinks[key] = true;
            }
            if (name !== 'response' && this.shouldDelegate(con, key)) {
              return this.delegate(con, msg);
            } else {
              this.verbose("EXECUTING: " + (JSON.stringify(msg)));
              return this[name](con, msg, function() {
                var c, _i, _len, _ref;
                if (__indexOf.call(setCmds, name) >= 0) {
                  _this.verbose("CMD: " + (JSON.stringify(msg)) + ", VALUE: " + (JSON.stringify(_this.varStorage.values[key])));
                  if (key === con.namePath) {
                    _this.name(con, msg[2]);
                  } else if (key === con.masterPath) {
                    _this.setMaster(con, msg[2]);
                  }
                  _ref = _this.relevantConnections(prefixes(key));
                  for (_i = 0, _len = _ref.length; _i < _len; _i++) {
                    c = _ref[_i];
                    c.addCmd(msg);
                  }
                  if (_this.varStorage.keyInfo[key] === storage_permanent) {
                    return _this.store(con, key, value);
                  }
                }
              });
            }
          }
        } else {
          return this.primDisconnect(con, error_bad_message, "Unknown command, '" + name + "' in message: " + (JSON.stringify(msg)));
        }
      } else if (noLinks) {
        x = msg[0], key = msg[1];
        if (!key.match(new RegExp("^this|^peer/" + con.peerPath + "/"))) {
          return this[name](con, msg, function() {
            var c, _i, _len, _ref, _results;
            _this.verbose("EXECUTED: " + msg);
            _ref = _this.relevantConnections(prefixes(key));
            _results = [];
            for (_i = 0, _len = _ref.length; _i < _len; _i++) {
              c = _ref[_i];
              _results.push(c.addCmd(msg));
            }
            return _results;
          });
        }
      }
    };

    Server.prototype.shouldDelegate = function(con, key) {
      var match;
      if (this.isPeerVar(key)) {
        match = key.match(/^peer\/([^/]+)\//);
        return this.peers[match[1]] !== con;
      } else {
        return false;
      }
    };

    Server.prototype.isPeerVar = function(key) {
      var _this = this;
      return _.any(prefixes(key), function(k) {
        return _this.varStorage.keyInfo[k] === storage_peer;
      });
    };

    Server.prototype.relevantConnections = function(keyPrefixes) {
      return _.filter(this.connections, function(c) {
        return caresAbout(c, keyPrefixes);
      });
    };

    Server.prototype.setConName = function(con, name) {
      con.name = name;
      con.peerPath = "peer/" + name;
      con.namePath = "" + con.peerPath + "/name";
      con.listenPath = "" + con.peerPath + "/listen";
      con.linksPath = "" + con.peerPath + "/links";
      con.masterPath = "" + con.peerPath + "/master";
      con.requests = {};
      this.peers[name] = con;
      return this.varStorage.setKey(con.namePath, name);
    };

    Server.prototype.addConnection = function(con) {
      this.verbose("Xus add connection");
      this.setConName(con, "@anonymous-" + (this.anonymousPeerCount++));
      con.listening = {};
      con.links = {};
      this.connections.push(con);
      this.varStorage.setKey(con.listenPath, []);
      con.date = new Date().getTime();
      con.addCmd(['set', 'this/name', con.name, con.date]);
      return con.send();
    };

    Server.prototype.renamePeerKeys = function(con, oldName, newName) {
      var l, newCL, newPrefix, newVL, oldPrefixPat;
      this.varStorage.keys = renameVars(this.varStorage.keys, this.varStorage.values, this.varStorage.handlers, oldName, newName)[0];
      newCL = {};
      newVL = [];
      newPrefix = "peer/" + newName;
      oldPrefixPat = new RegExp("^peer/" + oldName + "(?=/|$)");
      for (l in con.listening) {
        l = l.replace(oldPrefixPat, newPrefix);
        newCL[l] = true;
        newVL.push(l);
      }
      con.listening = newCL;
      newVL.sort();
      return this.varStorage.setKey("" + newPrefix + "/listen", newVL);
    };

    Server.prototype.disconnect = function(con, errorType, msg) {
      this.primDisconnect(con, errorType, msg);
      if (this.nextBatch) {
        return this.processBatch(con, this.nextBatch, true);
      }
    };

    Server.prototype.primDisconnect = function(con, errorType, msg) {
      var batch, idx, key, num, peerKey, peerKeys, _i, _j, _len, _len1, _ref;
      idx = this.connections.indexOf(con);
      batch = [];
      if (idx > -1) {
        this.varStorage.setKey(con.linksPath, []);
        batch = this.setLinks(con);
        peerKey = con.peerPath;
        peerKeys = this.varStorage.keysForPrefix(peerKey);
        if (con.name) {
          delete this.peers[con.name];
        }
        for (_i = 0, _len = peerKeys.length; _i < _len; _i++) {
          key = peerKeys[_i];
          this.varStorage.removeKey(key);
        }
        this.connections.splice(idx, 1);
        if (msg) {
          this.error(con, errorType, msg);
        }
        con.send();
        con.close();
        _ref = con.requests;
        for (_j = 0, _len1 = _ref.length; _j < _len1; _j++) {
          num = _ref[_j];
          delete this.pendingRequests[num];
        }
        if (con === this.master) {
          this.exit();
        }
      }
      return false;
    };

    Server.prototype.exit = function() {
      return console.log("No custom exit function");
    };

    Server.prototype.setListens = function(con) {
      var conPath, finalListen, old, path, thisPath, _i, _len, _ref;
      thisPath = new RegExp("^this/");
      conPath = "" + con.peerPath + "/";
      old = con.listening;
      con.listening = {};
      finalListen = [];
      _ref = this.varStorage.values[con.listenPath];
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        path = _ref[_i];
        if (path.match("^peer/") && !path.match("^peer/[^/]+/public") && !path.match("^" + con.peerPath)) {
          this.primDisconnect(con, error_private_variable, "Error, " + con.name + " attempted to listen to a peer's private variables in message: " + (JSON.stringify(msg)));
          return;
        }
        path = path.replace(thisPath, conPath);
        finalListen.push(path);
        con.listening[path] = true;
        if (_.all(prefixes(path), (function(p) {
          return !old[p];
        }))) {
          this.sendTree(con, path, ['value', path, null, true]);
        }
        old[path] = true;
      }
      return this.varStorage.setKey(con.listenPath, finalListen);
    };

    Server.prototype.setLinks = function(con) {
      var l, old, _i, _len, _ref, _results;
      this.verbose("PRIM SET LINKS, LINKS PATH: " + con.linksPath + ", NEW " + (JSON.stringify(this.varStorage.values[con.linksPath])) + ", OLD: " + (JSON.stringify(con.links)));
      old = {};
      for (l in con.links) {
        old[l] = true;
      }
      _ref = this.varStorage.values[con.linksPath];
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        l = _ref[_i];
        if (!old[l]) {
          this.addLink(con, l);
        } else {
          delete old[l];
        }
      }
      _results = [];
      for (l in old) {
        _results.push(this.removeLink(con, l));
      }
      return _results;
    };

    Server.prototype.processLinks = function(con, changed) {
      var l, link, old, p, _i, _len, _ref, _results;
      _results = [];
      for (link in changed) {
        old = {};
        for (l in this.linksToPeers[link]) {
          old[l] = true;
        }
        _ref = this.varStorage.values[link];
        for (_i = 0, _len = _ref.length; _i < _len; _i++) {
          p = _ref[_i];
          if (!old[p]) {
            this.addLink(this.peers[p], link);
          } else {
            delete old[p];
          }
        }
        _results.push((function() {
          var _results1;
          _results1 = [];
          for (p in old) {
            _results1.push(this.removeLink(this.peers[p], link));
          }
          return _results1;
        }).call(this));
      }
      return _results;
    };

    Server.prototype.addLink = function(con, link) {
      this.verbose("ADDING LINK: " + (JSON.stringify(link)));
      if (!this.linksToPeers[link]) {
        this.linksToPeers[link] = {};
      }
      this.linksToPeers[link][con.name] = con.links[link] = true;
      this.nextBatch.push(['splice', link, -1, 0, con.name]);
      return this.nextBatch.push(['splice', "peer/" + con.name + "/links", -1, 0, link]);
    };

    Server.prototype.removeLink = function(con, link) {
      var _ref;
      this.verbose("REMOVING LINK: " + (JSON.stringify(link)));
      delete con.links[link];
      if ((_ref = this.linksToPeers[link]) != null) {
        delete _ref[con.name];
      }
      if (this.linksToPeers[link] && !this.linksToPeers[link].length) {
        delete this.linksToPeers[link];
      }
      this.nextBatch.push(['removeAll', link, con.name]);
      return this.nextBatch.push(['removeAll', "peer/" + con.name + "/links", link]);
    };

    Server.prototype.error = function(con, errorType, msg) {
      con.addCmd(['error', errorType, msg]);
      return false;
    };

    Server.prototype.sendTree = function(con, path, cmd) {
      return this.handleStorageCommand(con, cmd, function() {
        return con.addCmd(cmd);
      });
    };

    Server.prototype.delegate = function(con, cmd) {
      var key, match, num, peer, x;
      x = cmd[0], key = cmd[1];
      if (match = key.match(/^peer\/([^/]+)\//)) {
        this.verbose("DELEGATING: " + (JSON.stringify(cmd)));
        peer = this.peers[match[1]];
        num = this.pendingRequestNum++;
        peer.requests[num] = true;
        this.pendingRequests[num] = [peer, con];
        return peer.addCmd(['request', con.name, num, cmd]);
      } else {
        return this.error(con, error_bad_peer_request, "Bad request: " + cmd);
      }
    };

    Server.prototype.get = function(key) {
      return this.varStorage.values[key];
    };

    Server.prototype.name = function(con, name) {
      if (name == null) {
        return this.primDisconnect(con, error_bad_message, "No name given in name message");
      } else if (this.peers[name]) {
        return this.primDisconnect(con, error_duplicate_peer_name, "Duplicate peer name: " + name);
      } else {
        delete this.peers[con.name];
        this.renamePeerKeys(con, con.name, name);
        this.setConName(con, name);
        return con.addCmd(['set', 'this/name', name]);
      }
    };

    Server.prototype.setMaster = function(con, value) {
      if ((this.master != null) && this.master !== con) {
        return this.primDisconnect(con, error_bad_master, "Xus cannot serve two masters");
      } else {
        this.master = value ? con : null;
        return con.addCmd(['set', 'this/master', value]);
      }
    };

    Server.prototype.value = function(con, cmd, cont) {
      var key, x;
      x = cmd[0], key = cmd[1];
      if (this.isPeerVar(key)) {
        return this.delegate(con, [cmd], cont);
      } else {
        return this.handleStorageCommand(con, cmd, function() {
          con.addCmd(cmd);
          return cont();
        });
      }
    };

    Server.prototype.set = function(con, cmd, cont) {
      var key, oldInfo, storageMode, value, x;
      x = cmd[0], key = cmd[1], value = cmd[2], storageMode = cmd[3];
      if (storageMode && storageModes.indexOf(storageMode) === -1) {
        return this.error(con, error_bad_storage_mode, "" + storageMode + " is not a valid storage mode");
      } else if (this.varStorage.values[key] === value) {
        return false;
      } else {
        if (storageMode && storageMode !== this.varStorage.keyInfo[key] && this.varStorage.keyInfo[key] === storage_permanent) {
          this.remove(con, key);
        }
        oldInfo = this.varStorage.keyInfo[key];
        this.varStorage.keyInfo[key] = storageMode = storageMode || this.varStorage.keyInfo[key] || storage_memory;
        if (storageMode !== storage_transient) {
          if (!oldInfo) {
            this.varStorage.keys.push(key);
            this.varStorage.newKeys = this.newKeys = true;
          }
          return this.handleStorageCommand(con, cmd, function() {
            cmd[2] = value;
            return cont();
          });
        } else {
          return cont();
        }
      }
    };

    Server.prototype.put = function(con, cmd, cont) {
      return this.handleStorageCommand(con, cmd, cont);
    };

    Server.prototype.splice = function(con, cmd, cont) {
      return this.handleStorageCommand(con, cmd, cont);
    };

    Server.prototype.removeFirst = function(con, cmd, cont) {
      var key, value, x;
      x = cmd[0], key = cmd[1], value = cmd[2];
      if (!this.varStorage.canRemove(key)) {
        return this.primDisconnect(con, error_variable_not_array, "Can't insert into " + key + " because it does not support splice and indexOf");
      } else {
        return this.handleStorageCommand(con, cmd, cont);
      }
    };

    Server.prototype.removeAll = function(con, cmd, cont) {
      return this.handleStorageCommand(con, cmd, cont);
    };

    Server.prototype.removeTree = function(con, cmd, cont) {
      return this.handleStorageCommand(con, cmd, cont);
    };

    Server.prototype.response = function(con, rcmd, cont) {
      var arg, c, cmd, cmdName, id, key, peer, receiver, x, _i, _len, _ref, _ref1;
      x = rcmd[0], id = rcmd[1], cmd = rcmd[2];
      _ref = this.pendingRequests[id], peer = _ref[0], receiver = _ref[1];
      delete this.pendingRequests[id];
      if (peer !== con) {
        return this.primDisconnect(peer, error_bad_peer_request, "Attempt to responsd to an invalid request");
      } else {
        delete peer.requests[id];
        if (cmd != null) {
          cmdName = cmd[0], key = cmd[1], arg = cmd[2];
          if (cmdName === 'error' && key === error_bad_peer_request) {
            this.primDisconnect(receiver, key, arg);
          } else if (cmdName === 'error' || cmdName === 'value') {
            receiver.addCmd(cmd);
          } else {
            _ref1 = this.relevantConnections(prefixes(key));
            for (_i = 0, _len = _ref1.length; _i < _len; _i++) {
              c = _ref1[_i];
              c.addCmd(msg);
            }
          }
        }
        return cont();
      }
    };

    Server.prototype.handleStorageCommand = function(con, cmd, cont) {
      var _this = this;
      return this.varStorage.handle(cmd, (function(type, msg) {
        return _this.primDisconnect(con, type, msg);
      }), cont);
    };

    return Server;

  })();

  exports.VarStorage = VarStorage = (function() {
    function VarStorage(owner) {
      this.owner = owner;
      this.keys = [];
      this.values = {};
      this.handlers = {};
      this.keyInfo = {};
      this.newKeys = false;
    }

    VarStorage.prototype.toString = function() {
      return "A VarStorage";
    };

    VarStorage.prototype.verbose = function() {
      var args, _ref;
      args = 1 <= arguments.length ? __slice.call(arguments, 0) : [];
      return (_ref = this.owner).verbose.apply(_ref, args);
    };

    VarStorage.prototype.handle = function(cmd, errBlock, cont) {
      var args, key, name;
      name = cmd[0], key = cmd[1], args = 3 <= cmd.length ? __slice.call(cmd, 2) : [];
      return this.handlerFor(key)[name](cmd, errBlock, cont);
    };

    VarStorage.prototype.handlerFor = function(key) {
      var handler, k,
        _this = this;
      k = _.find(prefixes(key), function(p) {
        return _this.handlers[p];
      });
      handler = k ? this.handlers[k] : this;
      return handler;
    };

    VarStorage.prototype.addKey = function(key, info) {
      if (!this.keyInfo[key]) {
        this.newKeys = true;
        this.keyInfo[key] = info;
        this.keys.push(key);
      }
      return info;
    };

    VarStorage.prototype.sortKeys = function() {
      if (this.newKeys) {
        this.keys.sort();
        return this.newKeys = false;
      }
    };

    VarStorage.prototype.setKey = function(key, value, info) {
      var obj;
      if (typeof value === 'function') {
        obj = this.addHandler(key, {
          put: function(_arg, errBlock, cont) {
            var args, err, result, x;
            x = _arg[0], args = 2 <= _arg.length ? __slice.call(_arg, 1) : [];
            try {
              result = value.apply(null, args);
            } catch (_error) {
              err = _error;
              return errBlock(error_bad_peer_request, "Error in computed value: " + (err.stack ? err.stack.join('\n') : err));
            }
            return cont(result);
          }
        });
        obj.set = obj.get = obj.put;
      } else {
        this.values[key] = value;
      }
      this.addKey(key, info || storage_memory);
      return value;
    };

    VarStorage.prototype.removeKey = function(key) {
      var idx;
      delete this.keyInfo[key];
      delete this.values[key];
      this.sortKeys();
      idx = _.sortedIndex(this.keys, key);
      if (idx > -1) {
        return this.keys.splice(idx, 1);
      }
    };

    VarStorage.prototype.isObject = function(key) {
      return typeof this.values[key] === 'object';
    };

    VarStorage.prototype.canSplice = function(key) {
      return !this.values[key] || ((this.values[key].splice != null) && (this.values[key].length != null));
    };

    VarStorage.prototype.canRemove = function(key) {
      return canSplice(key) && (this.values[key].indexOf != null);
    };

    VarStorage.prototype.contains = function(key) {
      return this.values[key] != null;
    };

    VarStorage.prototype.keysForPrefix = function(pref) {
      return keysForPrefix(this.keys, this.values, pref);
    };

    VarStorage.prototype.addHandler = function(path, obj) {
      obj.__proto__ = this;
      obj.toString = function() {
        return "A Handler for " + path;
      };
      this.handlers[path] = obj;
      this.addKey(path, 'handler');
      return obj;
    };

    VarStorage.prototype.value = function(cmd, errBlock, cont) {
      var blk, cookie, counter, key, keys, path, tree, x, _i, _len;
      x = cmd[0], path = cmd[1], cookie = cmd[2], tree = cmd[3];
      if (tree) {
        console.log("KEYS: " + this.keys);
        keys = this.keysForPrefix(path);
        console.log("GETTING VALUES FOR PATH: " + path + " KEYS: " + (JSON.stringify(keys)) + ", ALL KEYS: " + this.keys);
        counter = keys.length;
        blk = function() {
          var args;
          args = 1 <= arguments.length ? __slice.call(arguments, 0) : [];
          counter = 0;
          return errBlock.apply(null, args);
        };
        if (counter) {
          for (_i = 0, _len = keys.length; _i < _len; _i++) {
            key = keys[_i];
            this.handle(['get', key], blk, function(v) {
              if (v) {
                cmd.push(key, v);
              }
              if (--counter === 0) {
                return cont(cmd);
              }
            });
            if (counter < 1) {
              return;
            }
          }
        } else {
          cont(cmd);
        }
      } else {
        this.handle(['get', path], errBlock, function(v) {
          if (v) {
            cmd.push(path, v);
          }
          return cont(cmd);
        });
      }
      return cmd;
    };

    VarStorage.prototype.get = function(_arg, errBlock, cont) {
      var key, x;
      x = _arg[0], key = _arg[1];
      return cont(this.values[key]);
    };

    VarStorage.prototype.set = function(cmd, errBlock, cont) {
      var info, key, oldInfo, storageMode, value, x;
      x = cmd[0], key = cmd[1], value = cmd[2], info = cmd[3];
      if (storageMode && storageModes.indexOf(storageMode) === -1) {
        return errBlock(error_bad_storage_mode, "" + storageMode + " is not a valid storage mode");
      } else {
        oldInfo = this.keyInfo[key];
        storageMode = storageMode || this.keyInfo[key] || storage_memory;
        cmd[2] = value;
        if (storageMode !== storage_transient) {
          return cont(this.setKey(key, value, info));
        }
      }
    };

    VarStorage.prototype.put = function(_arg, errBlock, cont) {
      var index, key, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2], index = _arg[3];
      if (!this.values[key]) {
        this.values[key] = {};
      }
      if (typeof this.values[key] !== 'object' || this.values[key] instanceof Array) {
        return errBlock(error_variable_not_object("" + key + " is not an object"));
      } else {
        if (value === null) {
          delete this.values[key][index];
        } else {
          this.values[key][index] = value;
        }
        if (_.isEmpty(this.values[key])) {
          this.removeKey(key);
        }
        return cont(value);
      }
    };

    VarStorage.prototype.splice = function(_arg, errBlock, cont) {
      var args, key, x, _ref;
      x = _arg[0], key = _arg[1], args = 3 <= _arg.length ? __slice.call(_arg, 2) : [];
      this.verbose("SPLICING: " + (JSON.stringify([x, key].concat(__slice.call(args)))));
      if (!this.values[key]) {
        this.values[key] = [];
      }
      if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
        return errBlock(error_variable_not_array, "" + key + " is not an array");
      } else {
        if (args[0] < 0) {
          args[0] = this.values[key].length + args[0] + 1;
        }
        (_ref = this.values[key]).splice.apply(_ref, args);
        return cont(this.values[key]);
      }
    };

    VarStorage.prototype.removeFirst = function(_arg, errBlock, cont) {
      var idx, key, val, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2];
      if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
        return errBlock(error_variable_not_array, "" + key + " is not an array");
      } else {
        val = this.values[key];
        idx = val.indexOf(value);
        if (idx > -1) {
          val.splice(idx, 1);
        }
        return cont(val);
      }
    };

    VarStorage.prototype.removeAll = function(_arg, errBlock, cont) {
      var idx, key, val, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2];
      if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
        return errBlock(error_variable_not_array, "" + key + " is not an array");
      } else {
        val = this.values[key];
        while ((idx = val.indexOf(value)) > -1) {
          val.splice(idx, 1);
        }
        return cont(val);
      }
    };

    VarStorage.prototype.removeTree = function(_arg, errBlock, cont) {
      var key, x, _i, _len, _ref, _results;
      x = _arg[0], key = _arg[1];
      _ref = this.keysForPrefix(key);
      _results = [];
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        key = _ref[_i];
        _results.push(this.removeKey(key));
      }
      return _results;
    };

    return VarStorage;

  })();

  exports.renameVars = renameVars = function(keys, values, handlers, oldName, newName) {
    var k, key, newKey, newPrefix, oldPrefix, oldPrefixPat, trans, _i, _len, _ref;
    oldPrefix = "peer/" + oldName;
    newPrefix = "peer/" + newName;
    oldPrefixPat = new RegExp("^" + oldPrefix + "(?=/|$)");
    trans = {};
    _ref = keysForPrefix(keys, values, oldPrefix);
    for (_i = 0, _len = _ref.length; _i < _len; _i++) {
      key = _ref[_i];
      newKey = key.replace(oldPrefixPat, newPrefix);
      values[newKey] = values[key];
      handlers[newKey] = handlers[key];
      trans[key] = newKey;
      delete values[key];
      delete handlers[key];
    }
    keys = (function() {
      var _results;
      _results = [];
      for (k in values) {
        _results.push(k);
      }
      return _results;
    })();
    keys.sort();
    return [keys, trans];
  };

  keysForPrefix = function(keys, values, prefix) {
    var idx, initialPattern, prefixPattern, result, _i, _ref, _ref1, _results;
    initialPattern = "^" + prefix + "(/|$)";
    result = [];
    idx = _.find((function() {
      _results = [];
      for (var _i = 0, _ref = keys.length; 0 <= _ref ? _i < _ref : _i > _ref; 0 <= _ref ? _i++ : _i--){ _results.push(_i); }
      return _results;
    }).apply(this), function(i) {
      return keys[i].match(initialPattern);
    });
    if (idx > -1) {
      console.log("FOUND KEY: " + keys[idx] + " AT INDEX: " + idx);
      prefixPattern = initialPattern;
      idx--;
      while ((_ref1 = keys[++idx]) != null ? _ref1.match(prefixPattern) : void 0) {
        if (values[keys[idx]] != null) {
          result.push(keys[idx]);
        }
      }
    } else {
      console.log("NO KEYS FOR PREFIX: " + prefix + ", KEYS: " + keys);
    }
    return result;
  };

  caresAbout = function(con, keyPrefixes) {
    return _.any(keyPrefixes, function(p) {
      return con.listening[p];
    });
  };

  exports.prefixes = prefixes = function(key) {
    var result, splitKey;
    result = [];
    splitKey = _.without(key.split('/'), '');
    while (splitKey.length) {
      result.push(splitKey.join('/'));
      splitKey.pop();
    }
    return result;
  };

}).call(this);

/*
//@ sourceMappingURL=proto.map
*/

});

require.define("/node_modules/source-map-support/package.json",function(require,module,exports,__dirname,__filename,process){module.exports = {"main":"./source-map-support.js"}
});

require.define("/node_modules/source-map-support/source-map-support.js",function(require,module,exports,__dirname,__filename,process){var SourceMapConsumer = require('source-map').SourceMapConsumer;
var path = require('path');
var fs = require('fs');

exports.mapSourcePosition = mapSourcePosition = function(cache, position) {
  var sourceMap = cache[position.source];
  if (!sourceMap && fs.existsSync(position.source)) {
    // Get the URL of the source map
    var fileData = fs.readFileSync(position.source, 'utf8');
    var match = /\/\/[#@]\s*sourceMappingURL=(.*)\s*$/m.exec(fileData);
    if (!match) return position;
    var sourceMappingURL = match[1];

    // Read the contents of the source map
    var sourceMapData;
    var dataUrlPrefix = "data:application/json;base64,";
    if (sourceMappingURL.slice(0, dataUrlPrefix.length).toLowerCase() == dataUrlPrefix) {
      // Support source map URL as a data url
      sourceMapData = new Buffer(sourceMappingURL.slice(dataUrlPrefix.length), "base64").toString();
    }
    else {
      // Support source map URLs relative to the source URL
      var dir = path.dirname(position.source);
      sourceMappingURL = path.resolve(dir, sourceMappingURL);

      if (fs.existsSync(sourceMappingURL)) {
        sourceMapData = fs.readFileSync(sourceMappingURL, 'utf8');
      }
    }

    if (sourceMapData) {
      sourceMap = {
        url: sourceMappingURL,
        map: new SourceMapConsumer(sourceMapData)
      };
      cache[position.source] = sourceMap;
    }
  }

  // Resolve the source URL relative to the URL of the source map
  if (sourceMap) {
    var originalPosition = sourceMap.map.originalPositionFor(position);

    // Only return the original position if a matching line was found. If no
    // matching line is found then we return position instead, which will cause
    // the stack trace to print the path and line for the compiled file. It is
    // better to give a precise location in the compiled file than a vague
    // location in the original file.
    if (originalPosition.source !== null) {
      originalPosition.source = path.resolve(path.dirname(sourceMap.url), originalPosition.source);
      return originalPosition;
    }
  }

  return position;
}

// Parses code generated by FormatEvalOrigin(), a function inside V8:
// https://code.google.com/p/v8/source/browse/trunk/src/messages.js
function mapEvalOrigin(cache, origin) {
  // Most eval() calls are in this format
  var match = /^eval at ([^(]+) \((.+):(\d+):(\d+)\)$/.exec(origin);
  if (match) {
    var position = mapSourcePosition(cache, {
      source: match[2],
      line: match[3],
      column: match[4]
    });
    return 'eval at ' + match[1] + ' (' + position.source + ':' +
      position.line + ':' + position.column + ')';
  }

  // Parse nested eval() calls using recursion
  match = /^eval at ([^(]+) \((.+)\)$/.exec(origin);
  if (match) {
    return 'eval at ' + match[1] + ' (' + mapEvalOrigin(cache, match[2]) + ')';
  }

  // Make sure we still return useful information if we didn't find anything
  return origin;
}

function wrapCallSite(cache, frame) {
  // Most call sites will return the source file from getFileName(), but code
  // passed to eval() ending in "//# sourceURL=..." will return the source file
  // from getScriptNameOrSourceURL() instead
  var source = frame.getFileName() || frame.getScriptNameOrSourceURL();
  if (source) {
    var position = mapSourcePosition(cache, {
      source: source,
      line: frame.getLineNumber(),
      column: frame.getColumnNumber()
    });
    return {
      __proto__: frame,
      getFileName: function() { return position.source; },
      getLineNumber: function() { return position.line; },
      getColumnNumber: function() { return position.column; },
      getScriptNameOrSourceURL: function() { return position.source; }
    };
  }

  // Code called using eval() needs special handling
  var origin = frame.isEval() && frame.getEvalOrigin();
  if (origin) {
    origin = mapEvalOrigin(cache, origin);
    return {
      __proto__: frame,
      getEvalOrigin: function() { return origin; }
    };
  }

  // If we get here then we were unable to change the source position
  return frame;
}

// This function is part of the V8 stack trace API, for more info see:
// http://code.google.com/p/v8/wiki/JavaScriptStackTraceApi
function prepareStackTrace(error, stack) {
  // Store source maps in a cache so we don't load them more than once when
  // formatting a single stack trace (don't cache them forever though in case
  // the files change on disk and the user wants to see the updated mapping)
  var cache = {};
  return error + stack.map(function(frame) {
    return '\n    at ' + wrapCallSite(cache, frame);
  }).join('');
}

// Mimic node's stack trace printing when an exception escapes the process
function handleUncaughtExceptions(error) {
  if (!error || !error.stack) {
    console.log('Uncaught exception:', error);
    process.exit();
  }
  var match = /\n    at [^(]+ \((.*):(\d+):(\d+)\)/.exec(error.stack);
  if (match) {
    var cache = {};
    var position = mapSourcePosition(cache, {
      source: match[1],
      line: match[2],
      column: match[3]
    });
    if (fs.existsSync(position.source)) {
      var contents = fs.readFileSync(position.source, 'utf8');
      var line = contents.split(/(?:\r\n|\r|\n)/)[position.line - 1];
      if (line) {
        console.log('\n' + position.source + ':' + position.line);
        console.log(line);
        console.log(new Array(+position.column).join(' ') + '^');
      }
    }
  }
  console.log(error.stack);
  process.exit();
}

exports.install = function(options) {
  Error.prepareStackTrace = prepareStackTrace;

  // Configure options
  options = options || {};
  var installHandler = 'handleUncaughtExceptions' in options ?
    options.handleUncaughtExceptions : true;

  // Provide the option to not install the uncaught exception handler. This is
  // to support other uncaught exception handlers (in test frameworks, for
  // example). If this handler is not installed and there are no other uncaught
  // exception handlers, uncaught exceptions will be caught by node's built-in
  // exception handler and the process will still be terminated. However, the
  // generated JavaScript code will be shown above the stack trace instead of
  // the original source code.
  if (installHandler) {
    process.on('uncaughtException', handleUncaughtExceptions);
  }
};

});

require.define("/node_modules/source-map-support/node_modules/source-map/package.json",function(require,module,exports,__dirname,__filename,process){module.exports = {"main":"./lib/source-map.js"}
});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map.js",function(require,module,exports,__dirname,__filename,process){/*
 * Copyright 2009-2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE.txt or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
exports.SourceMapGenerator = require('./source-map/source-map-generator').SourceMapGenerator;
exports.SourceMapConsumer = require('./source-map/source-map-consumer').SourceMapConsumer;
exports.SourceNode = require('./source-map/source-node').SourceNode;

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/source-map-generator.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var base64VLQ = require('./base64-vlq');
  var util = require('./util');
  var ArraySet = require('./array-set').ArraySet;

  /**
   * An instance of the SourceMapGenerator represents a source map which is
   * being built incrementally. To create a new one, you must pass an object
   * with the following properties:
   *
   *   - file: The filename of the generated source.
   *   - sourceRoot: An optional root for all URLs in this source map.
   */
  function SourceMapGenerator(aArgs) {
    this._file = util.getArg(aArgs, 'file');
    this._sourceRoot = util.getArg(aArgs, 'sourceRoot', null);
    this._sources = new ArraySet();
    this._names = new ArraySet();
    this._mappings = [];
    this._sourcesContents = null;
  }

  SourceMapGenerator.prototype._version = 3;

  /**
   * Creates a new SourceMapGenerator based on a SourceMapConsumer
   *
   * @param aSourceMapConsumer The SourceMap.
   */
  SourceMapGenerator.fromSourceMap =
    function SourceMapGenerator_fromSourceMap(aSourceMapConsumer) {
      var sourceRoot = aSourceMapConsumer.sourceRoot;
      var generator = new SourceMapGenerator({
        file: aSourceMapConsumer.file,
        sourceRoot: sourceRoot
      });
      aSourceMapConsumer.eachMapping(function (mapping) {
        var newMapping = {
          generated: {
            line: mapping.generatedLine,
            column: mapping.generatedColumn
          }
        };

        if (mapping.source) {
          newMapping.source = mapping.source;
          if (sourceRoot) {
            newMapping.source = util.relative(sourceRoot, newMapping.source);
          }

          newMapping.original = {
            line: mapping.originalLine,
            column: mapping.originalColumn
          };

          if (mapping.name) {
            newMapping.name = mapping.name;
          }
        }

        generator.addMapping(newMapping);
      });
      aSourceMapConsumer.sources.forEach(function (sourceFile) {
        var content = aSourceMapConsumer.sourceContentFor(sourceFile);
        if (content) {
          generator.setSourceContent(sourceFile, content);
        }
      });
      return generator;
    };

  /**
   * Add a single mapping from original source line and column to the generated
   * source's line and column for this source map being created. The mapping
   * object should have the following properties:
   *
   *   - generated: An object with the generated line and column positions.
   *   - original: An object with the original line and column positions.
   *   - source: The original source file (relative to the sourceRoot).
   *   - name: An optional original token name for this mapping.
   */
  SourceMapGenerator.prototype.addMapping =
    function SourceMapGenerator_addMapping(aArgs) {
      var generated = util.getArg(aArgs, 'generated');
      var original = util.getArg(aArgs, 'original', null);
      var source = util.getArg(aArgs, 'source', null);
      var name = util.getArg(aArgs, 'name', null);

      this._validateMapping(generated, original, source, name);

      if (source && !this._sources.has(source)) {
        this._sources.add(source);
      }

      if (name && !this._names.has(name)) {
        this._names.add(name);
      }

      this._mappings.push({
        generated: generated,
        original: original,
        source: source,
        name: name
      });
    };

  /**
   * Set the source content for a source file.
   */
  SourceMapGenerator.prototype.setSourceContent =
    function SourceMapGenerator_setSourceContent(aSourceFile, aSourceContent) {
      var source = aSourceFile;
      if (this._sourceRoot) {
        source = util.relative(this._sourceRoot, source);
      }

      if (aSourceContent !== null) {
        // Add the source content to the _sourcesContents map.
        // Create a new _sourcesContents map if the property is null.
        if (!this._sourcesContents) {
          this._sourcesContents = {};
        }
        this._sourcesContents[util.toSetString(source)] = aSourceContent;
      } else {
        // Remove the source file from the _sourcesContents map.
        // If the _sourcesContents map is empty, set the property to null.
        delete this._sourcesContents[util.toSetString(source)];
        if (Object.keys(this._sourcesContents).length === 0) {
          this._sourcesContents = null;
        }
      }
    };

  /**
   * Applies the mappings of a sub-source-map for a specific source file to the
   * source map being generated. Each mapping to the supplied source file is
   * rewritten using the supplied source map. Note: The resolution for the
   * resulting mappings is the minimium of this map and the supplied map.
   *
   * @param aSourceMapConsumer The source map to be applied.
   * @param aSourceFile Optional. The filename of the source file.
   *        If omitted, SourceMapConsumer's file property will be used.
   */
  SourceMapGenerator.prototype.applySourceMap =
    function SourceMapGenerator_applySourceMap(aSourceMapConsumer, aSourceFile) {
      // If aSourceFile is omitted, we will use the file property of the SourceMap
      if (!aSourceFile) {
        aSourceFile = aSourceMapConsumer.file;
      }
      var sourceRoot = this._sourceRoot;
      // Make "aSourceFile" relative if an absolute Url is passed.
      if (sourceRoot) {
        aSourceFile = util.relative(sourceRoot, aSourceFile);
      }
      // Applying the SourceMap can add and remove items from the sources and
      // the names array.
      var newSources = new ArraySet();
      var newNames = new ArraySet();

      // Find mappings for the "aSourceFile"
      this._mappings.forEach(function (mapping) {
        if (mapping.source === aSourceFile && mapping.original) {
          // Check if it can be mapped by the source map, then update the mapping.
          var original = aSourceMapConsumer.originalPositionFor({
            line: mapping.original.line,
            column: mapping.original.column
          });
          if (original.source !== null) {
            // Copy mapping
            if (sourceRoot) {
              mapping.source = util.relative(sourceRoot, original.source);
            } else {
              mapping.source = original.source;
            }
            mapping.original.line = original.line;
            mapping.original.column = original.column;
            if (original.name !== null && mapping.name !== null) {
              // Only use the identifier name if it's an identifier
              // in both SourceMaps
              mapping.name = original.name;
            }
          }
        }

        var source = mapping.source;
        if (source && !newSources.has(source)) {
          newSources.add(source);
        }

        var name = mapping.name;
        if (name && !newNames.has(name)) {
          newNames.add(name);
        }

      }, this);
      this._sources = newSources;
      this._names = newNames;

      // Copy sourcesContents of applied map.
      aSourceMapConsumer.sources.forEach(function (sourceFile) {
        var content = aSourceMapConsumer.sourceContentFor(sourceFile);
        if (content) {
          if (sourceRoot) {
            sourceFile = util.relative(sourceRoot, sourceFile);
          }
          this.setSourceContent(sourceFile, content);
        }
      }, this);
    };

  /**
   * A mapping can have one of the three levels of data:
   *
   *   1. Just the generated position.
   *   2. The Generated position, original position, and original source.
   *   3. Generated and original position, original source, as well as a name
   *      token.
   *
   * To maintain consistency, we validate that any new mapping being added falls
   * in to one of these categories.
   */
  SourceMapGenerator.prototype._validateMapping =
    function SourceMapGenerator_validateMapping(aGenerated, aOriginal, aSource,
                                                aName) {
      if (aGenerated && 'line' in aGenerated && 'column' in aGenerated
          && aGenerated.line > 0 && aGenerated.column >= 0
          && !aOriginal && !aSource && !aName) {
        // Case 1.
        return;
      }
      else if (aGenerated && 'line' in aGenerated && 'column' in aGenerated
               && aOriginal && 'line' in aOriginal && 'column' in aOriginal
               && aGenerated.line > 0 && aGenerated.column >= 0
               && aOriginal.line > 0 && aOriginal.column >= 0
               && aSource) {
        // Cases 2 and 3.
        return;
      }
      else {
        throw new Error('Invalid mapping.');
      }
    };

  function cmpLocation(loc1, loc2) {
    var cmp = (loc1 && loc1.line) - (loc2 && loc2.line);
    return cmp ? cmp : (loc1 && loc1.column) - (loc2 && loc2.column);
  }

  function strcmp(str1, str2) {
    str1 = str1 || '';
    str2 = str2 || '';
    return (str1 > str2) - (str1 < str2);
  }

  function cmpMapping(mappingA, mappingB) {
    return cmpLocation(mappingA.generated, mappingB.generated) ||
      cmpLocation(mappingA.original, mappingB.original) ||
      strcmp(mappingA.source, mappingB.source) ||
      strcmp(mappingA.name, mappingB.name);
  }

  /**
   * Serialize the accumulated mappings in to the stream of base 64 VLQs
   * specified by the source map format.
   */
  SourceMapGenerator.prototype._serializeMappings =
    function SourceMapGenerator_serializeMappings() {
      var previousGeneratedColumn = 0;
      var previousGeneratedLine = 1;
      var previousOriginalColumn = 0;
      var previousOriginalLine = 0;
      var previousName = 0;
      var previousSource = 0;
      var result = '';
      var mapping;

      // The mappings must be guaranteed to be in sorted order before we start
      // serializing them or else the generated line numbers (which are defined
      // via the ';' separators) will be all messed up. Note: it might be more
      // performant to maintain the sorting as we insert them, rather than as we
      // serialize them, but the big O is the same either way.
      this._mappings.sort(cmpMapping);

      for (var i = 0, len = this._mappings.length; i < len; i++) {
        mapping = this._mappings[i];

        if (mapping.generated.line !== previousGeneratedLine) {
          previousGeneratedColumn = 0;
          while (mapping.generated.line !== previousGeneratedLine) {
            result += ';';
            previousGeneratedLine++;
          }
        }
        else {
          if (i > 0) {
            if (!cmpMapping(mapping, this._mappings[i - 1])) {
              continue;
            }
            result += ',';
          }
        }

        result += base64VLQ.encode(mapping.generated.column
                                   - previousGeneratedColumn);
        previousGeneratedColumn = mapping.generated.column;

        if (mapping.source && mapping.original) {
          result += base64VLQ.encode(this._sources.indexOf(mapping.source)
                                     - previousSource);
          previousSource = this._sources.indexOf(mapping.source);

          // lines are stored 0-based in SourceMap spec version 3
          result += base64VLQ.encode(mapping.original.line - 1
                                     - previousOriginalLine);
          previousOriginalLine = mapping.original.line - 1;

          result += base64VLQ.encode(mapping.original.column
                                     - previousOriginalColumn);
          previousOriginalColumn = mapping.original.column;

          if (mapping.name) {
            result += base64VLQ.encode(this._names.indexOf(mapping.name)
                                       - previousName);
            previousName = this._names.indexOf(mapping.name);
          }
        }
      }

      return result;
    };

  /**
   * Externalize the source map.
   */
  SourceMapGenerator.prototype.toJSON =
    function SourceMapGenerator_toJSON() {
      var map = {
        version: this._version,
        file: this._file,
        sources: this._sources.toArray(),
        names: this._names.toArray(),
        mappings: this._serializeMappings()
      };
      if (this._sourceRoot) {
        map.sourceRoot = this._sourceRoot;
      }
      if (this._sourcesContents) {
        map.sourcesContent = map.sources.map(function (source) {
          if (map.sourceRoot) {
            source = util.relative(map.sourceRoot, source);
          }
          return Object.prototype.hasOwnProperty.call(
            this._sourcesContents, util.toSetString(source))
            ? this._sourcesContents[util.toSetString(source)]
            : null;
        }, this);
      }
      return map;
    };

  /**
   * Render the source map being generated to a string.
   */
  SourceMapGenerator.prototype.toString =
    function SourceMapGenerator_toString() {
      return JSON.stringify(this);
    };

  exports.SourceMapGenerator = SourceMapGenerator;

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/node_modules/amdefine/package.json",function(require,module,exports,__dirname,__filename,process){module.exports = {"main":"./amdefine.js"}
});

require.define("/node_modules/source-map-support/node_modules/source-map/node_modules/amdefine/amdefine.js",function(require,module,exports,__dirname,__filename,process){/** vim: et:ts=4:sw=4:sts=4
 * @license amdefine 0.0.5 Copyright (c) 2011, The Dojo Foundation All Rights Reserved.
 * Available via the MIT or new BSD license.
 * see: http://github.com/jrburke/amdefine for details
 */

/*jslint node: true */
/*global module, process */
'use strict';

var path = require('path');

/**
 * Creates a define for node.
 * @param {Object} module the "module" object that is defined by Node for the
 * current module.
 * @param {Function} [require]. Node's require function for the current module.
 * It only needs to be passed in Node versions before 0.5, when module.require
 * did not exist.
 * @returns {Function} a define function that is usable for the current node
 * module.
 */
function amdefine(module, require) {
    var defineCache = {},
        loaderCache = {},
        alreadyCalled = false,
        makeRequire, stringRequire;

    /**
     * Trims the . and .. from an array of path segments.
     * It will keep a leading path segment if a .. will become
     * the first path segment, to help with module name lookups,
     * which act like paths, but can be remapped. But the end result,
     * all paths that use this function should look normalized.
     * NOTE: this method MODIFIES the input array.
     * @param {Array} ary the array of path segments.
     */
    function trimDots(ary) {
        var i, part;
        for (i = 0; ary[i]; i+= 1) {
            part = ary[i];
            if (part === '.') {
                ary.splice(i, 1);
                i -= 1;
            } else if (part === '..') {
                if (i === 1 && (ary[2] === '..' || ary[0] === '..')) {
                    //End of the line. Keep at least one non-dot
                    //path segment at the front so it can be mapped
                    //correctly to disk. Otherwise, there is likely
                    //no path mapping for a path starting with '..'.
                    //This can still fail, but catches the most reasonable
                    //uses of ..
                    break;
                } else if (i > 0) {
                    ary.splice(i - 1, 2);
                    i -= 2;
                }
            }
        }
    }

    function normalize(name, baseName) {
        var baseParts;

        //Adjust any relative paths.
        if (name && name.charAt(0) === '.') {
            //If have a base name, try to normalize against it,
            //otherwise, assume it is a top-level require that will
            //be relative to baseUrl in the end.
            if (baseName) {
                baseParts = baseName.split('/');
                baseParts = baseParts.slice(0, baseParts.length - 1);
                baseParts = baseParts.concat(name.split('/'));
                trimDots(baseParts);
                name = baseParts.join('/');
            }
        }

        return name;
    }

    /**
     * Create the normalize() function passed to a loader plugin's
     * normalize method.
     */
    function makeNormalize(relName) {
        return function (name) {
            return normalize(name, relName);
        };
    }

    function makeLoad(id) {
        function load(value) {
            loaderCache[id] = value;
        }

        load.fromText = function (id, text) {
            //This one is difficult because the text can/probably uses
            //define, and any relative paths and requires should be relative
            //to that id was it would be found on disk. But this would require
            //bootstrapping a module/require fairly deeply from node core.
            //Not sure how best to go about that yet.
            throw new Error('amdefine does not implement load.fromText');
        };

        return load;
    }

    makeRequire = function (systemRequire, exports, module, relId) {
        function amdRequire(deps, callback) {
            if (typeof deps === 'string') {
                //Synchronous, single module require('')
                return stringRequire(systemRequire, exports, module, deps, relId);
            } else {
                //Array of dependencies with a callback.

                //Convert the dependencies to modules.
                deps = deps.map(function (depName) {
                    return stringRequire(systemRequire, exports, module, depName, relId);
                });

                //Wait for next tick to call back the require call.
                process.nextTick(function () {
                    callback.apply(null, deps);
                });
            }
        }

        amdRequire.toUrl = function (filePath) {
            if (filePath.indexOf('.') === 0) {
                return normalize(filePath, path.dirname(module.filename));
            } else {
                return filePath;
            }
        };

        return amdRequire;
    };

    //Favor explicit value, passed in if the module wants to support Node 0.4.
    require = require || function req() {
        return module.require.apply(module, arguments);
    };

    function runFactory(id, deps, factory) {
        var r, e, m, result;

        if (id) {
            e = loaderCache[id] = {};
            m = {
                id: id,
                uri: __filename,
                exports: e
            };
            r = makeRequire(require, e, m, id);
        } else {
            //Only support one define call per file
            if (alreadyCalled) {
                throw new Error('amdefine with no module ID cannot be called more than once per file.');
            }
            alreadyCalled = true;

            //Use the real variables from node
            //Use module.exports for exports, since
            //the exports in here is amdefine exports.
            e = module.exports;
            m = module;
            r = makeRequire(require, e, m, module.id);
        }

        //If there are dependencies, they are strings, so need
        //to convert them to dependency values.
        if (deps) {
            deps = deps.map(function (depName) {
                return r(depName);
            });
        }

        //Call the factory with the right dependencies.
        if (typeof factory === 'function') {
            result = factory.apply(module.exports, deps);
        } else {
            result = factory;
        }

        if (result !== undefined) {
            m.exports = result;
            if (id) {
                loaderCache[id] = m.exports;
            }
        }
    }

    stringRequire = function (systemRequire, exports, module, id, relId) {
        //Split the ID by a ! so that
        var index = id.indexOf('!'),
            originalId = id,
            prefix, plugin;

        if (index === -1) {
            id = normalize(id, relId);

            //Straight module lookup. If it is one of the special dependencies,
            //deal with it, otherwise, delegate to node.
            if (id === 'require') {
                return makeRequire(systemRequire, exports, module, relId);
            } else if (id === 'exports') {
                return exports;
            } else if (id === 'module') {
                return module;
            } else if (loaderCache.hasOwnProperty(id)) {
                return loaderCache[id];
            } else if (defineCache[id]) {
                runFactory.apply(null, defineCache[id]);
                return loaderCache[id];
            } else {
                if(systemRequire) {
                    return systemRequire(originalId);
                } else {
                    throw new Error('No module with ID: ' + id);
                }
            }
        } else {
            //There is a plugin in play.
            prefix = id.substring(0, index);
            id = id.substring(index + 1, id.length);

            plugin = stringRequire(systemRequire, exports, module, prefix, relId);

            if (plugin.normalize) {
                id = plugin.normalize(id, makeNormalize(relId));
            } else {
                //Normalize the ID normally.
                id = normalize(id, relId);
            }

            if (loaderCache[id]) {
                return loaderCache[id];
            } else {
                plugin.load(id, makeRequire(systemRequire, exports, module, relId), makeLoad(id), {});

                return loaderCache[id];
            }
        }
    };

    //Create a define function specific to the module asking for amdefine.
    function define(id, deps, factory) {
        if (Array.isArray(id)) {
            factory = deps;
            deps = id;
            id = undefined;
        } else if (typeof id !== 'string') {
            factory = id;
            id = deps = undefined;
        }

        if (deps && !Array.isArray(deps)) {
            factory = deps;
            deps = undefined;
        }

        if (!deps) {
            deps = ['require', 'exports', 'module'];
        }

        //Set up properties for this module. If an ID, then use
        //internal cache. If no ID, then use the external variables
        //for this node module.
        if (id) {
            //Put the module in deep freeze until there is a
            //require call for it.
            defineCache[id] = [id, deps, factory];
        } else {
            runFactory(id, deps, factory);
        }
    }

    //define.require, which has access to all the values in the
    //cache. Useful for AMD modules that all have IDs in the file,
    //but need to finally export a value to node based on one of those
    //IDs.
    define.require = function (id) {
        if (loaderCache[id]) {
            return loaderCache[id];
        }

        if (defineCache[id]) {
            runFactory.apply(null, defineCache[id]);
            return loaderCache[id];
        }
    };

    define.amd = {};

    return define;
}

module.exports = amdefine;

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/base64-vlq.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Based on the Base 64 VLQ implementation in Closure Compiler:
 * https://code.google.com/p/closure-compiler/source/browse/trunk/src/com/google/debugging/sourcemap/Base64VLQ.java
 *
 * Copyright 2011 The Closure Compiler Authors. All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of Google Inc. nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var base64 = require('./base64');

  // A single base 64 digit can contain 6 bits of data. For the base 64 variable
  // length quantities we use in the source map spec, the first bit is the sign,
  // the next four bits are the actual value, and the 6th bit is the
  // continuation bit. The continuation bit tells us whether there are more
  // digits in this value following this digit.
  //
  //   Continuation
  //   |    Sign
  //   |    |
  //   V    V
  //   101011

  var VLQ_BASE_SHIFT = 5;

  // binary: 100000
  var VLQ_BASE = 1 << VLQ_BASE_SHIFT;

  // binary: 011111
  var VLQ_BASE_MASK = VLQ_BASE - 1;

  // binary: 100000
  var VLQ_CONTINUATION_BIT = VLQ_BASE;

  /**
   * Converts from a two-complement value to a value where the sign bit is
   * is placed in the least significant bit.  For example, as decimals:
   *   1 becomes 2 (10 binary), -1 becomes 3 (11 binary)
   *   2 becomes 4 (100 binary), -2 becomes 5 (101 binary)
   */
  function toVLQSigned(aValue) {
    return aValue < 0
      ? ((-aValue) << 1) + 1
      : (aValue << 1) + 0;
  }

  /**
   * Converts to a two-complement value from a value where the sign bit is
   * is placed in the least significant bit.  For example, as decimals:
   *   2 (10 binary) becomes 1, 3 (11 binary) becomes -1
   *   4 (100 binary) becomes 2, 5 (101 binary) becomes -2
   */
  function fromVLQSigned(aValue) {
    var isNegative = (aValue & 1) === 1;
    var shifted = aValue >> 1;
    return isNegative
      ? -shifted
      : shifted;
  }

  /**
   * Returns the base 64 VLQ encoded value.
   */
  exports.encode = function base64VLQ_encode(aValue) {
    var encoded = "";
    var digit;

    var vlq = toVLQSigned(aValue);

    do {
      digit = vlq & VLQ_BASE_MASK;
      vlq >>>= VLQ_BASE_SHIFT;
      if (vlq > 0) {
        // There are still more digits in this value, so we must make sure the
        // continuation bit is marked.
        digit |= VLQ_CONTINUATION_BIT;
      }
      encoded += base64.encode(digit);
    } while (vlq > 0);

    return encoded;
  };

  /**
   * Decodes the next base 64 VLQ value from the given string and returns the
   * value and the rest of the string.
   */
  exports.decode = function base64VLQ_decode(aStr) {
    var i = 0;
    var strLen = aStr.length;
    var result = 0;
    var shift = 0;
    var continuation, digit;

    do {
      if (i >= strLen) {
        throw new Error("Expected more digits in base 64 VLQ value.");
      }
      digit = base64.decode(aStr.charAt(i++));
      continuation = !!(digit & VLQ_CONTINUATION_BIT);
      digit &= VLQ_BASE_MASK;
      result = result + (digit << shift);
      shift += VLQ_BASE_SHIFT;
    } while (continuation);

    return {
      value: fromVLQSigned(result),
      rest: aStr.slice(i)
    };
  };

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/base64.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var charToIntMap = {};
  var intToCharMap = {};

  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    .split('')
    .forEach(function (ch, index) {
      charToIntMap[ch] = index;
      intToCharMap[index] = ch;
    });

  /**
   * Encode an integer in the range of 0 to 63 to a single base 64 digit.
   */
  exports.encode = function base64_encode(aNumber) {
    if (aNumber in intToCharMap) {
      return intToCharMap[aNumber];
    }
    throw new TypeError("Must be between 0 and 63: " + aNumber);
  };

  /**
   * Decode a single base 64 digit to an integer.
   */
  exports.decode = function base64_decode(aChar) {
    if (aChar in charToIntMap) {
      return charToIntMap[aChar];
    }
    throw new TypeError("Not a valid base 64 digit: " + aChar);
  };

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/util.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  /**
   * This is a helper function for getting values from parameter/options
   * objects.
   *
   * @param args The object we are extracting values from
   * @param name The name of the property we are getting.
   * @param defaultValue An optional value to return if the property is missing
   * from the object. If this is not specified and the property is missing, an
   * error will be thrown.
   */
  function getArg(aArgs, aName, aDefaultValue) {
    if (aName in aArgs) {
      return aArgs[aName];
    } else if (arguments.length === 3) {
      return aDefaultValue;
    } else {
      throw new Error('"' + aName + '" is a required argument.');
    }
  }
  exports.getArg = getArg;

  var urlRegexp = /([\w+\-.]+):\/\/((\w+:\w+)@)?([\w.]+)?(:(\d+))?(\S+)?/;

  function urlParse(aUrl) {
    var match = aUrl.match(urlRegexp);
    if (!match) {
      return null;
    }
    return {
      scheme: match[1],
      auth: match[3],
      host: match[4],
      port: match[6],
      path: match[7]
    };
  }
  exports.urlParse = urlParse;

  function urlGenerate(aParsedUrl) {
    var url = aParsedUrl.scheme + "://";
    if (aParsedUrl.auth) {
      url += aParsedUrl.auth + "@"
    }
    if (aParsedUrl.host) {
      url += aParsedUrl.host;
    }
    if (aParsedUrl.port) {
      url += ":" + aParsedUrl.port
    }
    if (aParsedUrl.path) {
      url += aParsedUrl.path;
    }
    return url;
  }
  exports.urlGenerate = urlGenerate;

  function join(aRoot, aPath) {
    var url;

    if (aPath.match(urlRegexp)) {
      return aPath;
    }

    if (aPath.charAt(0) === '/' && (url = urlParse(aRoot))) {
      url.path = aPath;
      return urlGenerate(url);
    }

    return aRoot.replace(/\/$/, '') + '/' + aPath;
  }
  exports.join = join;

  /**
   * Because behavior goes wacky when you set `__proto__` on objects, we
   * have to prefix all the strings in our set with an arbitrary character.
   *
   * See https://github.com/mozilla/source-map/pull/31 and
   * https://github.com/mozilla/source-map/issues/30
   *
   * @param String aStr
   */
  function toSetString(aStr) {
    return '$' + aStr;
  }
  exports.toSetString = toSetString;

  function fromSetString(aStr) {
    return aStr.substr(1);
  }
  exports.fromSetString = fromSetString;

  function relative(aRoot, aPath) {
    aRoot = aRoot.replace(/\/$/, '');

    var url = urlParse(aRoot);
    if (aPath.charAt(0) == "/" && url && url.path == "/") {
      return aPath.slice(1);
    }

    return aPath.indexOf(aRoot + '/') === 0
      ? aPath.substr(aRoot.length + 1)
      : aPath;
  }
  exports.relative = relative;

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/array-set.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var util = require('./util');

  /**
   * A data structure which is a combination of an array and a set. Adding a new
   * member is O(1), testing for membership is O(1), and finding the index of an
   * element is O(1). Removing elements from the set is not supported. Only
   * strings are supported for membership.
   */
  function ArraySet() {
    this._array = [];
    this._set = {};
  }

  /**
   * Static method for creating ArraySet instances from an existing array.
   */
  ArraySet.fromArray = function ArraySet_fromArray(aArray) {
    var set = new ArraySet();
    for (var i = 0, len = aArray.length; i < len; i++) {
      set.add(aArray[i]);
    }
    return set;
  };

  /**
   * Add the given string to this set.
   *
   * @param String aStr
   */
  ArraySet.prototype.add = function ArraySet_add(aStr) {
    if (this.has(aStr)) {
      // Already a member; nothing to do.
      return;
    }
    var idx = this._array.length;
    this._array.push(aStr);
    this._set[util.toSetString(aStr)] = idx;
  };

  /**
   * Is the given string a member of this set?
   *
   * @param String aStr
   */
  ArraySet.prototype.has = function ArraySet_has(aStr) {
    return Object.prototype.hasOwnProperty.call(this._set,
                                                util.toSetString(aStr));
  };

  /**
   * What is the index of the given string in the array?
   *
   * @param String aStr
   */
  ArraySet.prototype.indexOf = function ArraySet_indexOf(aStr) {
    if (this.has(aStr)) {
      return this._set[util.toSetString(aStr)];
    }
    throw new Error('"' + aStr + '" is not in the set.');
  };

  /**
   * What is the element at the given index?
   *
   * @param Number aIdx
   */
  ArraySet.prototype.at = function ArraySet_at(aIdx) {
    if (aIdx >= 0 && aIdx < this._array.length) {
      return this._array[aIdx];
    }
    throw new Error('No element indexed by ' + aIdx);
  };

  /**
   * Returns the array representation of this set (which has the proper indices
   * indicated by indexOf). Note that this is a copy of the internal array used
   * for storing the members so that no one can mess with internal state.
   */
  ArraySet.prototype.toArray = function ArraySet_toArray() {
    return this._array.slice();
  };

  exports.ArraySet = ArraySet;

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/source-map-consumer.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var util = require('./util');
  var binarySearch = require('./binary-search');
  var ArraySet = require('./array-set').ArraySet;
  var base64VLQ = require('./base64-vlq');

  /**
   * A SourceMapConsumer instance represents a parsed source map which we can
   * query for information about the original file positions by giving it a file
   * position in the generated source.
   *
   * The only parameter is the raw source map (either as a JSON string, or
   * already parsed to an object). According to the spec, source maps have the
   * following attributes:
   *
   *   - version: Which version of the source map spec this map is following.
   *   - sources: An array of URLs to the original source files.
   *   - names: An array of identifiers which can be referrenced by individual mappings.
   *   - sourceRoot: Optional. The URL root from which all sources are relative.
   *   - sourcesContent: Optional. An array of contents of the original source files.
   *   - mappings: A string of base64 VLQs which contain the actual mappings.
   *   - file: The generated file this source map is associated with.
   *
   * Here is an example source map, taken from the source map spec[0]:
   *
   *     {
   *       version : 3,
   *       file: "out.js",
   *       sourceRoot : "",
   *       sources: ["foo.js", "bar.js"],
   *       names: ["src", "maps", "are", "fun"],
   *       mappings: "AA,AB;;ABCDE;"
   *     }
   *
   * [0]: https://docs.google.com/document/d/1U1RGAehQwRypUTovF1KRlpiOFze0b-_2gc6fAH0KY0k/edit?pli=1#
   */
  function SourceMapConsumer(aSourceMap) {
    var sourceMap = aSourceMap;
    if (typeof aSourceMap === 'string') {
      sourceMap = JSON.parse(aSourceMap.replace(/^\)\]\}'/, ''));
    }

    var version = util.getArg(sourceMap, 'version');
    var sources = util.getArg(sourceMap, 'sources');
    var names = util.getArg(sourceMap, 'names');
    var sourceRoot = util.getArg(sourceMap, 'sourceRoot', null);
    var sourcesContent = util.getArg(sourceMap, 'sourcesContent', null);
    var mappings = util.getArg(sourceMap, 'mappings');
    var file = util.getArg(sourceMap, 'file');

    if (version !== this._version) {
      throw new Error('Unsupported version: ' + version);
    }

    this._names = ArraySet.fromArray(names);
    this._sources = ArraySet.fromArray(sources);
    this.sourceRoot = sourceRoot;
    this.sourcesContent = sourcesContent;
    this.file = file;

    // `this._generatedMappings` and `this._originalMappings` hold the parsed
    // mapping coordinates from the source map's "mappings" attribute. Each
    // object in the array is of the form
    //
    //     {
    //       generatedLine: The line number in the generated code,
    //       generatedColumn: The column number in the generated code,
    //       source: The path to the original source file that generated this
    //               chunk of code,
    //       originalLine: The line number in the original source that
    //                     corresponds to this chunk of generated code,
    //       originalColumn: The column number in the original source that
    //                       corresponds to this chunk of generated code,
    //       name: The name of the original symbol which generated this chunk of
    //             code.
    //     }
    //
    // All properties except for `generatedLine` and `generatedColumn` can be
    // `null`.
    //
    // `this._generatedMappings` is ordered by the generated positions.
    //
    // `this._originalMappings` is ordered by the original positions.
    this._generatedMappings = [];
    this._originalMappings = [];
    this._parseMappings(mappings, sourceRoot);
  }

  /**
   * The version of the source mapping spec that we are consuming.
   */
  SourceMapConsumer.prototype._version = 3;

  /**
   * The list of original sources.
   */
  Object.defineProperty(SourceMapConsumer.prototype, 'sources', {
    get: function () {
      return this._sources.toArray().map(function (s) {
        return this.sourceRoot ? util.join(this.sourceRoot, s) : s;
      }, this);
    }
  });

  /**
   * Parse the mappings in a string in to a data structure which we can easily
   * query (an ordered list in this._generatedMappings).
   */
  SourceMapConsumer.prototype._parseMappings =
    function SourceMapConsumer_parseMappings(aStr, aSourceRoot) {
      var generatedLine = 1;
      var previousGeneratedColumn = 0;
      var previousOriginalLine = 0;
      var previousOriginalColumn = 0;
      var previousSource = 0;
      var previousName = 0;
      var mappingSeparator = /^[,;]/;
      var str = aStr;
      var mapping;
      var temp;

      while (str.length > 0) {
        if (str.charAt(0) === ';') {
          generatedLine++;
          str = str.slice(1);
          previousGeneratedColumn = 0;
        }
        else if (str.charAt(0) === ',') {
          str = str.slice(1);
        }
        else {
          mapping = {};
          mapping.generatedLine = generatedLine;

          // Generated column.
          temp = base64VLQ.decode(str);
          mapping.generatedColumn = previousGeneratedColumn + temp.value;
          previousGeneratedColumn = mapping.generatedColumn;
          str = temp.rest;

          if (str.length > 0 && !mappingSeparator.test(str.charAt(0))) {
            // Original source.
            temp = base64VLQ.decode(str);
            mapping.source = this._sources.at(previousSource + temp.value);
            previousSource += temp.value;
            str = temp.rest;
            if (str.length === 0 || mappingSeparator.test(str.charAt(0))) {
              throw new Error('Found a source, but no line and column');
            }

            // Original line.
            temp = base64VLQ.decode(str);
            mapping.originalLine = previousOriginalLine + temp.value;
            previousOriginalLine = mapping.originalLine;
            // Lines are stored 0-based
            mapping.originalLine += 1;
            str = temp.rest;
            if (str.length === 0 || mappingSeparator.test(str.charAt(0))) {
              throw new Error('Found a source and line, but no column');
            }

            // Original column.
            temp = base64VLQ.decode(str);
            mapping.originalColumn = previousOriginalColumn + temp.value;
            previousOriginalColumn = mapping.originalColumn;
            str = temp.rest;

            if (str.length > 0 && !mappingSeparator.test(str.charAt(0))) {
              // Original name.
              temp = base64VLQ.decode(str);
              mapping.name = this._names.at(previousName + temp.value);
              previousName += temp.value;
              str = temp.rest;
            }
          }

          this._generatedMappings.push(mapping);
          if (typeof mapping.originalLine === 'number') {
            this._originalMappings.push(mapping);
          }
        }
      }

      this._originalMappings.sort(this._compareOriginalPositions);
    };

  /**
   * Comparator between two mappings where the original positions are compared.
   */
  SourceMapConsumer.prototype._compareOriginalPositions =
    function SourceMapConsumer_compareOriginalPositions(mappingA, mappingB) {
      if (mappingA.source > mappingB.source) {
        return 1;
      }
      else if (mappingA.source < mappingB.source) {
        return -1;
      }
      else {
        var cmp = mappingA.originalLine - mappingB.originalLine;
        return cmp === 0
          ? mappingA.originalColumn - mappingB.originalColumn
          : cmp;
      }
    };

  /**
   * Comparator between two mappings where the generated positions are compared.
   */
  SourceMapConsumer.prototype._compareGeneratedPositions =
    function SourceMapConsumer_compareGeneratedPositions(mappingA, mappingB) {
      var cmp = mappingA.generatedLine - mappingB.generatedLine;
      return cmp === 0
        ? mappingA.generatedColumn - mappingB.generatedColumn
        : cmp;
    };

  /**
   * Find the mapping that best matches the hypothetical "needle" mapping that
   * we are searching for in the given "haystack" of mappings.
   */
  SourceMapConsumer.prototype._findMapping =
    function SourceMapConsumer_findMapping(aNeedle, aMappings, aLineName,
                                           aColumnName, aComparator) {
      // To return the position we are searching for, we must first find the
      // mapping for the given position and then return the opposite position it
      // points to. Because the mappings are sorted, we can use binary search to
      // find the best mapping.

      if (aNeedle[aLineName] <= 0) {
        throw new TypeError('Line must be greater than or equal to 1, got '
                            + aNeedle[aLineName]);
      }
      if (aNeedle[aColumnName] < 0) {
        throw new TypeError('Column must be greater than or equal to 0, got '
                            + aNeedle[aColumnName]);
      }

      return binarySearch.search(aNeedle, aMappings, aComparator);
    };

  /**
   * Returns the original source, line, and column information for the generated
   * source's line and column positions provided. The only argument is an object
   * with the following properties:
   *
   *   - line: The line number in the generated source.
   *   - column: The column number in the generated source.
   *
   * and an object is returned with the following properties:
   *
   *   - source: The original source file, or null.
   *   - line: The line number in the original source, or null.
   *   - column: The column number in the original source, or null.
   *   - name: The original identifier, or null.
   */
  SourceMapConsumer.prototype.originalPositionFor =
    function SourceMapConsumer_originalPositionFor(aArgs) {
      var needle = {
        generatedLine: util.getArg(aArgs, 'line'),
        generatedColumn: util.getArg(aArgs, 'column')
      };

      var mapping = this._findMapping(needle,
                                      this._generatedMappings,
                                      "generatedLine",
                                      "generatedColumn",
                                      this._compareGeneratedPositions);

      if (mapping) {
        var source = util.getArg(mapping, 'source', null);
        if (source && this.sourceRoot) {
          source = util.join(this.sourceRoot, source);
        }
        return {
          source: source,
          line: util.getArg(mapping, 'originalLine', null),
          column: util.getArg(mapping, 'originalColumn', null),
          name: util.getArg(mapping, 'name', null)
        };
      }

      return {
        source: null,
        line: null,
        column: null,
        name: null
      };
    };

  /**
   * Returns the original source content. The only argument is the url of the
   * original source file. Returns null if no original source content is
   * availible.
   */
  SourceMapConsumer.prototype.sourceContentFor =
    function SourceMapConsumer_sourceContentFor(aSource) {
      if (!this.sourcesContent) {
        return null;
      }

      if (this.sourceRoot) {
        aSource = util.relative(this.sourceRoot, aSource);
      }

      if (this._sources.has(aSource)) {
        return this.sourcesContent[this._sources.indexOf(aSource)];
      }

      var url;
      if (this.sourceRoot
          && (url = util.urlParse(this.sourceRoot))) {
        // XXX: file:// URIs and absolute paths lead to unexpected behavior for
        // many users. We can help them out when they expect file:// URIs to
        // behave like it would if they were running a local HTTP server. See
        // https://bugzilla.mozilla.org/show_bug.cgi?id=885597.
        var fileUriAbsPath = aSource.replace(/^file:\/\//, "");
        if (url.scheme == "file"
            && this._sources.has(fileUriAbsPath)) {
          return this.sourcesContent[this._sources.indexOf(fileUriAbsPath)]
        }

        if ((!url.path || url.path == "/")
            && this._sources.has("/" + aSource)) {
          return this.sourcesContent[this._sources.indexOf("/" + aSource)];
        }
      }

      throw new Error('"' + aSource + '" is not in the SourceMap.');
    };

  /**
   * Returns the generated line and column information for the original source,
   * line, and column positions provided. The only argument is an object with
   * the following properties:
   *
   *   - source: The filename of the original source.
   *   - line: The line number in the original source.
   *   - column: The column number in the original source.
   *
   * and an object is returned with the following properties:
   *
   *   - line: The line number in the generated source, or null.
   *   - column: The column number in the generated source, or null.
   */
  SourceMapConsumer.prototype.generatedPositionFor =
    function SourceMapConsumer_generatedPositionFor(aArgs) {
      var needle = {
        source: util.getArg(aArgs, 'source'),
        originalLine: util.getArg(aArgs, 'line'),
        originalColumn: util.getArg(aArgs, 'column')
      };

      if (this.sourceRoot) {
        needle.source = util.relative(this.sourceRoot, needle.source);
      }

      var mapping = this._findMapping(needle,
                                      this._originalMappings,
                                      "originalLine",
                                      "originalColumn",
                                      this._compareOriginalPositions);

      if (mapping) {
        return {
          line: util.getArg(mapping, 'generatedLine', null),
          column: util.getArg(mapping, 'generatedColumn', null)
        };
      }

      return {
        line: null,
        column: null
      };
    };

  SourceMapConsumer.GENERATED_ORDER = 1;
  SourceMapConsumer.ORIGINAL_ORDER = 2;

  /**
   * Iterate over each mapping between an original source/line/column and a
   * generated line/column in this source map.
   *
   * @param Function aCallback
   *        The function that is called with each mapping.
   * @param Object aContext
   *        Optional. If specified, this object will be the value of `this` every
   *        time that `aCallback` is called.
   * @param aOrder
   *        Either `SourceMapConsumer.GENERATED_ORDER` or
   *        `SourceMapConsumer.ORIGINAL_ORDER`. Specifies whether you want to
   *        iterate over the mappings sorted by the generated file's line/column
   *        order or the original's source/line/column order, respectively. Defaults to
   *        `SourceMapConsumer.GENERATED_ORDER`.
   */
  SourceMapConsumer.prototype.eachMapping =
    function SourceMapConsumer_eachMapping(aCallback, aContext, aOrder) {
      var context = aContext || null;
      var order = aOrder || SourceMapConsumer.GENERATED_ORDER;

      var mappings;
      switch (order) {
      case SourceMapConsumer.GENERATED_ORDER:
        mappings = this._generatedMappings;
        break;
      case SourceMapConsumer.ORIGINAL_ORDER:
        mappings = this._originalMappings;
        break;
      default:
        throw new Error("Unknown order of iteration.");
      }

      var sourceRoot = this.sourceRoot;
      mappings.map(function (mapping) {
        var source = mapping.source;
        if (source && sourceRoot) {
          source = util.join(sourceRoot, source);
        }
        return {
          source: source,
          generatedLine: mapping.generatedLine,
          generatedColumn: mapping.generatedColumn,
          originalLine: mapping.originalLine,
          originalColumn: mapping.originalColumn,
          name: mapping.name
        };
      }).forEach(aCallback, context);
    };

  exports.SourceMapConsumer = SourceMapConsumer;

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/binary-search.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  /**
   * Recursive implementation of binary search.
   *
   * @param aLow Indices here and lower do not contain the needle.
   * @param aHigh Indices here and higher do not contain the needle.
   * @param aNeedle The element being searched for.
   * @param aHaystack The non-empty array being searched.
   * @param aCompare Function which takes two elements and returns -1, 0, or 1.
   */
  function recursiveSearch(aLow, aHigh, aNeedle, aHaystack, aCompare) {
    // This function terminates when one of the following is true:
    //
    //   1. We find the exact element we are looking for.
    //
    //   2. We did not find the exact element, but we can return the next
    //      closest element that is less than that element.
    //
    //   3. We did not find the exact element, and there is no next-closest
    //      element which is less than the one we are searching for, so we
    //      return null.
    var mid = Math.floor((aHigh - aLow) / 2) + aLow;
    var cmp = aCompare(aNeedle, aHaystack[mid]);
    if (cmp === 0) {
      // Found the element we are looking for.
      return aHaystack[mid];
    }
    else if (cmp > 0) {
      // aHaystack[mid] is greater than our needle.
      if (aHigh - mid > 1) {
        // The element is in the upper half.
        return recursiveSearch(mid, aHigh, aNeedle, aHaystack, aCompare);
      }
      // We did not find an exact match, return the next closest one
      // (termination case 2).
      return aHaystack[mid];
    }
    else {
      // aHaystack[mid] is less than our needle.
      if (mid - aLow > 1) {
        // The element is in the lower half.
        return recursiveSearch(aLow, mid, aNeedle, aHaystack, aCompare);
      }
      // The exact needle element was not found in this haystack. Determine if
      // we are in termination case (2) or (3) and return the appropriate thing.
      return aLow < 0
        ? null
        : aHaystack[aLow];
    }
  }

  /**
   * This is an implementation of binary search which will always try and return
   * the next lowest value checked if there is no exact hit. This is because
   * mappings between original and generated line/col pairs are single points,
   * and there is an implicit region between each of them, so a miss just means
   * that you aren't on the very start of a region.
   *
   * @param aNeedle The element you are looking for.
   * @param aHaystack The array that is being searched.
   * @param aCompare A function which takes the needle and an element in the
   *     array and returns -1, 0, or 1 depending on whether the needle is less
   *     than, equal to, or greater than the element, respectively.
   */
  exports.search = function search(aNeedle, aHaystack, aCompare) {
    return aHaystack.length > 0
      ? recursiveSearch(-1, aHaystack.length, aNeedle, aHaystack, aCompare)
      : null;
  };

});

});

require.define("/node_modules/source-map-support/node_modules/source-map/lib/source-map/source-node.js",function(require,module,exports,__dirname,__filename,process){/* -*- Mode: js; js-indent-level: 2; -*- */
/*
 * Copyright 2011 Mozilla Foundation and contributors
 * Licensed under the New BSD license. See LICENSE or:
 * http://opensource.org/licenses/BSD-3-Clause
 */
if (typeof define !== 'function') {
    var define = require('amdefine')(module);
}
define(function (require, exports, module) {

  var SourceMapGenerator = require('./source-map-generator').SourceMapGenerator;
  var util = require('./util');

  /**
   * SourceNodes provide a way to abstract over interpolating/concatenating
   * snippets of generated JavaScript source code while maintaining the line and
   * column information associated with the original source code.
   *
   * @param aLine The original line number.
   * @param aColumn The original column number.
   * @param aSource The original source's filename.
   * @param aChunks Optional. An array of strings which are snippets of
   *        generated JS, or other SourceNodes.
   * @param aName The original identifier.
   */
  function SourceNode(aLine, aColumn, aSource, aChunks, aName) {
    this.children = [];
    this.sourceContents = {};
    this.line = aLine === undefined ? null : aLine;
    this.column = aColumn === undefined ? null : aColumn;
    this.source = aSource === undefined ? null : aSource;
    this.name = aName === undefined ? null : aName;
    if (aChunks != null) this.add(aChunks);
  }

  /**
   * Creates a SourceNode from generated code and a SourceMapConsumer.
   *
   * @param aGeneratedCode The generated code
   * @param aSourceMapConsumer The SourceMap for the generated code
   */
  SourceNode.fromStringWithSourceMap =
    function SourceNode_fromStringWithSourceMap(aGeneratedCode, aSourceMapConsumer) {
      // The SourceNode we want to fill with the generated code
      // and the SourceMap
      var node = new SourceNode();

      // The generated code
      // Processed fragments are removed from this array.
      var remainingLines = aGeneratedCode.split('\n');

      // We need to remember the position of "remainingLines"
      var lastGeneratedLine = 1, lastGeneratedColumn = 0;

      // The generate SourceNodes we need a code range.
      // To extract it current and last mapping is used.
      // Here we store the last mapping.
      var lastMapping = null;

      aSourceMapConsumer.eachMapping(function (mapping) {
        if (lastMapping === null) {
          // We add the generated code until the first mapping
          // to the SourceNode without any mapping.
          // Each line is added as separate string.
          while (lastGeneratedLine < mapping.generatedLine) {
            node.add(remainingLines.shift() + "\n");
            lastGeneratedLine++;
          }
          if (lastGeneratedColumn < mapping.generatedColumn) {
            var nextLine = remainingLines[0];
            node.add(nextLine.substr(0, mapping.generatedColumn));
            remainingLines[0] = nextLine.substr(mapping.generatedColumn);
            lastGeneratedColumn = mapping.generatedColumn;
          }
        } else {
          // We add the code from "lastMapping" to "mapping":
          // First check if there is a new line in between.
          if (lastGeneratedLine < mapping.generatedLine) {
            var code = "";
            // Associate full lines with "lastMapping"
            do {
              code += remainingLines.shift() + "\n";
              lastGeneratedLine++;
              lastGeneratedColumn = 0;
            } while (lastGeneratedLine < mapping.generatedLine);
            // When we reached the correct line, we add code until we
            // reach the correct column too.
            if (lastGeneratedColumn < mapping.generatedColumn) {
              var nextLine = remainingLines[0];
              code += nextLine.substr(0, mapping.generatedColumn);
              remainingLines[0] = nextLine.substr(mapping.generatedColumn);
              lastGeneratedColumn = mapping.generatedColumn;
            }
            // Create the SourceNode.
            addMappingWithCode(lastMapping, code);
          } else {
            // There is no new line in between.
            // Associate the code between "lastGeneratedColumn" and
            // "mapping.generatedColumn" with "lastMapping"
            var nextLine = remainingLines[0];
            var code = nextLine.substr(0, mapping.generatedColumn -
                                          lastGeneratedColumn);
            remainingLines[0] = nextLine.substr(mapping.generatedColumn -
                                                lastGeneratedColumn);
            lastGeneratedColumn = mapping.generatedColumn;
            addMappingWithCode(lastMapping, code);
          }
        }
        lastMapping = mapping;
      }, this);
      // We have processed all mappings.
      // Associate the remaining code in the current line with "lastMapping"
      // and add the remaining lines without any mapping
      addMappingWithCode(lastMapping, remainingLines.join("\n"));

      // Copy sourcesContent into SourceNode
      aSourceMapConsumer.sources.forEach(function (sourceFile) {
        var content = aSourceMapConsumer.sourceContentFor(sourceFile);
        if (content) {
          node.setSourceContent(sourceFile, content);
        }
      });

      return node;

      function addMappingWithCode(mapping, code) {
        if (mapping.source === undefined) {
          node.add(code);
        } else {
          node.add(new SourceNode(mapping.originalLine,
                                  mapping.originalColumn,
                                  mapping.source,
                                  code,
                                  mapping.name));
        }
      }
    };

  /**
   * Add a chunk of generated JS to this source node.
   *
   * @param aChunk A string snippet of generated JS code, another instance of
   *        SourceNode, or an array where each member is one of those things.
   */
  SourceNode.prototype.add = function SourceNode_add(aChunk) {
    if (Array.isArray(aChunk)) {
      aChunk.forEach(function (chunk) {
        this.add(chunk);
      }, this);
    }
    else if (aChunk instanceof SourceNode || typeof aChunk === "string") {
      if (aChunk) {
        this.children.push(aChunk);
      }
    }
    else {
      throw new TypeError(
        "Expected a SourceNode, string, or an array of SourceNodes and strings. Got " + aChunk
      );
    }
    return this;
  };

  /**
   * Add a chunk of generated JS to the beginning of this source node.
   *
   * @param aChunk A string snippet of generated JS code, another instance of
   *        SourceNode, or an array where each member is one of those things.
   */
  SourceNode.prototype.prepend = function SourceNode_prepend(aChunk) {
    if (Array.isArray(aChunk)) {
      for (var i = aChunk.length-1; i >= 0; i--) {
        this.prepend(aChunk[i]);
      }
    }
    else if (aChunk instanceof SourceNode || typeof aChunk === "string") {
      this.children.unshift(aChunk);
    }
    else {
      throw new TypeError(
        "Expected a SourceNode, string, or an array of SourceNodes and strings. Got " + aChunk
      );
    }
    return this;
  };

  /**
   * Walk over the tree of JS snippets in this node and its children. The
   * walking function is called once for each snippet of JS and is passed that
   * snippet and the its original associated source's line/column location.
   *
   * @param aFn The traversal function.
   */
  SourceNode.prototype.walk = function SourceNode_walk(aFn) {
    this.children.forEach(function (chunk) {
      if (chunk instanceof SourceNode) {
        chunk.walk(aFn);
      }
      else {
        if (chunk !== '') {
          aFn(chunk, { source: this.source,
                       line: this.line,
                       column: this.column,
                       name: this.name });
        }
      }
    }, this);
  };

  /**
   * Like `String.prototype.join` except for SourceNodes. Inserts `aStr` between
   * each of `this.children`.
   *
   * @param aSep The separator.
   */
  SourceNode.prototype.join = function SourceNode_join(aSep) {
    var newChildren;
    var i;
    var len = this.children.length;
    if (len > 0) {
      newChildren = [];
      for (i = 0; i < len-1; i++) {
        newChildren.push(this.children[i]);
        newChildren.push(aSep);
      }
      newChildren.push(this.children[i]);
      this.children = newChildren;
    }
    return this;
  };

  /**
   * Call String.prototype.replace on the very right-most source snippet. Useful
   * for trimming whitespace from the end of a source node, etc.
   *
   * @param aPattern The pattern to replace.
   * @param aReplacement The thing to replace the pattern with.
   */
  SourceNode.prototype.replaceRight = function SourceNode_replaceRight(aPattern, aReplacement) {
    var lastChild = this.children[this.children.length - 1];
    if (lastChild instanceof SourceNode) {
      lastChild.replaceRight(aPattern, aReplacement);
    }
    else if (typeof lastChild === 'string') {
      this.children[this.children.length - 1] = lastChild.replace(aPattern, aReplacement);
    }
    else {
      this.children.push(''.replace(aPattern, aReplacement));
    }
    return this;
  };

  /**
   * Set the source content for a source file. This will be added to the SourceMapGenerator
   * in the sourcesContent field.
   *
   * @param aSourceFile The filename of the source file
   * @param aSourceContent The content of the source file
   */
  SourceNode.prototype.setSourceContent =
    function SourceNode_setSourceContent(aSourceFile, aSourceContent) {
      this.sourceContents[util.toSetString(aSourceFile)] = aSourceContent;
    };

  /**
   * Walk over the tree of SourceNodes. The walking function is called for each
   * source file content and is passed the filename and source content.
   *
   * @param aFn The traversal function.
   */
  SourceNode.prototype.walkSourceContents =
    function SourceNode_walkSourceContents(aFn) {
      this.children.forEach(function (chunk) {
        if (chunk instanceof SourceNode) {
          chunk.walkSourceContents(aFn);
        }
      }, this);
      Object.keys(this.sourceContents).forEach(function (sourceFileKey) {
        aFn(util.fromSetString(sourceFileKey), this.sourceContents[sourceFileKey]);
      }, this);
    };

  /**
   * Return the string representation of this source node. Walks over the tree
   * and concatenates all the various snippets together to one string.
   */
  SourceNode.prototype.toString = function SourceNode_toString() {
    var str = "";
    this.walk(function (chunk) {
      str += chunk;
    });
    return str;
  };

  /**
   * Returns the string representation of this source node along with a source
   * map.
   */
  SourceNode.prototype.toStringWithSourceMap = function SourceNode_toStringWithSourceMap(aArgs) {
    var generated = {
      code: "",
      line: 1,
      column: 0
    };
    var map = new SourceMapGenerator(aArgs);
    var sourceMappingActive = false;
    this.walk(function (chunk, original) {
      generated.code += chunk;
      if (original.source !== null
          && original.line !== null
          && original.column !== null) {
        map.addMapping({
          source: original.source,
          original: {
            line: original.line,
            column: original.column
          },
          generated: {
            line: generated.line,
            column: generated.column
          },
          name: original.name
        });
        sourceMappingActive = true;
      } else if (sourceMappingActive) {
        map.addMapping({
          generated: {
            line: generated.line,
            column: generated.column
          }
        });
        sourceMappingActive = false;
      }
      chunk.split('').forEach(function (ch) {
        if (ch === '\n') {
          generated.line++;
          generated.column = 0;
        } else {
          generated.column++;
        }
      });
    });
    this.walkSourceContents(function (sourceFile, sourceContent) {
      map.setSourceContent(sourceFile, sourceContent);
    });

    return { code: generated.code, map: map };
  };

  exports.SourceNode = SourceNode;

});

});

require.define("fs",function(require,module,exports,__dirname,__filename,process){// nothing to see here... no file methods for the browser

});

require.define("/lib/transport.js",function(require,module,exports,__dirname,__filename,process){// Generated by CoffeeScript 1.6.3
(function() {
  var CometClientConnection, CometConnection, Connection, ConnectionEndpoint, FdConnection, JSONCodec, ProxyMux, SocketConnection, WebSocketConnection, XusEndpoint, d, deadComets, error_bad_connection, exports, fs, _,
    __hasProp = {}.hasOwnProperty,
    __extends = function(child, parent) { for (var key in parent) { if (__hasProp.call(parent, key)) child[key] = parent[key]; } function ctor() { this.constructor = child; } ctor.prototype = parent.prototype; child.prototype = new ctor(); child.__super__ = parent.prototype; return child; };

  d = (exports = module.exports = require('./base')).d;

  error_bad_connection = require('./proto').error_bad_connection;

  _ = require('./lodash.min');

  fs = require('fs');

  exports.JSONCodec = JSONCodec = {
    prepare: function(con) {},
    send: function(con, obj) {
      return con.write("" + (JSON.stringify(obj)) + "\n");
    },
    newData: function(con, data) {
      var batch, msgs, _i, _len, _ref, _results;
      if (typeof data !== 'string') {
        data = data.toString();
      }
      msgs = (con.saved + data).trim().split('\n');
      con.saved = data[data.length - 1] === '\n' ? '' : msgs.pop();
      _ref = _.map(msgs, function(m) {
        var err;
        try {
          con.verbose("PROCESSING BATCH: " + m);
          return JSON.parse(m);
        } catch (_error) {
          err = _error;
          con.addCmd(['error', "Could not parse message: " + m]);
          return con.send();
        }
      });
      _results = [];
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        batch = _ref[_i];
        _results.push(con.processBatch(batch));
      }
      return _results;
    }
  };

  exports.Connection = Connection = (function() {
    Connection.prototype.write = function(str) {
      return this.master.disconnect(this, error_bad_connection, "Connection has no 'write' method");
    };

    Connection.prototype.basicClose = function() {
      return this.master.disconnect(this, error_bad_connection, "Connection has no 'disconnect' method");
    };

    function Connection(master, codec, saved) {
      var err, _ref;
      this.codec = codec;
      this.saved = saved != null ? saved : '';
      this.codec = (_ref = this.codec) != null ? _ref : JSONCodec;
      try {
        this.codec.prepare(this);
      } catch (_error) {
        err = _error;
        console.log(err.stack);
      }
      this.q = [];
      this.connected = true;
      this.setMaster(master);
    }

    Connection.prototype.setMaster = function(master) {
      this.master = master;
    };

    Connection.prototype.verbose = function(str) {
      return this.master.verbose(str);
    };

    Connection.prototype.isConnected = function() {
      return this.connected;
    };

    Connection.prototype.close = function() {
      this.connected = false;
      this.q = null;
      return this.basicClose();
    };

    Connection.prototype.addCmd = function(cmd) {
      return this.q.push(cmd);
    };

    Connection.prototype.send = function() {
      var q, _ref;
      if (this.connected && this.q.length) {
        this.verbose("" + (d(this)) + " SENDING " + (JSON.stringify(this.q)));
        _ref = [this.q, []], q = _ref[0], this.q = _ref[1];
        return this.codec.send(this, q);
      }
    };

    Connection.prototype.newData = function(data) {
      this.verbose("" + (d(this)) + " read data: " + data);
      return this.codec.newData(this, data);
    };

    Connection.prototype.processBatch = function(batch) {
      return this.master.processBatch(this, batch);
    };

    return Connection;

  })();

  exports.FdConnection = FdConnection = (function(_super) {
    __extends(FdConnection, _super);

    function FdConnection(input, output) {
      this.input = input;
      this.output = output;
      FdConnection.__super__.constructor.call(this, null, this["null"]);
      this.q = [];
      this.writing = false;
    }

    FdConnection.prototype.setMaster = function(master) {
      this.master = master;
      if (this.master) {
        this.master.addConnection(this);
        return this.read(new Buffer(65536));
      }
    };

    FdConnection.prototype.basicClose = function() {
      fs.close(this.input, function(err) {
        return console.log("Error closing connection: " + err.stack);
      });
      return fs.close(this.output, function(err) {
        return console.log("Error closing connection: " + err.stack);
      });
    };

    FdConnection.prototype.connected = true;

    FdConnection.prototype.read = function(buf) {
      var _this = this;
      return fs.read(this.input, buf, 0, buf.length, null, function(err, bytesRead) {
        if (err) {
          _this.verbose("" + (d(_this)) + " disconnect");
          return _this.master.disconnect(_this);
        } else {
          _this.verbose("" + (d(_this)) + " data '" + data + "'");
          _this.newData(buf.toString(null, 0, bytesRead));
          return _this.read(buf);
        }
      });
    };

    FdConnection.prototype.write = function(str) {
      if (str.length) {
        this.q.push(str);
        if (!this.writing) {
          this.writing = true;
          return this.writeNext();
        }
      }
    };

    FdConnection.prototype.writeNext = function() {
      var buf;
      buf = new Buffer(this.q[0]);
      splice(this.q, 0, 1);
      return writeBuffer(buf);
    };

    FdConnection.prototype.writeBuffer = function(buf) {
      var _this = this;
      return fs.write(this.output, buf, 0, buf.length, null, function(err, written) {
        if (err) {
          _this.verbose("" + (d(_this)) + " disconnect");
          return _this.master.disconnect(_this);
        } else if (written < buf.length) {
          return _this.writeBuffer(buf.slice(written));
        } else if (_this.q.length) {
          return _this.writeNext();
        } else {
          return _this.writing = false;
        }
      });
    };

    return FdConnection;

  })(Connection);

  exports.SocketConnection = SocketConnection = (function(_super) {
    __extends(SocketConnection, _super);

    function SocketConnection(master, con, initialData) {
      var _this = this;
      this.master = master;
      this.con = con;
      SocketConnection.__super__.constructor.call(this, this.master, null, (initialData != null ? initialData : '').toString());
      this.con.on('data', function(data) {
        _this.verbose("" + (d(_this)) + " data: '" + data + "'");
        return _this.newData(data);
      });
      this.con.on('end', function(hadError) {
        _this.verbose("" + (d(_this)) + " disconnect");
        return _this.master.disconnect(_this);
      });
      this.con.on('close', function(hadError) {
        _this.verbose("" + (d(_this)) + " disconnect");
        return _this.master.disconnect(_this);
      });
      this.con.on('error', function(hadError) {
        _this.verbose("" + (d(_this)) + " disconnect");
        return _this.master.disconnect(_this);
      });
      this.master.addConnection(this);
    }

    SocketConnection.prototype.connected = true;

    SocketConnection.prototype.write = function(str) {
      return this.con.write(str);
    };

    SocketConnection.prototype.basicClose = function() {
      var err;
      try {
        return this.con.destroy();
      } catch (_error) {
        err = _error;
        return console.log("Error closing connection: " + err.stack);
      }
    };

    return SocketConnection;

  })(Connection);

  exports.WebSocketConnection = WebSocketConnection = (function(_super) {
    __extends(WebSocketConnection, _super);

    function WebSocketConnection(master, con) {
      this.master = master;
      this.con = con;
      this.pending = [];
      WebSocketConnection.__super__.constructor.call(this, this.master);
    }

    WebSocketConnection.prototype.setMaster = function(master) {
      var _this = this;
      this.master = master;
      if (this.master) {
        if (this.con.readyState === 1) {
          this.sendPending();
        } else {
          this.con.onopen = function(evt) {
            return _this.sendPending();
          };
        }
        this.con.onmessage = function(evt) {
          console.log("MESSAGE: " + (JSON.stringify(evt.data)));
          return _this.newData(evt.data);
        };
        this.con.onend = function(hadError) {
          return _this.master.disconnect(_this);
        };
        this.con.onclose = function(hadError) {
          return _this.master.disconnect(_this);
        };
        this.con.onerror = function(hadError) {
          return _this.master.disconnect(_this);
        };
        return this.master.addConnection(this);
      }
    };

    WebSocketConnection.prototype.connected = true;

    WebSocketConnection.prototype.write = function(str) {
      return this.pending.push(str);
    };

    WebSocketConnection.prototype.sendPending = function() {
      var msg, _i, _len, _ref;
      console.log("CHANGING WRITE METHOD");
      this.write = function(str) {
        this.verbose("" + (d(this)) + " writing: " + str);
        return this.con.send(str);
      };
      _ref = this.pending;
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        msg = _ref[_i];
        this.write(msg);
      }
      this.pending = null;
      return this.sendPending = function() {};
    };

    WebSocketConnection.prototype.basicClose = function() {
      var err;
      try {
        return this.con.terminate();
      } catch (_error) {
        err = _error;
        return console.log("Error closing connection: " + err.stack);
      }
    };

    return WebSocketConnection;

  })(Connection);

  deadComets = {};

  exports.CometConnection = CometConnection = (function(_super) {
    __extends(CometConnection, _super);

    function CometConnection(master, socket) {
      this.master = master;
      this.socket = socket;
      CometConnection.__super__.constructor.call(this, this.master);
    }

    CometConnection.prototype.setMaster = function(master) {
      var _this = this;
      this.master = master;
      console.log("MASTER: " + this.master);
      this.master.addConnection(this);
      this.socket.on('disconnect', function() {
        return _this.master.disconnect(_this);
      });
      return this.socket.on('xusCmd', function(data) {
        _this.verbose("MESSAGE: " + data.str);
        return _this.newData(data.str);
      });
    };

    CometConnection.prototype.connected = true;

    CometConnection.prototype.write = function(str) {
      this.verbose("" + (d(this)) + " writing: " + str);
      return this.socket.emit('xusCmd', {
        str: str
      });
    };

    CometConnection.prototype.basicClose = function() {
      if (!this.socket._zombi) {
        this.socket.emit('xusTerminate', '');
      }
      return deadComets[this.socket._uuid] = true;
    };

    return CometConnection;

  })(Connection);

  exports.CometClientConnection = CometClientConnection = (function(_super) {
    __extends(CometClientConnection, _super);

    function CometClientConnection(master, url) {
      var _this = this;
      this.master = master;
      CometClientConnection.__super__.constructor.call(this, this.master);
      this.pending = [];
      this.socket = comet.connect(url).on('connect', function() {
        return _this.sendPending();
      }).on('xusCmd', function(data) {
        _this.verbose("MESSAGE: " + data.str);
        return _this.newData(data.str);
      }).on('xusTerminate', function() {
        return _this.master.disconnect(_this);
      });
    }

    CometClientConnection.prototype.connected = true;

    CometClientConnection.prototype.write = function(str) {
      return this.pending.push(str);
    };

    CometClientConnection.prototype.sendPending = function() {
      var msg, _i, _len, _ref;
      console.log("CHANGING WRITE METHOD");
      this.write = function(str) {
        this.verbose("" + (d(this)) + " writing: " + str);
        return this.socket.emit('xusCmd', {
          str: str
        });
      };
      _ref = this.pending;
      for (_i = 0, _len = _ref.length; _i < _len; _i++) {
        msg = _ref[_i];
        this.write(msg);
      }
      return this.pending = null;
    };

    CometClientConnection.prototype.basicClose = function() {
      if (!this.socket._zombi) {
        this.socket.emit('xus.terminate', '');
      }
      return deadComets[this.socket._uuid] = true;
    };

    return CometClientConnection;

  })(Connection);

  exports.ProxyMux = ProxyMux = (function() {
    function ProxyMux(handler) {
      this.handler = handler;
      this.currentId = 0;
      this.connections = {};
    }

    ProxyMux.prototype.verbose = function() {};

    ProxyMux.prototype.prepare = function() {};

    ProxyMux.prototype.addConnection = function(con) {
      this.verbose("proxy main connection");
      return this.mainConnection = con;
    };

    ProxyMux.prototype.newConnectionEndpoint = function(conFactory) {
      var _this = this;
      return this.newConnection(function(id) {
        var endPoint;
        endPoint = new ConnectionEndpoint(_this, id);
        conFactory(endPoint);
        return endPoint;
      });
    };

    ProxyMux.prototype.newPeer = function() {
      var peer,
        _this = this;
      peer = new exports.Peer;
      console.log("SETTING CONNECTION");
      this.newConnection(function(id) {
        peer.setConnection(new XusEndpoint(peer, _this, id));
        return peer;
      });
      this.mainSend([['connect', peer.con.id]]);
      peer.con.newconnection = false;
      return peer;
    };

    ProxyMux.prototype.newConnection = function(factory) {
      var con, id;
      id = this.currentId++;
      con = factory(id);
      this.verbose("proxy got new connection: " + (d(con)) + ", id: " + id);
      this.connections[id] = con;
      return con;
    };

    ProxyMux.prototype.processBatch = function(muxedCon, batch) {
      var b, cmd, con, id, _ref;
      this.verbose("proxy demuxing batch: " + (JSON.stringify(batch)));
      _ref = batch[0], cmd = _ref[0], id = _ref[1];
      con = this.connections[id];
      switch (cmd) {
        case 'connect':
          this.verbose("MUX connect");
          con = new XusEndpoint(this.handler, this, id);
          this.connections[id] = con;
          this.handler.addConnection(con);
          break;
        case 'disconnect':
          this.verbose("MUX disconnect");
          if (con) {
            this.removeConnection(con);
            con.disconnect();
          }
          break;
        case 'data':
          this.verbose("MUX data: " + (JSON.stringify(batch.slice(1))));
      }
      b = batch.slice(1);
      if (b.length) {
        return this.handler.processBatch(con, b);
      }
    };

    ProxyMux.prototype.disconnect = function(con) {
      if (con === this.mainConnection) {
        return this.mainDisconnect(con);
      } else {
        this.mainSend([['disconnect', con.id]]);
        return this.removeConnection(con);
      }
    };

    ProxyMux.prototype.mainDisconnect = function(con) {
      console.log("Disconnecting mux connection");
      return process.exit();
    };

    ProxyMux.prototype.removeConnection = function(con) {
      var connected;
      if (connected) {
        connected = false;
        return delete this.connections[con.id];
      }
    };

    ProxyMux.prototype.mux = function(endpoint, batch) {
      var b;
      b = batch.slice(0);
      b.splice(0, 0, ['data', endpoint.id]);
      endpoint.newConnection = false;
      return this.mainSend(b);
    };

    ProxyMux.prototype.mainSend = function(batch) {
      this.verbose("" + (d(this)) + " proxy forwarding muxed batch: " + (JSON.stringify(batch)) + " to " + this.mainConnection.constructor.name);
      this.mainConnection.q = batch;
      return this.mainConnection.send();
    };

    ProxyMux.prototype.prepare = function(con) {};

    return ProxyMux;

  })();

  ConnectionEndpoint = (function() {
    function ConnectionEndpoint(mux, id) {
      this.mux = mux;
      this.id = id;
      this.verbose("New ConnectionEndpoint");
      this.newConnection = true;
    }

    ConnectionEndpoint.prototype.verbose = function(str) {
      return this.mux.verbose(str);
    };

    ConnectionEndpoint.prototype.addConnection = function(con) {
      this.con = con;
      this.verbose("ConnectionEndpoint connection: " + this.con.constructor.name);
      this.mux.mainSend([['connect', this.id]]);
      return this.newconnection = false;
    };

    ConnectionEndpoint.prototype.disconnect = function(con) {
      this.verbose("ConnectionEndpoint disconnecting");
      return this.mux.disconnect(this);
    };

    ConnectionEndpoint.prototype.send = function(demuxedBatch) {
      this.verbose("ConnectionEndpoint writing: " + (JSON.stringify(demuxedBatch)));
      this.con.q = demuxedBatch;
      return this.con.send();
    };

    ConnectionEndpoint.prototype.processBatch = function(con, batch) {
      this.verbose("Socket endpoint read: " + batch);
      return this.mux.mux(this, batch);
    };

    return ConnectionEndpoint;

  })();

  XusEndpoint = (function(_super) {
    __extends(XusEndpoint, _super);

    function XusEndpoint(master, proxy, id) {
      this.master = master;
      this.proxy = proxy;
      this.id = id;
      XusEndpoint.__super__.constructor.call(this, this.master, this.proxy);
      this.verbose = this.proxy.verbose;
    }

    XusEndpoint.prototype.newConnection = false;

    XusEndpoint.prototype.basicClose = function() {
      return this.proxy.disconnect(this);
    };

    XusEndpoint.prototype.send = function() {
      var q, _ref;
      this.verbose("SEND " + (JSON.stringify(this.q)));
      _ref = [this.q, []], q = _ref[0], this.q = _ref[1];
      return this.proxy.mux(this, q);
    };

    XusEndpoint.prototype.disconnect = function() {
      return this.master.disconnect(this);
    };

    return XusEndpoint;

  })(Connection);

}).call(this);

/*
//@ sourceMappingURL=transport.map
*/

});

require.define("/lib/lodash.min.js",function(require,module,exports,__dirname,__filename,process){/*!
 Lo-Dash 0.7.0 lodash.com/license
 Underscore.js 1.3.3 github.com/documentcloud/underscore/blob/master/LICENSE
*/
;(function(e,t){function s(e){return new o(e)}function o(e){if(e&&e.__wrapped__)return e;this.__wrapped__=e}function u(e,t){return function(n,r,i){return e.call(t,n,r,i)}}function a(e,t,n){t||(t=0);var r=e.length,i=r-t>=(n||W),s=i?{}:e;if(i)for(var o=t-1;++o<r;)n=e[o]+"",(at.call(s,n)?s[n]:s[n]=[]).push(e[o]);return function(e){if(i){var n=e+"";return at.call(s,n)&&-1<L(s[n],e)}return-1<L(s,e,t)}}function f(e,n){var r=e.b,i=n.b,e=e.a,n=n.a;if(e!==n){if(e>n||e===t)return 1;if(e<n||n===t)return-1}return r<
i?-1:1}function l(e,t,n){function r(){var u=arguments,a=s?this:t;return i||(e=t[o]),n.length&&(u=u.length?n.concat(ct.call(u)):n),this instanceof r?(v.prototype=e.prototype,a=new v,(u=e.apply(a,u))&&$t[typeof u]?u:a):e.apply(a,u)}var i=E(e),s=!n,o=e;return s&&(n=t),r}function c(){for(var e,t,n,s=-1,o=arguments.length,a={e:"",f:"",j:"",q:"",c:{d:""},m:{d:""}};++s<o;)for(t in e=arguments[s],e)n=(n=e[t])==r?"":n,/d|i/.test(t)?("string"==typeof n&&(n={b:n,l:n}),a.c[t]=n.b||"",a.m[t]=n.l||""):a[t]=n;e=
a.a,t=/^[^,]+/.exec(e)[0],n=a.s,a.g=t,a.h=Mt,a.k=Rt,a.n=Pt,a.p=rt,a.r=a.r!==i,a.s=n==r?Ut:n,a.o==r&&(a.o=jt),a.f||(a.f="if(!"+t+")return u");if("e"!=t||!a.c.i)a.c=r;t="",a.s&&(t+="'use strict';"),t+="var j,A,k="+a.g+",u",a.j&&(t+="="+a.j),t+=";"+a.f+";"+a.q+";",a.c&&(t+="var l=k.length;j=-1;",a.m&&(t+="if(l===+l){"),a.o&&(t+="if(z.call(k)==x){k=k.split('')}"),t+=a.c.d+";while(++j<l){A=k[j];"+a.c.i+"}",a.m&&(t+="}"));if(a.m){a.c?t+="else{":a.n&&(t+="var l=k.length;j=-1;if(l&&O(k)){while(++j<l){A=k[j+=''];"+
a.m.i+"}}else{"),a.h||(t+="var v=typeof k=='function'&&r.call(k,'prototype');");if(a.k&&a.r)t+="var o=-1,p=X[typeof k]?m(k):[],l=p.length;"+a.m.d+";while(++o<l){j=p[o];",a.h||(t+="if(!(v&&j=='prototype')){"),t+="A=k[j];"+a.m.i+"",a.h||(t+="}");else{t+=a.m.d+";for(j in k){";if(!a.h||a.r)t+="if(",a.h||(t+="!(v&&j=='prototype')"),!a.h&&a.r&&(t+="&&"),a.r&&(t+="h.call(k,j)"),t+="){";t+="A=k[j];"+a.m.i+";";if(!a.h||a.r)t+="}"}t+="}";if(a.h){t+="var g=k.constructor;";for(n=0;7>n;n++)t+="j='"+a.p[n]+"';if("
,"constructor"==a.p[n]&&(t+="!(g&&g.prototype===k)&&"),t+="h.call(k,j)){A=k[j];"+a.m.i+"}"}if(a.c||a.n)t+="}"}return t+=a.e+";return u",Function("D,E,F,c,I,f,J,h,i,M,O,Q,S,T,W,X,m,r,w,x,z","var G=function("+e+"){"+t+"};return G")(zt,R,D,u,f,ut,ln,at,P,L,w,sn,E,on,Ct,$t,gt,lt,ct,Lt,ht)}function h(e,t){return ot[t]}function p(e){return"\\"+Jt[e]}function d(e){return Xt[e]}function v(){}function m(e,t){if(e&&V.test(t))return"<e%-"+t+"%>";var n=ot.length;return ot[n]="'+__e("+t+")+'",it+n+st}function g
(e,t,n,i){return i?(e=ot.length,ot[e]="';"+i+";__p+='",it+e+st):t?m(r,t):y(r,n)}function y(e,t){if(e&&V.test(t))return"<e%="+t+"%>";var n=ot.length;return ot[n]="'+((__t=("+t+"))==null?'':__t)+'",it+n+st}function b(e){return Vt[e]}function w(e){return ht.call(e)==Et}function E(e){return"function"==typeof e}function S(e,t){var n=i;if(!e||"object"!=typeof e||!t&&w(e))return n;var r=e.constructor;return(!Ft||"function"==typeof e.toString||"string"!=typeof (e+""))&&(!E(r)||r instanceof r)?Dt?(ln(e,function(
e,t,r){return n=!at.call(r,t),i}),n===i):(ln(e,function(e,t){n=t}),n===i||at.call(e,n)):n}function x(e,t,s,o,u){if(e==r)return e;s&&(t=i);if(s=$t[typeof e]){var a=ht.call(e);if(!Wt[a]||Ht&&w(e))return e;var f=a==St,s=f||(a==Ct?on(e,n):s)}if(!s||!t)return s?f?ct.call(e):fn({},e):e;s=e.constructor;switch(a){case xt:return new s(e==n);case Tt:return new s(+e);case Nt:case Lt:return new s(e);case kt:return s(e.source,G.exec(e))}o||(o=[]),u||(u=[]);for(a=o.length;a--;)if(o[a]==e)return u[a];var l=f?s(
a=e.length):{};o.push(e),u.push(l);if(f)for(f=-1;++f<a;)l[f]=x(e[f],t,r,o,u);else cn(e,function(e,n){l[n]=x(e,t,r,o,u)});return l}function T(e,t,s,o){if(e==r||t==r)return e===t;if(e===t)return 0!==e||1/e==1/t;if($t[typeof e]||$t[typeof t])e=e.__wrapped__||e,t=t.__wrapped__||t;var u=ht.call(e);if(u!=ht.call(t))return i;switch(u){case xt:case Tt:return+e==+t;case Nt:return e!=+e?t!=+t:0==e?1/e==1/t:e==+t;case kt:case Lt:return e==t+""}var a=zt[u];if(Ht&&!a&&(a=w(e))&&!w(t)||!a&&(u!=Ct||Ft&&("function"!=typeof 
e.toString&&"string"==typeof (e+"")||"function"!=typeof t.toString&&"string"==typeof (t+""))))return i;s||(s=[]),o||(o=[]);for(u=s.length;u--;)if(s[u]==e)return o[u]==t;var u=-1,f=n,l=0;s.push(e),o.push(t);if(a){l=e.length;if(f=l==t.length)for(;l--&&(f=T(e[l],t[l],s,o)););return f}a=e.constructor,f=t.constructor;if(a!=f&&(!E(a)||!(a instanceof a&&E(f)&&f instanceof f)))return i;for(var c in e)if(at.call(e,c)&&(l++,!at.call(t,c)||!T(e[c],t[c],s,o)))return i;for(c in t)if(at.call(t,c)&&!(l--))return i
;if(Mt)for(;7>++u;)if(c=rt[u],at.call(e,c)&&(!at.call(t,c)||!T(e[c],t[c],s,o)))return i;return n}function N(e,t,n,r){if(!e)return n;var i=e.length,s=3>arguments.length;r&&(t=u(t,r));if(i===+i){var o=jt&&ht.call(e)==Lt?e.split(""):e;for(i&&s&&(n=o[--i]);i--;)n=t(n,o[i],i,e);return n}o=vn(e);for((i=o.length)&&s&&(n=e[o[--i]]);i--;)s=o[i],n=t(n,e[s],s,e);return n}function C(e,t,n){if(e)return t==r||n?e[0]:ct.call(e,0,t)}function k(e,t){var n=[];if(!e)return n;for(var r,i=-1,s=e.length;++i<s;)r=e[i],
sn(r)?ft.apply(n,t?r:k(r)):n.push(r);return n}function L(e,t,n){if(!e)return-1;var r=-1,i=e.length;if(n){if("number"!=typeof n)return r=M(e,t),e[r]===t?r:-1;r=(0>n?yt(0,i+n):n)-1}for(;++r<i;)if(e[r]===t)return r;return-1}function A(e,t,n){var r=-Infinity,i=r;if(!e)return i;var s=-1,o=e.length;if(!t){for(;++s<o;)e[s]>i&&(i=e[s]);return i}for(n&&(t=u(t,n));++s<o;)n=t(e[s],s,e),n>r&&(r=n,i=e[s]);return i}function O(e,t,n){return e?ct.call(e,t==r||n?1:t):[]}function M(e,t,n,r){if(!e)return 0;var i=0,
s=e.length;if(n){r&&(n=D(n,r));for(t=n(t);i<s;)r=i+s>>>1,n(e[r])<t?i=r+1:s=r}else for(;i<s;)r=i+s>>>1,e[r]<t?i=r+1:s=r;return i}function _(e,t,n,r){var s=[];if(!e)return s;var o=-1,a=e.length,f=[];"function"==typeof t&&(r=n,n=t,t=i);for(n?r&&(n=u(n,r)):n=P;++o<a;)if(r=n(e[o],o,e),t?!o||f[f.length-1]!==r:0>L(f,r))f.push(r),s.push(e[o]);return s}function D(e,t){return qt||pt&&2<arguments.length?pt.call.apply(pt,arguments):l(e,t,ct.call(arguments,2))}function P(e){return e}function H(e){Cn(hn(e),function(
t){var r=s[t]=e[t];o.prototype[t]=function(){var e=[this.__wrapped__];return arguments.length&&ft.apply(e,arguments),e=r.apply(s,e),this.__chain__&&(e=new o(e),e.__chain__=n),e}})}var n=!0,r=null,i=!1,B,j,F,I,q="object"==typeof exports&&exports&&("object"==typeof global&&global&&global==global.global&&(e=global),exports),R=Array.prototype,U=Object.prototype,z=0,W=30,X=e._,V=/[-?+=!~*%&^<>|{(\/]|\[\D|\b(?:delete|in|instanceof|new|typeof|void)\b/,$=/&(?:amp|lt|gt|quot|#x27);/g,J=/\b__p\+='';/g,K=/\b(__p\+=)''\+/g
,Q=/(__e\(.*?\)|\b__t\))\+'';/g,G=/\w*$/,Y=/(?:__e|__t=)\(\s*(?![\d\s"']|this\.)/g,Z=RegExp("^"+(U.valueOf+"").replace(/[.*+?^=!:${}()|[\]\/\\]/g,"\\$&").replace(/valueOf|for [^\]]+/g,".+?")+"$"),et=/__token(\d+)__/g,tt=/[&<>"']/g,nt=/['\n\r\t\u2028\u2029\\]/g,rt="constructor hasOwnProperty isPrototypeOf propertyIsEnumerable toLocaleString toString valueOf".split(" "),it="__token",st="__",ot=[],ut=R.concat,at=U.hasOwnProperty,ft=R.push,lt=U.propertyIsEnumerable,ct=R.slice,ht=U.toString,pt=Z.test(
pt=ct.bind)&&pt,dt=Math.floor,vt=Z.test(vt=Array.isArray)&&vt,mt=e.isFinite,gt=Z.test(gt=Object.keys)&&gt,yt=Math.max,bt=Math.min,wt=Math.random,Et="[object Arguments]",St="[object Array]",xt="[object Boolean]",Tt="[object Date]",Nt="[object Number]",Ct="[object Object]",kt="[object RegExp]",Lt="[object String]",At=e.clearTimeout,Ot=e.setTimeout,Mt,_t,Dt,Pt=n;(function(){function e(){this.x=1}var t={0:1,length:1},n=[];e.prototype={valueOf:1,y:1};for(var r in new e)n.push(r);for(r in arguments)Pt=!r;Mt=4>
(n+"").length,Dt="x"!=n[0],_t=(n.splice.call(t,0,1),t[0])})(1);var Ht=!w(arguments),Bt="x"!=ct.call("x")[0],jt="xx"!="x"[0]+Object("x")[0];try{var Ft=("[object Object]",ht.call(e.document||0)==Ct)}catch(It){}var qt=pt&&/\n|Opera/.test(pt+ht.call(e.opera)),Rt=gt&&/^.+$|true/.test(gt+!!e.attachEvent),Ut=!qt,zt={};zt[xt]=zt[Tt]=zt["[object Function]"]=zt[Nt]=zt[Ct]=zt[kt]=i,zt[Et]=zt[St]=zt[Lt]=n;var Wt={};Wt[Et]=Wt["[object Function]"]=i,Wt[St]=Wt[xt]=Wt[Tt]=Wt[Nt]=Wt[Ct]=Wt[kt]=Wt[Lt]=n;var Xt={"&"
:"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#x27;"},Vt={"&amp;":"&","&lt;":"<","&gt;":">","&quot;":'"',"&#x27;":"'"},$t={"boolean":i,"function":n,object:n,number:i,string:i,"undefined":i,unknown:n},Jt={"\\":"\\","'":"'","\n":"n","\r":"r","	":"t","\u2028":"u2028","\u2029":"u2029"};s.templateSettings={escape:/<%-([\s\S]+?)%>/g,evaluate:/<%([\s\S]+?)%>/g,interpolate:/<%=([\s\S]+?)%>/g,variable:""};var Kt={a:"e,d,y",j:"e",q:"if(!d)d=i;else if(y)d=c(d,y)",i:"if(d(A,j,e)===false)return u"},Qt={j:"{}"
,q:"var q;if(typeof d!='function'){var hh=d;d=function(A){return A[hh]}}else if(y)d=c(d,y)",i:"q=d(A,j,e);(h.call(u,q)?u[q]++:u[q]=1)"},Gt={j:"true",i:"if(!d(A,j,e))return!u"},Yt={r:i,s:i,a:"n",j:"n",q:"for(var a=1,b=arguments.length;a<b;a++){if(k=arguments[a]){",i:"u[j]=A",e:"}}"},Zt={j:"[]",i:"d(A,j,e)&&u.push(A)"},en={q:"if(y)d=c(d,y)"},tn={i:{l:Kt.i}},nn={j:"",f:"if(!e)return[]",d:{b:"u=Array(l)",l:"u="+(Rt?"Array(l)":"[]")},i:{b:"u[j]=d(A,j,e)",l:"u"+(Rt?"[o]=":".push")+"(d(A,j,e))"}},rn={r:
i,a:"n,d,y",j:"{}",q:"var R=typeof d=='function';if(!R){var t=f.apply(E,arguments)}else if(y)d=c(d,y)",i:"if(R?!d(A,j,n):M(t,j)<0)u[j]=A"};Ht&&(w=function(e){return!!e&&!!at.call(e,"callee")});var sn=vt||function(e){return ht.call(e)==St};E(/x/)&&(E=function(e){return"[object Function]"==ht.call(e)});var on=$t.__proto__!=U?S:function(e,t){if(!e)return i;var n=e.valueOf,r="function"==typeof n&&(r=n.__proto__)&&r.__proto__;return r?e==r||e.__proto__==r&&(t||!w(e)):S(e)},un=c({a:"n",j:"[]",i:"u.push(j)"
}),an=c(Yt,{i:"if(u[j]==null)"+Yt.i}),fn=c(Yt),ln=c(Kt,en,tn,{r:i}),cn=c(Kt,en,tn),hn=c({r:i,a:"n",j:"[]",i:"if(S(A))u.push(j)",e:"u.sort()"}),pn=c({a:"n",j:"{}",i:"u[A]=j"}),dn=c({a:"A",j:"true",q:"var H=z.call(A),l=A.length;if(D[H]"+(Ht?"||O(A)":"")+"||(H==W&&l===+l&&S(A.splice)))return!l",i:{l:"return false"}}),vn=gt?function(e){var t=typeof e;return"function"==t&&lt.call(e,"prototype")?un(e):e&&$t[t]?gt(e):[]}:un,mn=c(Yt,{a:"n,cc,N",q:"var b,P,dd,ee,C=arguments,a=0;if(N==T){b=2;dd=C[3];ee=C[4]}else{b=C.length;dd=[];ee=[]}while(++a<b){if(k=C[a]){"
,i:"if((cc=A)&&((P=Q(cc))||T(cc))){var K=false,ff=dd.length;while(ff--)if(K=dd[ff]==cc)break;if(K){u[j]=ee[ff]}else{dd.push(cc);ee.push(A=(A=u[j])&&P?(Q(A)?A:[]):(T(A)?A:{}));u[j]=G(A,cc,T,dd,ee)}}else if(cc!=null)u[j]=cc"}),gn=c(rn),yn=c({a:"n",j:"[]",i:"u"+(Rt?"[o]=":".push")+"([j,A])"}),bn=c(rn,{q:"if(typeof d!='function'){var q,t=f.apply(E,arguments),l=t.length;for(j=1;j<l;j++){q=t[j];if(q in n)u[q]=n[q]}}else{if(y)d=c(d,y)",i:"if(d(A,j,n))u[j]=A",e:"}"}),wn=c({a:"n",j:"[]",i:"u.push(A)"}),En=
c({a:"e,gg",j:"false",o:i,d:{b:"if(z.call(e)==x)return e.indexOf(gg)>-1"},i:"if(A===gg)return true"}),Sn=c(Kt,Qt),xn=c(Kt,Gt),Tn=c(Kt,Zt),Nn=c(Kt,en,{j:"",i:"if(d(A,j,e))return A"}),Cn=c(Kt,en),kn=c(Kt,Qt,{i:"q=d(A,j,e);(h.call(u,q)?u[q]:u[q]=[]).push(A)"}),Ln=c(nn,{a:"e,U",q:"var C=w.call(arguments,2),R=typeof U=='function'",i:{b:"u[j]=(R?U:A[U]).apply(A,C)",l:"u"+(Rt?"[o]=":".push")+"((R?U:A[U]).apply(A,C))"}}),An=c(Kt,nn),On=c(nn,{a:"e,aa",i:{b:"u[j]=A[aa]",l:"u"+(Rt?"[o]=":".push")+"(A[aa])"}
}),Mn=c({a:"e,d,B,y",j:"B",q:"var V=arguments.length<3;if(y)d=c(d,y)",d:{b:"if(V)u=k[++j]"},i:{b:"u=d(u,A,j,e)",l:"u=V?(V=false,A):d(u,A,j,e)"}}),_n=c(Kt,Zt,{i:"!"+Zt.i}),Dn=c(Kt,Gt,{j:"false",i:Gt.i.replace("!","")}),Pn=c(Kt,Qt,nn,{i:{b:"u[j]={a:d(A,j,e),b:j,c:A}",l:"u"+(Rt?"[o]=":".push")+"({a:d(A,j,e),b:j,c:A})"},e:"u.sort(I);l=u.length;while(l--)u[l]=u[l].c"}),Hn=c(Zt,{a:"e,Z",q:"var t=[];J(Z,function(A,q){t.push(q)});var bb=t.length",i:"for(var q,Y=true,s=0;s<bb;s++){q=t[s];if(!(Y=A[q]===Z[q]))break}Y&&u.push(A)"
}),Bn=c({r:i,s:i,a:"n",j:"n",q:"var L=arguments,l=L.length;if(l>1){for(var j=1;j<l;j++)u[L[j]]=F(u[L[j]],u);return u}",i:"if(S(u[j]))u[j]=F(u[j],u)"});s.VERSION="0.7.0",s.after=function(e,t){return 1>e?t():function(){if(1>--e)return t.apply(this,arguments)}},s.bind=D,s.bindAll=Bn,s.chain=function(e){return e=new o(e),e.__chain__=n,e},s.clone=x,s.compact=function(e){var t=[];if(!e)return t;for(var n=-1,r=e.length;++n<r;)e[n]&&t.push(e[n]);return t},s.compose=function(){var e=arguments;return function(
){for(var t=arguments,n=e.length;n--;)t=[e[n].apply(this,t)];return t[0]}},s.contains=En,s.countBy=Sn,s.debounce=function(e,t,n){function i(){a=r,n||(o=e.apply(u,s))}var s,o,u,a;return function(){var r=n&&!a;return s=arguments,u=this,At(a),a=Ot(i,t),r&&(o=e.apply(u,s)),o}},s.defaults=an,s.defer=function(e){var n=ct.call(arguments,1);return Ot(function(){return e.apply(t,n)},1)},s.delay=function(e,n){var r=ct.call(arguments,2);return Ot(function(){return e.apply(t,r)},n)},s.difference=function(e){
var t=[];if(!e)return t;for(var n=-1,r=e.length,i=ut.apply(t,arguments),i=a(i,r);++n<r;)i(e[n])||t.push(e[n]);return t},s.escape=function(e){return e==r?"":(e+"").replace(tt,d)},s.every=xn,s.extend=fn,s.filter=Tn,s.find=Nn,s.first=C,s.flatten=k,s.forEach=Cn,s.forIn=ln,s.forOwn=cn,s.functions=hn,s.groupBy=kn,s.has=function(e,t){return e?at.call(e,t):i},s.identity=P,s.indexOf=L,s.initial=function(e,t,n){return e?ct.call(e,0,-(t==r||n?1:t)):[]},s.intersection=function(e){var t=[];if(!e)return t;var n
,r=arguments.length,i=[],s=-1,o=e.length;e:for(;++s<o;)if(n=e[s],0>L(t,n)){for(var u=1;u<r;u++)if(!(i[u]||(i[u]=a(arguments[u])))(n))continue e;t.push(n)}return t},s.invert=pn,s.invoke=Ln,s.isArguments=w,s.isArray=sn,s.isBoolean=function(e){return e===n||e===i||ht.call(e)==xt},s.isElement=function(e){return e?1===e.nodeType:i},s.isEmpty=dn,s.isEqual=T,s.isFinite=function(e){return mt(e)&&ht.call(e)==Nt},s.isFunction=E,s.isNaN=function(e){return ht.call(e)==Nt&&e!=+e},s.isNull=function(e){return e===
r},s.isObject=function(e){return e?$t[typeof e]:i},s.isUndefined=function(e){return e===t},s.keys=vn,s.last=function(e,t,n){if(e){var i=e.length;return t==r||n?e[i-1]:ct.call(e,-t||i)}},s.lastIndexOf=function(e,t,n){if(!e)return-1;var r=e.length;for(n&&"number"==typeof n&&(r=(0>n?yt(0,r+n):bt(n,r-1))+1);r--;)if(e[r]===t)return r;return-1},s.lateBind=function(e,t){return l(t,e,ct.call(arguments,2))},s.map=An,s.max=A,s.memoize=function(e,t){var n={};return function(){var r=t?t.apply(this,arguments)
:arguments[0];return at.call(n,r)?n[r]:n[r]=e.apply(this,arguments)}},s.merge=mn,s.min=function(e,t,n){var r=Infinity,i=r;if(!e)return i;var s=-1,o=e.length;if(!t){for(;++s<o;)e[s]<i&&(i=e[s]);return i}for(n&&(t=u(t,n));++s<o;)n=t(e[s],s,e),n<r&&(r=n,i=e[s]);return i},s.mixin=H,s.noConflict=function(){return e._=X,this},s.object=function(e,t){if(!e)return{};for(var n=-1,r=e.length,i={};++n<r;)t?i[e[n]]=t[n]:i[e[n][0]]=e[n][1];return i},s.omit=gn,s.once=function(e){var t,s=i;return function(){return s?
t:(s=n,t=e.apply(this,arguments),e=r,t)}},s.pairs=yn,s.partial=function(e){return l(e,ct.call(arguments,1))},s.pick=bn,s.pluck=On,s.random=function(e,t){return e==r&&t==r?wt():(e=+e||0,t==r&&(t=e,e=0),e+dt(wt()*((+t||0)-e+1)))},s.range=function(e,t,n){e=+e||0,n=+n||1,t==r&&(t=e,e=0);for(var i=-1,t=yt(0,Math.ceil((t-e)/n)),s=Array(t);++i<t;)s[i]=e,e+=n;return s},s.reduce=Mn,s.reduceRight=N,s.reject=_n,s.rest=O,s.result=function(e,t){if(!e)return r;var n=e[t];return E(n)?e[t]():n},s.shuffle=function(
e){if(!e)return[];for(var t,n=-1,r=e.length,i=Array(r);++n<r;)t=dt(wt()*(n+1)),i[n]=i[t],i[t]=e[n];return i},s.size=function(e){if(!e)return 0;var t=e.length;return t===+t?t:vn(e).length},s.some=Dn,s.sortBy=Pn,s.sortedIndex=M,s.tap=function(e,t){return t(e),e},s.template=function(e,t,n){n||(n={});var e=e+"",o,u;o=n.escape;var a=n.evaluate,f=n.interpolate,l=s.templateSettings,c=n=n.variable||l.variable;o==r&&(o=l.escape),a==r&&(a=l.evaluate||i),f==r&&(f=l.interpolate),o&&(e=e.replace(o,m)),f&&(e=e
.replace(f,y)),a!=B&&(B=a,I=RegExp("<e%-([\\s\\S]+?)%>|<e%=([\\s\\S]+?)%>"+(a?"|"+a.source:""),"g")),o=ot.length,e=e.replace(I,g),o=o!=ot.length,e="__p += '"+e.replace(nt,p).replace(et,h)+"';",ot.length=0,c||(n=j||"obj",o?e="with("+n+"){"+e+"}":(n!=j&&(j=n,F=RegExp("(\\(\\s*)"+n+"\\."+n+"\\b","g")),e=e.replace(Y,"$&"+n+".").replace(F,"$1__d"))),e=(o?e.replace(J,""):e).replace(K,"$1").replace(Q,"$1;"),e="function("+n+"){"+(c?"":n+"||("+n+"={});")+"var __t,__p='',__e=_.escape"+(o?",__j=Array.prototype.join;function print(){__p+=__j.call(arguments,'')}"
:(c?"":",__d="+n+"."+n+"||"+n)+";")+e+"return __p}";try{u=Function("_","return "+e)(s)}catch(d){throw d.source=e,d}return t?u(t):(u.source=e,u)},s.throttle=function(e,t){function n(){a=new Date,u=r,s=e.apply(o,i)}var i,s,o,u,a=0;return function(){var r=new Date,f=t-(r-a);return i=arguments,o=this,0>=f?(a=r,s=e.apply(o,i)):u||(u=Ot(n,f)),s}},s.times=function(e,t,n){var r=-1;if(n)for(;++r<e;)t.call(n,r);else for(;++r<e;)t(r)},s.toArray=function(e){if(!e)return[];var t=e.length;return t===+t?(Bt?ht.
call(e)==Lt:"string"==typeof e)?e.split(""):ct.call(e):wn(e)},s.unescape=function(e){return e==r?"":(e+"").replace($,b)},s.union=function(){for(var e=-1,t=[],n=ut.apply(t,arguments),r=n.length;++e<r;)0>L(t,n[e])&&t.push(n[e]);return t},s.uniq=_,s.uniqueId=function(e){var t=z++;return e?e+t:t},s.values=wn,s.where=Hn,s.without=function(e){var t=[];if(!e)return t;for(var n=-1,r=e.length,i=a(arguments,1,20);++n<r;)i(e[n])||t.push(e[n]);return t},s.wrap=function(e,t){return function(){var n=[e];return arguments
.length&&ft.apply(n,arguments),t.apply(this,n)}},s.zip=function(e){if(!e)return[];for(var t=-1,n=A(On(arguments,"length")),r=Array(n);++t<n;)r[t]=On(arguments,t);return r},s.all=xn,s.any=Dn,s.collect=An,s.detect=Nn,s.drop=O,s.each=Cn,s.foldl=Mn,s.foldr=N,s.head=C,s.include=En,s.inject=Mn,s.methods=hn,s.select=Tn,s.tail=O,s.take=C,s.unique=_,Cn({Date:Tt,Number:Nt,RegExp:kt,String:Lt},function(e,t){s["is"+t]=function(t){return ht.call(t)==e}}),o.prototype=s.prototype,H(s),o.prototype.chain=function(
){return this.__chain__=n,this},o.prototype.value=function(){return this.__wrapped__},Cn("pop push reverse shift sort splice unshift".split(" "),function(e){var t=R[e];o.prototype[e]=function(){var e=this.__wrapped__;return t.apply(e,arguments),_t&&e.length===0&&delete e[0],this.__chain__&&(e=new o(e),e.__chain__=n),e}}),Cn(["concat","join","slice"],function(e){var t=R[e];o.prototype[e]=function(){var e=t.apply(this.__wrapped__,arguments);return this.__chain__&&(e=new o(e),e.__chain__=n),e}}),typeof 
define=="function"&&typeof define.amd=="object"&&define.amd?(e._=s,define(function(){return s})):q?"object"==typeof module&&module&&module.exports==q?(module.exports=s)._=s:q._=s:e._=s})(this);
});

require.define("/lib/peer.js",function(require,module,exports,__dirname,__filename,process){// Generated by CoffeeScript 1.6.3
(function() {
  var DelegationHandler, DirectConnection, Peer, VarStorage, connectedPeerMethods, d, exports, prefixes, setCmds, _, _ref,
    __slice = [].slice,
    __indexOf = [].indexOf || function(item) { for (var i = 0, l = this.length; i < l; i++) { if (i in this && this[i] === item) return i; } return -1; };

  d = (exports = module.exports = require('./base')).d;

  _ref = require('./proto'), setCmds = _ref.setCmds, prefixes = _ref.prefixes, VarStorage = _ref.VarStorage;

  _ = require('./lodash.min');

  exports.Peer = Peer = (function() {
    function Peer(con) {
      var defaultHandler, peer;
      this.setConnection(con);
      this.inTransaction = false;
      this.changeListeners = {};
      this.treeListeners = {};
      this.valueListeners = {};
      this.queuedListeners = [];
      this.name = null;
      this.namePrefixPat = /^$/;
      this.varStorage = new VarStorage(this);
      peer = this;
      defaultHandler = this.varStorage.handlerFor;
      this.varStorage.handlerFor = function(key) {
        return defaultHandler.call(this, key.replace(peer.namePrefixPat, 'this/'));
      };
      this.pendingBlocks = [];
    }

    Peer.prototype.addConnection = function(con) {};

    Peer.prototype.afterConnect = function(block) {
      if (this.name) {
        return block();
      } else {
        return this.pendingBlocks.push(block);
      }
    };

    Peer.prototype.setConnection = function(con) {
      var _ref1, _ref2;
      this.con = con;
      if ((_ref1 = this.con) != null) {
        _ref1.setMaster(this);
      }
      this.verbose = ((_ref2 = this.con) != null ? _ref2.verbose : void 0) || (function() {});
      return this.verbose("ADDED CONNECTION: " + this.con + ", verbose: " + (this.verbose.toString()));
    };

    Peer.prototype.verbose = function() {};

    Peer.prototype.transaction = function(block) {
      this.inTransaction = true;
      block();
      this.inTransaction = false;
      return this.con.send();
    };

    Peer.prototype.send = function(batch) {
      return this.processBatch(this.con, batch);
    };

    Peer.prototype.get = function(key) {
      return this.varStorage.values[key];
    };

    Peer.prototype.listen = function() {
      var args;
      args = 1 <= arguments.length ? __slice.call(arguments, 0) : [];
      return this.queuedListeners.push(args);
    };

    Peer.prototype.name = function(n) {
      return this.addCmd(['name', n]);
    };

    Peer.prototype.value = function(key, cookie, isTree, callback) {
      this.grabTree(key, callback);
      return this.addCmd(['value', key, cookie, isTree]);
    };

    Peer.prototype.set = function(key, value, storage) {
      return this.addCmd((storage ? ['set', key, value, storage] : ['set', key, value]));
    };

    Peer.prototype.put = function(key, index, value) {
      return this.addCmd(['put', key, value, index]);
    };

    Peer.prototype.splice = function() {
      var key, spliceArgs;
      key = arguments[0], spliceArgs = 2 <= arguments.length ? __slice.call(arguments, 1) : [];
      return this.addCmd(['splice', key].concat(__slice.call(spliceArgs)));
    };

    Peer.prototype.removeFirst = function(key, value) {
      return this.addCmd(['removeFirst', key, value]);
    };

    Peer.prototype.removeAll = function(key, value) {
      return this.addCmd(['removeAll', key, value]);
    };

    Peer.prototype.manage = function(key, handler) {};

    Peer.prototype.processBatch = function(con, batch) {
      var block, cmd, k, v, _i, _j, _len, _len1, _ref1, _ref2, _results;
      if (batch[0][0] === 'set' && batch[0][1] === 'this/name') {
        this.name = batch[0][2];
        this.date = batch[0][3];
        for (k in connectedPeerMethods) {
          v = connectedPeerMethods[k];
          this[k] = v;
        }
        _ref1 = this.queuedListeners;
        for (_i = 0, _len = _ref1.length; _i < _len; _i++) {
          cmd = _ref1[_i];
          this.listen.apply(this, cmd);
        }
        this.queuedListeners = null;
        this.processBatch(con, batch.slice(1));
        _ref2 = this.pendingBlocks;
        _results = [];
        for (_j = 0, _len1 = _ref2.length; _j < _len1; _j++) {
          block = _ref2[_j];
          _results.push(block());
        }
        return _results;
      }
    };

    Peer.prototype.rename = function(newName) {
      var c, k, listen, newPath, oldName, oldPat, t, thisPat, v, _ref1, _ref2, _ref3;
      if (this.name !== newName) {
        newPath = "peer/" + newName;
        thisPat = new RegExp("^this(?=/|$)");
        oldName = (_ref1 = this.name) != null ? _ref1 : 'this';
        this.name = newName;
        this.varStorage.sortKeys();
        exports.renameVars(this.varStorage.keys, this.varStorage.values, this.varStorage.handlers, oldName, newName);
        t = {};
        _ref2 = this.treeListeners;
        for (k in _ref2) {
          v = _ref2[k];
          t[k.replace(thisPat, newPath)] = v;
        }
        this.treeListeners = t;
        c = {};
        _ref3 = this.changeListeners;
        for (k in _ref3) {
          v = _ref3[k];
          c[k.replace(thisPat, newPath)] = v;
        }
        this.changeListeners = c;
        listen = "peer/" + newName + "/listen";
        if (this.varStorage.values[listen]) {
          oldPat = new RegExp("^peer/" + oldName + "(?=/|$)");
          return this.varStorage.values[listen] = (k.replace(oldPat, newPath)).replace(thisPat, newPath);
        }
      }
    };

    Peer.prototype.sendTreeSets = function(sets, callback) {
      var k, msg, v, x, _i, _len, _results;
      _results = [];
      for (_i = 0, _len = sets.length; _i < _len; _i++) {
        msg = sets[_i];
        x = msg[0], k = msg[1], v = msg[2];
        _results.push(callback(k, v, null, msg, sets));
      }
      return _results;
    };

    Peer.prototype.tree = function(key, simulate, callback) {
      var idx, msg, msgs, prefix, _ref1;
      prefix = "^" + key + "(/|$)";
      idx = _.sortedIndex(this.varStorage.keys, key);
      if (simulate) {
        msgs = [];
        while ((_ref1 = this.varStorage.keys[idx]) != null ? _ref1.match(prefix) : void 0) {
          msgs.push(['set', this.varStorage.keys[idx], this.varStorage.values[this.varStorage.keys[idx]]]);
        }
        return this.sendTreeSets(msgs, callback);
      } else {
        msg = ['value', key, null, true];
        while (this.varStorage.keys[idx].match(prefix)) {
          msg.push(this.varStorage.keys[idx], this.varStorage.values[this.varStorage.keys[idx]]);
        }
        return callback(null, null, null, msg, [msg]);
      }
    };

    Peer.prototype.setsForTree = function(msg) {
      var i, key, _i, _len, _ref1, _results;
      _ref1 = msg.slice(4);
      _results = [];
      for (i = _i = 0, _len = _ref1.length; _i < _len; i = _i += 2) {
        key = _ref1[i];
        _results.push(['set', key, msg[i + 1]]);
      }
      return _results;
    };

    Peer.prototype.grabTree = function(key, callback) {
      if (!this.treeListeners[key]) {
        this.treeListeners[key] = [];
      }
      return this.treeListeners[key].push(callback);
    };

    Peer.prototype.addCmd = function(cmd) {
      this.con.addCmd(cmd);
      if (!this.inTransaction) {
        return this.con.send();
      }
    };

    Peer.prototype.disconnect = function() {
      return this.con.close();
    };

    Peer.prototype.listenersFor = function(key) {
      var _this = this;
      return _.flatten(_.map(prefixes(key), function(k) {
        return _this.changeListeners[k] || [];
      }));
    };

    Peer.prototype.handleDelegation = function(name, num, cmd) {
      var _this = this;
      return this.varStorage.handle(cmd, (function(type, msg) {
        return _this.sendCmd(['error', type, msg]);
      }), function() {
        return _this.sendCmd(['response', num, cmd]);
      });
    };

    Peer.prototype.sendCmd = function(cmd) {
      this.con.addCmd(cmd);
      return this.con.send();
    };

    Peer.prototype.addHandler = function(path, obj) {
      return this.varStorage.addHandler(path, obj);
    };

    Peer.prototype.personalize = function(path) {
      return path.replace(new RegExp('^this(?=\/|$)'), "peer/" + this.name);
    };

    return Peer;

  })();

  connectedPeerMethods = {
    processBatch: function(con, batch) {
      var block, cb, cmd, dcmd, index, key, name, num, numKeys, value, x, _i, _j, _k, _l, _len, _len1, _len2, _len3, _ref1, _ref2;
      this.verbose("PEER BATCH: " + (JSON.stringify(batch)));
      numKeys = this.varStorage.keys.length;
      for (_i = 0, _len = batch.length; _i < _len; _i++) {
        cmd = batch[_i];
        name = cmd[0], key = cmd[1], value = cmd[2], index = cmd[3];
        if (key.match(this.namePrefixPat)) {
          key = key.replace(this.namePrefixPat, 'this/');
        }
        if (__indexOf.call(setCmds, name) >= 0 && !this.varStorage.contains(key)) {
          this.varStorage.keys.push(key);
        }
        switch (name) {
          case 'error':
            console.log("ERROR '" + key + "': value");
            break;
          case 'request':
            this.verbose("GOT REQUEST: " + (JSON.stringify(cmd)) + ", batch: " + (JSON.stringify(batch)));
            x = cmd[0], name = cmd[1], num = cmd[2], dcmd = cmd[3];
            this.handleDelegation(name, num, dcmd);
            break;
          default:
            this.varStorage.handle(cmd, (function(type, msg) {
              return console.log("Error, '" + type + "': " + msg);
            }), function() {});
        }
      }
      if (numKeys !== this.varStorage.keys.length) {
        this.varStorage.keys.sort();
      }
      for (_j = 0, _len1 = batch.length; _j < _len1; _j++) {
        cmd = batch[_j];
        name = cmd[0], key = cmd[1], value = cmd[2], index = cmd[3];
        if (key.match(this.namePrefixPat)) {
          key = key.replace(this.namePrefixPat, 'this/');
        }
        if (name === 'set' && key === 'this/name') {
          this.name = value;
          this.namePrefixPat = new RegExp("^peer/" + value + "/");
        }
        if (__indexOf.call(setCmds, name) >= 0) {
          _ref1 = this.listenersFor(key);
          for (_k = 0, _len2 = _ref1.length; _k < _len2; _k++) {
            block = _ref1[_k];
            block(key, this.varStorage.values[key], cmd, batch);
          }
        } else if (name === 'value' && this.treeListeners[key]) {
          _ref2 = this.treeListeners[key];
          for (_l = 0, _len3 = _ref2.length; _l < _len3; _l++) {
            cb = _ref2[_l];
            cb(cmd, batch);
          }
          delete this.treeListeners[key];
        }
      }
      return null;
    },
    listen: function(key, simulateSetsForTree, noChildren, callback) {
      var _ref1,
        _this = this;
      key = key.replace(/^this\//, "peer/" + this.name + "/");
      if (typeof simulateSetsForTree === 'function') {
        noChildren = simulateSetsForTree;
        simulateSetsForTree = false;
      }
      if (typeof noChildren === 'function') {
        callback = noChildren;
        noChildren = false;
      }
      if (noChildren) {
        callback = function(changedKey, value, oldValue, cmd, batch) {
          if (key === changedKey) {
            return callback(changedKey, value, oldValue, cmd, batch);
          }
        };
      }
      if (!callback) {
        _ref1 = [null, simulateSetsForTree], simulateSetsForTree = _ref1[0], callback = _ref1[1];
      }
      if (!this.changeListeners[key]) {
        this.changeListeners[key] = [];
        this.grabTree(key, function(msg, batch) {
          if (simulateSetsForTree) {
            _this.sendTreeSets(_this.setsForTree(msg), callback);
          } else {
            callback(key, (msg[4] === key ? msg[5] : null), null, msg, batch);
          }
          return _this.changeListeners[key].push(callback);
        });
        return this.splice("this/listen", -1, 0, key);
      } else {
        return this.tree(key, simulateSetsForTree, callback);
      }
    }
  };

  exports.createDirectPeer = function(xus, peerFactory) {
    var ctx, peer, peerConnection, xusConnection;
    ctx = {
      connected: true,
      server: xus
    };
    xusConnection = new DirectConnection;
    peerConnection = new DirectConnection;
    peerConnection.verbose = xusConnection.verbose = xus.verbose;
    peer = (peerFactory != null ? peerFactory : function(con) {
      return new Peer(con);
    })(peerConnection);
    peerConnection.connect(xusConnection, xus, ctx);
    xusConnection.connect(peerConnection, peer, ctx);
    xus.addConnection(xusConnection);
    return peer;
  };

  DirectConnection = (function() {
    function DirectConnection() {
      this.q = [];
    }

    DirectConnection.prototype.connect = function(otherConnection, otherMaster, ctx) {
      this.otherConnection = otherConnection;
      this.otherMaster = otherMaster;
      this.ctx = ctx;
    };

    DirectConnection.prototype.isConnected = function() {
      return this.ctx.connected;
    };

    DirectConnection.prototype.close = function() {
      this.ctx.connected = false;
      return this.q = this.otherConnection.q = null;
    };

    DirectConnection.prototype.addCmd = function(cmd) {
      return this.q.push(cmd);
    };

    DirectConnection.prototype.send = function() {
      var q, _ref1;
      if (this.ctx.connected && this.q.length) {
        this.ctx.server.verbose("" + (d(this)) + " SENDING " + this.name + ", " + (JSON.stringify(this.q)));
        _ref1 = [this.q, []], q = _ref1[0], this.q = _ref1[1];
        return this.otherMaster.processBatch(this.otherConnection, q);
      }
    };

    DirectConnection.prototype.setMaster = function() {};

    return DirectConnection;

  })();

  DelegationHandler = (function() {
    function DelegationHandler(peer) {
      this.peer = peer;
      this.values = {};
    }

    DelegationHandler.prototype.value = function(reqId, cmd) {};

    DelegationHandler.prototype.set = function(reqId, cmd) {};

    DelegationHandler.prototype.put = function(reqId, cmd) {};

    DelegationHandler.prototype.splice = function(reqId, cmd) {};

    DelegationHandler.prototype.removeFirst = function(reqId, cmd) {};

    DelegationHandler.prototype.removeAll = function(reqId, cmd) {};

    return DelegationHandler;

  })();

}).call(this);

/*
//@ sourceMappingURL=peer.map
*/

});

require.define("/lib/browser.js",function(require,module,exports,__dirname,__filename,process){// Generated by CoffeeScript 1.6.3
(function() {
  var ProxyMux, WebSocketConnection, exports, log, _, _ref;

  window.Xus = exports = module.exports = require('./base');

  require('./proto');

  _ref = require('./transport'), log = _ref.log, ProxyMux = _ref.ProxyMux, WebSocketConnection = _ref.WebSocketConnection;

  require('./peer');

  window._ = _ = require('./lodash.min');

  if (window.MozWebSocket) {
    window.WebSocket = window.MozWebSocket;
  }

  exports.xusToProxy = function(xus, url, verbose) {
    var proxy, sock;
    proxy = new ProxyMux(xus);
    proxy.mainDisconnect = function(con) {
      console.log("Disconnecting mux connection and closing");
      window.open('', '_self', '');
      return window.close();
    };
    if (verbose != null) {
      proxy.verbose = log;
    }
    sock = new WebSocket(url);
    return sock.onopen = function() {
      return new WebSocketConnection(proxy, sock);
    };
  };

}).call(this);

/*
//@ sourceMappingURL=browser.map
*/

});
require("/lib/browser.js");
})();
