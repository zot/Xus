// Generated by CoffeeScript 1.3.3
(function() {
  var $, Connection, Server, caresAbout, cmds, error_bad_message, error_bad_storage_mode, error_variable_not_array, error_variable_not_object, exports, prefixes, setCmds, storageModes, storage_memory, storage_permanent, storage_transient, warning_no_storage, _,
    __indexOf = [].indexOf || function(item) { for (var i = 0, l = this.length; i < l; i++) { if (i in this && this[i] === item) return i; } return -1; };

  exports = module.exports;

  $ = require('./jquery-1.7.2.min');

  _ = require('./lodash');

  console.log("jquery: " + jQuery);

  cmds = ['connect', 'set', 'put', 'insert', 'remove', 'removeFirst', 'removeAll'];

  setCmds = ['set', 'put', 'insert', 'removeFirst', 'removeAll'];

  warning_no_storage = 'warning_no_storage';

  error_bad_message = 'error_bad_message';

  error_bad_storage_mode = 'errror_bad_storage_mode';

  error_variable_not_object = 'error_variable_not_object';

  error_variable_not_array = 'error_variable_not_array';

  storage_memory = 'memory';

  storage_transient = 'transient';

  storage_permanent = 'permanent';

  storageModes = [storage_transient, storage_memory, storage_permanent];

  exports.Connection = Connection = (function() {

    function Connection() {
      this.q = [];
      this.listening = {};
    }

    Connection.prototype.setName = function(name) {
      this.name = name;
      this.peerPath = "peers/" + name;
      return this.listenPath = "" + (this.peerPath / listeners);
    };

    Connection.prototype.connected = true;

    return Connection;

  })();

  exports.Server = Server = (function() {

    function Server() {}

    Server.prototype.connections = [];

    Server.prototype.peers = {};

    Server.prototype.values = {};

    Server.prototype.keys = [];

    Server.prototype.newKeys = false;

    Server.prototype.oldListens = null;

    Server.prototype.storageModes = {};

    Server.prototype.processMessages = function(con, msgs) {
      var msg, _i, _j, _len, _len1, _ref, _results;
      for (_i = 0, _len = msgs.length; _i < _len; _i++) {
        msg = msgs[_i];
        this.processMsg(con, msg, msg);
      }
      if (this.newKeys) {
        this.newKeys = false;
        this.keys.sort();
      }
      if (this.oldListens) {
        this.newListens = false;
        this.setListens(con, oldListens);
        this.oldListens = null;
      }
      _ref = this.connections;
      _results = [];
      for (_j = 0, _len1 = _ref.length; _j < _len1; _j++) {
        con = _ref[_j];
        _results.push(dump(con));
      }
      return _results;
    };

    Server.prototype.processMsg = function(con, _arg, msg) {
      var isSetter, key, name, _i, _len, _ref;
      name = _arg[0], key = _arg[1];
      if (con.connected) {
        if (__indexOf.call(cmds, name) >= 0) {
          isSetter = __indexOf.call(setCmds, name) >= 0;
          if (isSetter && key.match('^peers/[^/]+/listen$')) {
            this.oldListens = this.oldListens || this.values[key];
          }
          if (this[name](con, msg)) {
            if (isSetter) {
              _ref = this.relevantConnections(prefixes(key));
              for (_i = 0, _len = _ref.length; _i < _len; _i++) {
                con = _ref[_i];
                con.q.push(msg);
              }
              if (this.storageModes[key] === storage_permanent) {
                return this.store(con, key, value);
              }
            }
          }
        } else {
          return this.disconnect(con, error_bad_message, "Bad message: " + msg);
        }
      }
    };

    Server.prototype.relevantConnections = function(keyPrefixes) {
      return _.filter(this.connections, function(con) {
        return caresAbout(con, keyPrefixes);
      });
    };

    Server.prototype.addPeer = function(con, name) {
      this.peers[name] = con;
      return con.name = name;
    };

    Server.prototype.disconnect = function(con, errorType, msg) {
      var idx;
      idx = this.connections.indexOf(con);
      if (idx > -1) {
        this.connections.splice(idx, 1);
        if (con.name) {
          peers[con.name] = null;
        }
        error(con, errorType, msg);
        con.dump();
        this.primDisconnect;
      }
      return false;
    };

    Server.prototype.setListens = function(con, listening) {
      var old, path, _i, _j, _len, _len1, _results;
      old = con.listening;
      con.listening = {};
      for (_i = 0, _len = listening.length; _i < _len; _i++) {
        path = listening[_i];
        con.listening[path + '/'] = true;
      }
      _results = [];
      for (_j = 0, _len1 = listening.length; _j < _len1; _j++) {
        path = listening[_j];
        if (_.all(prefixes(path), (function(p) {
          return !old[p];
        }))) {
          this.sendAll(con, path);
        }
        _results.push(old[path + '/'] = true);
      }
      return _results;
    };

    Server.prototype.error = function(con, errorType, msg) {
      con.q.push(['error', errorType, msg]);
      return false;
    };

    Server.prototype.store = function(con, key, value) {
      return this.error(con, warning_no_storage, "Can't store " + key + " = " + (JSON.stringify(value)) + ", because no storage is configured");
    };

    Server.prototype["delete"] = function(con, key) {
      return this.error(con, warning_no_storage, "Can't delete " + key + ", because no storage is configured");
    };

    Server.prototype.sendAll = function(con, path) {
      return this.error(con, warning_no_storage, "Can't send data for " + path + " because no storage is configured");
    };

    Server.prototype.connect = function(con, _arg) {
      var name, x;
      x = _arg[0], name = _arg[1];
      if (!name) {
        this.disconnect(con, "No peer name");
      } else if (this.peers[name]) {
        this.disconnect(con, "Duplicate peer name: " + name);
      } else {
        this.addPeer(con, name);
      }
      return true;
    };

    Server.prototype.set = function(con, _arg) {
      var key, storageMode, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2], storageMode = _arg[3];
      if (storageMode && storageModes.indexOf(storageMode) === -1) {
        return this.error(con, error_bad_storage_mode, "" + storageMode + " is not a valid storage mode");
      } else {
        if (storageMode && storageMode !== this.storageModes[key] && this.storageModes[key] === storage_permanent) {
          this["delete"](con, key);
        }
        if ((storageMode || this.storageModes[key]) !== storage_transient) {
          if (!this.storageModes[key]) {
            storageMode = storageMode || storage_memory;
            this.keys.push(key);
            this.newKeys = true;
          }
          this.values[key] = value;
        }
        if (storageMode) {
          this.storageModes[key] = storageMode;
        }
        return true;
      }
    };

    Server.prototype.put = function(con, _arg) {
      var index, key, value, x;
      x = _arg[0], key = _arg[1], index = _arg[2], value = _arg[3];
      if (!this.values[key] || typeof this.values[key] !== 'object') {
        return this.disconnect(con, error_variable_not_object, "Can't put with " + key + " because it is not an object");
      } else {
        this.values[key][index] = value;
        return true;
      }
    };

    Server.prototype.insert = function(con, _arg) {
      var index, key, value, x;
      x = _arg[0], key = _arg[1], index = _arg[2], value = _arg[3];
      if (!(this.values[key] instanceof Array)) {
        return this.disonnect(con, error_variable_not_array, "Can't insert into " + key + " because it is not an array");
      } else {
        if (index === -1) {
          this.values[key].push(value);
        } else {
          this.values[key].splice(index, 0, value);
        }
        return true;
      }
    };

    Server.prototype.removeFirst = function(con, _arg) {
      var idx, key, val, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2];
      if (!(this.values[key] instanceof Array)) {
        return this.disconnect(con, error_variable_not_array, "Can't insert into " + key + " because it is not an array");
      } else {
        val = this.values[key];
        idx = val.indexOf(value);
        if (idx > -1) {
          val.splice(idx, 1);
        }
        return true;
      }
    };

    Server.prototype.removeAll = function(con, _arg) {
      var idx, key, val, value, x;
      x = _arg[0], key = _arg[1], value = _arg[2];
      if (!(this.values[key] instanceof Array)) {
        return this.disconnect(con, error_variable_not_array, "Can't insert into " + key + " because it is not an array");
      } else {
        val = this.values[key];
        while ((idx = val.indexOf(value)) > -1) {
          val.splice(idx, 1);
        }
        return true;
      }
    };

    return Server;

  })();

  caresAbout = function(con, keyPrefixes) {
    return _.any(keyPrefixes, function(p) {
      return con.listening[p] === true;
    });
  };

  prefixes = function(key) {
    var result, splitKey;
    result = [];
    splitKey = _without(key.split('/'), '');
    while (splitKey.length) {
      result.push(splitKey.join('/'));
      splitKey.pop();
    }
    return result;
  };

  _.search = function(key, arr) {
    var left, mid, right;
    left = 0;
    right = arr.length - 1;
    while (left < right) {
      mid = Math.floor((left + right) / 2);
      if (arr[mid] === key) {
        left = right = mid;
      }
      if (arr[mid] > key) {
        right = mid;
      } else {
        left = mid + 1;
      }
    }
    return left;
  };

}).call(this);