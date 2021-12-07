// XUS
// Copyright (C) 2021, Bill Burdick
// License: ZLIB license

import {d, log} from './base'
//import {} from './transport'
//import {Peer, createDirectPeer} from './peer'
declare class Peer {constructor(...args)}
declare function createDirectPeer(...args)

////
//
// The Xus protocol
//
// Most of the messages change values for Xus' keys
//
// Standard keys
//
// this/** -- equivalent to peer/PEER_NAME/*
//
// peer/X/listen -- list of paths that peer X is listening to
// peer/X/name -- the name of the peer (whatever X is)
//
////

////
// cmds is a list of commands a peer can send
//
// Response is a special command that responds when Xus sends it a 'request' command
//
// Request format: ['request', peerName, requestId, cmd]
// Response format: ['response', requestId, cmd]
////

export type cmd =
  //{type: 'def', parent: string | number, id: string | number, meta: any} |
  //{type: 'set', id: string | number, value: any} |
  //{type: 'setLength', id: string | number, value: number} | 
  //{type: 'remove', id: string | number}
  {type: 'response' } |
  {type: 'value' } |
  {type: 'set' } |
  {type: 'put' } |
  {type: 'splice' } |
  {type: 'removeFirst' } |
  {type: 'removeAll' } |
  {type: 'removeTree' } |
  {type: 'removedump' }

export const setCmds = ['set', 'put', 'splice', 'removeFirst', 'removeAll', 'removeTree']
export const cmds = ['response', 'value', ...setCmds, 'dump']

////
// Commands
//
// name name -- set the peer name to a unique name
//
// value cookie tree key -- fetch the value or the tree (if tree is true) for a key
//                       -- sends cmd back, with values added: ["get", cookie, tree, key, k1, v1, ...]
//
// -- commands that change data, they all start with key, value --
//
// set key value [storageMode]
//   set the value of a key and optionally change its storage mode
//
// put key value index
//
// insert key value index -- negative indexes start at the position right after the end
//                        -- so index for a negative is length + 1 + index
//
// removeFirst key value
//   remove the first occurance of value in the key's array
//
// removeAll key value
//   remove all occurances of value in the key's array
//
// removeTree key
//   removes key and all of its children
//
// dump
//   if server is running in diagnostic mode, dump fetches a value cmd containing all known data
////

////
// ERROR TYPES
////

// warning_no_storage doesn't disconnect, but the changes are only affect memory
export const warning_no_storage = 'warning_no_storage'
// warning_bad_peer_request doesn't disconnect, but indicates a problem with a peer request
export const warning_peer_request = 'warning_peer_request'

// errors cause disconnect
export const error_bad_message = 'error_bad_message'
export const error_bad_storage_mode = 'error_bad_storage_mode'
export const error_variable_not_object = 'error_variable_not_object'
export const error_variable_not_array = 'error_variable_not_array'
export const error_duplicate_peer_name = 'error_duplicate_peer_name'
export const error_private_variable = 'error_private_variable'
export const error_bad_master = 'error_bad_master'
export const error_bad_peer_request = 'error_bad_peer_request'
export const error_bad_connection = 'error_bad_connection'

export type errorType = 'error_bad_message' |
  'error_bad_storage_mode' |
  'error_variable_not_object' |
  'error_variable_not_array' |
  'error_duplicate_peer_name' |
  'error_private_variable' |
  'error_bad_master' |
  'error_bad_peer_request' |
  'error_bad_connection'

////
// STORAGE MODES FOR VARIABLES
////

// memory: this is the default mode -- values are just stored in memory
export const storage_memory = 'memory'
// transient: new listeners won't get values for this variable
export const storage_transient = 'transient'
// permanent: values are stored in permanent storage, like a database
export const storage_permanent = 'permanent'
// peer: 'value' and change commands are delegated to the peer that owns them
// only legal for public peer variables
// note that the peer has the power to disconnect the requester if it returns an error
export const storage_peer = 'peer'

export const storageModes = [storage_transient, storage_memory, storage_permanent, storage_peer];

/**
 * SERVER CLASS -- Xus server objects understand the Xus protocol
 *
 * connections: an array of objects representing Xus connections
 *
 * each connection has a 'xus' object with information and operations about the connection
 *   isConnected(): boolean indicating whether the peer is still connected
 *   name: the peer name
 *   q: the message queue
 *   listening: the variables it's listening to
 *   send(): send the message queue to the connection
 *   disconnect(): disconnect the connection
 */
export class Server {
  newKeys = false
  anonymousPeerCount = 0
  connections: any[]
  peers: object
  varStorage: VarStorage;
  storageModes: object // keys and their storage modes
  linksToPeers: object // key=> {peerName: true...}
  changedLinks = null;
  pendingRequests: object;
  pendingRequestNum = 0;
  nextBatch: any[];
  newListens = false
  newConLinks = false
  master: any
  diag = false

  constructor(public name: string) {
    console.log("NEW XUS SERVER: #{this}")
    this.connections = []
    this.peers = {}
    this.varStorage = new VarStorage(this)
    this.storageModes = {} // keys and their storage modes
    this.linksToPeers = {} // key=> {peerName: true...}
    this.changedLinks = null
    this.pendingRequests = {}
    this.pendingRequestNum = 0
  }

  verbose(...args: any[]) {}

  createPeer(peerFactory) {
    return createDirectPeer(this, peerFactory);
  }

  newPeer() {
    return this.createPeer(function(con) {
      return new Peer(con);
    });
  }

  processBatch(con, batch, nolinks) {
    var c, j, len, len1, m, msg, ref, results;
    while (batch.length) {
      this.nextBatch = [];
      for (j = 0, len = batch.length; j < len; j++) {
        msg = batch[j];
        this.verbose(`RECEIVED ${JSON.stringify(msg)}`);
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
    ref = this.connections;
    results = [];
    for (m = 0, len1 = ref.length; m < len1; m++) {
      c = ref[m];
      results.push(c != null ? c.send() : void 0);
    }
    return results;
  }

  processMsg(con, [name], msg, noLinks) {
    var isMyPeerKey, tmpMsg, x1, x2;
    //console.log "@@@@@"
    //console.log "*** processMsg: #{msg}"
    if (con.isConnected()) {
      if (cmds.indexOf(name) >= 0) {
        if (name === 'response') {
          [x1, x2, tmpMsg] = msg;
        } else {
          tmpMsg = msg;
        }
        let [x, key, value] = tmpMsg;
        key = key != null ? key : '';
        if (typeof key === 'string') {
          key = tmpMsg[1] = key.replace(new RegExp('^this/'), `${con.peerPath}/`);
        }
        isMyPeerKey = key.match(`^${con.peerPath}/`);
        if (!isMyPeerKey && !noLinks && key.match("^peer/") && !key.match("^.*/public(/|$)")) {
          return this.primDisconnect(con, error_private_variable, `Error, ${con.name} (key = ${key}, peerPath = ${con.peerPath}, match = ${key.match(`^${con.peerPath}`)}) attempted to change another peer's private variable: '${key}' in message: ${JSON.stringify(msg)}`);
        } else {
          if (isMyPeerKey) {
            switch (key) {
              case con.listenPath:
                this.newListens = true;
                break;
              case !noLinks && con.linksPath:
                this.verbose(`Setting links: ${msg}`);
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
            this.verbose(`EXECUTING: ${JSON.stringify(msg)}`);
            return this[name](con, msg, () => {
              var c, j, len, ref;
              //@verbose "EXECUTED: #{JSON.stringify msg}"
              if (setCmds.indexOf(name) >= 0) {
                this.verbose(`CMD: ${JSON.stringify(msg)}, VALUE: ${JSON.stringify(this.varStorage.values[key])}`);
                if (key === con.namePath) {
                  this.setName(con, msg[2]);
                } else if (key === con.masterPath) {
                  this.setMaster(con, msg[2]);
                }
                ref = this.relevantConnections(prefixes(key));
                for (j = 0, len = ref.length; j < len; j++) {
                  c = ref[j];
                  c.addCmd(msg);
                }
                if (this.varStorage.keyInfo[key] === storage_permanent) {
                  return this.store(con, key, value);
                }
              }
            });
          }
        }
      } else {
        return this.primDisconnect(con, error_bad_message, `Unknown command, '${name}' in message: ${JSON.stringify(msg)}`);
      }
    } else if (noLinks) {
      let [x, key] = msg;

      if (!key.match(new RegExp(`^this|^peer/${con.peerPath}/`))) {
        return this[name](con, msg, () => {
          var c, j, len, ref, results;
          this.verbose(`EXECUTED: ${msg}`);
          ref = this.relevantConnections(prefixes(key));
          results = [];
          for (j = 0, len = ref.length; j < len; j++) {
            c = ref[j];
            results.push(c.addCmd(msg));
          }
          return results;
        });
      }
    }
  }

  shouldDelegate(con, key) {
    var match;
    if (this.isPeerVar(key)) {
      match = key.match(/^peer\/([^\/]+)\//);
      return this.peers[match[1]] !== con;
    } else {
      return false;
    }
  }

  isPeerVar(key) {
    return prefixes(key).find((k) => this.varStorage.keyInfo[k] === storage_peer)
  }

  relevantConnections(keyPrefixes) {
    return this.connections.filter(c=> caresAbout(c, keyPrefixes))
  }

  setConName(con, name) {
    con.name = name;
    con.peerPath = `peer/${name}`;
    con.namePath = `${con.peerPath}/name`;
    con.listenPath = `${con.peerPath}/listen`;
    con.linksPath = `${con.peerPath}/links`;
    con.masterPath = `${con.peerPath}/master`;
    con.requests = {};
    this.peers[name] = con;
    return this.varStorage.setKey(con.namePath, name);
  }

  addConnection(con) {
    this.verbose("Xus add connection");
    this.setConName(con, `@anonymous-${this.anonymousPeerCount++}`);
    con.listening = {};
    con.links = {};
    this.connections.push(con);
    this.varStorage.setKey(con.listenPath, []);
    con.date = new Date().getTime();
    con.addCmd(['set', 'this/name', con.name, con.date]);
    return con.send();
  }

  renamePeerKeys(con, oldName, newName) {
    var l, newCL, newPrefix, newVL, oldPrefixPat;
    [this.varStorage.keys] = renameVars(this.varStorage.keys, this.varStorage.values, this.varStorage.handlers, oldName, newName);
    newCL = {};
    newVL = [];
    newPrefix = `peer/${newName}`;
    oldPrefixPat = new RegExp(`^peer/${oldName}(?=/|$)`);
    for (l in con.listening) {
      l = l.replace(oldPrefixPat, newPrefix);
      newCL[l] = true;
      newVL.push(l);
    }
    con.listening = newCL;
    newVL.sort();
    return this.varStorage.setKey(`${newPrefix}/listen`, newVL);
  }

  disconnect(con, errorType, msg) {
    this.verbose(`${this} DISCONNECT ${con}`);
    this.primDisconnect(con, errorType, msg);
    if (this.nextBatch) {
      return this.processBatch(con, this.nextBatch, true);
    }
  }

  toString() {
    return `Server [${this.name}]`;
  }

  primDisconnect(con, errorType, msg) {
    var batch, idx, j, key, len, len1, m, num, peerKey, peerKeys, ref;
    if (!con.disconnected) {
      con.disconnected = true;
      idx = this.connections.indexOf(con);
      batch = [];
      if (idx > -1) {
        //@varStorage.setKey con.linksPath, []
        batch = this.setLinks(con);
        peerKey = con.peerPath;
        peerKeys = this.varStorage.keysForPrefix(peerKey);
        if (con.name) {
          delete this.peers[con.name];
        }
        // this could be more efficient, but does it matter?
        for (j = 0, len = peerKeys.length; j < len; j++) {
          key = peerKeys[j];
          this.varStorage.removeKey(key);
        }
        this.connections.splice(idx, 1);
        if (msg) {
          this.error(con, errorType, msg);
        }
        con.send();
        con.close();
        ref = con.requests;
        for (m = 0, len1 = ref.length; m < len1; m++) {
          num = ref[m];
          delete this.pendingRequests[num];
        }
        if (con === this.master) {
          this.exit();
        }
      }
    }
    // return false becuase this is called by messages, so a faulty message won't be forwarded
    return false;
  }

  exit() {
    return console.log("No custom exit function");
  }

  setListens(con) {
    var conPath, finalListen, j, len, old, path, ref, thisPath;
    thisPath = new RegExp("^this/");
    conPath = `${con.peerPath}/`;
    old = con.listening;
    con.listening = {};
    finalListen = [];
    ref = this.varStorage.values[con.listenPath];
    for (j = 0, len = ref.length; j < len; j++) {
      path = ref[j];
      if (path.match("^peer/") && !path.match("^peer/[^/]+/public") && !path.match(`^${con.peerPath}`)) {
        //this.primDisconnect(con, error_private_variable, `Error, ${con.name} attempted to listen to a peer's private variables in message: ${JSON.stringify(msg)}`);
        this.primDisconnect(con, error_private_variable, `Error, ${con.name} attempted to listen to a peer's private variables`);
        return;
      }
      path = path.replace(thisPath, conPath);
      finalListen.push(path);
      con.listening[path] = true;
      if (prefixes(path).every(p=> !old[p])) {
        this.sendTree(con, path, ['value', path, null, true]);
      }
      old[path] = true;
    }
    return this.varStorage.setKey(con.listenPath, finalListen);
  }

  setLinks(con) {
    var j, l, len, old, ref, ref1, results;
    this.verbose(`PRIM SET LINKS, LINKS PATH: ${con.linksPath}, NEW ${JSON.stringify(this.varStorage.values[con.linksPath])}, OLD: ${JSON.stringify(con.links)}`);
    old = {};
    for (l in con.links) {
      old[l] = true;
    }
    ref1 = (ref = this.varStorage.values[con.linksPath]) != null ? ref : [];
    for (j = 0, len = ref1.length; j < len; j++) {
      l = ref1[j];
      if (!old[l]) {
        this.addLink(con, l);
      } else {
        delete old[l];
      }
    }
    results = [];
    for (l in old) {
      results.push(this.removeLink(con, l));
    }
    return results;
  }

  processLinks(con, changed) {
    var j, l, len, link, old, p, ref, results;
    results = [];
    for (link in changed) {
      old = {};
      for (l in this.linksToPeers[link]) {
        old[l] = true;
      }
      ref = this.varStorage.values[link];
      for (j = 0, len = ref.length; j < len; j++) {
        p = ref[j];
        if (!old[p]) {
          this.addLink(this.peers[p], link);
        } else {
          delete old[p];
        }
      }
      results.push((function() {
        var results1;
        results1 = [];
        for (p in old) {
          results1.push(this.removeLink(this.peers[p], link));
        }
        return results1;
      }).call(this));
    }
    return results;
  }

  addLink(con, link) {
    this.verbose(`ADDING LINK: ${JSON.stringify(link)}`);
    if (!this.linksToPeers[link]) {
      this.linksToPeers[link] = {};
    }
    this.linksToPeers[link][con.name] = con.links[link] = true;
    this.nextBatch.push(['splice', link, -1, 0, con.name]);
    return this.nextBatch.push(['splice', `peer/${con.name}/links`, -1, 0, link]);
  }

  removeLink(con, link) {
    var ref;
    this.verbose(`REMOVING LINK: ${JSON.stringify(link)}`);
    delete con.links[link];
    if ((ref = this.linksToPeers[link]) != null) {
      delete ref[con.name];
    }
    if (this.linksToPeers[link] && !this.linksToPeers[link].length) {
      delete this.linksToPeers[link];
    }
    this.nextBatch.push(['removeAll', link, con.name]);
    return this.nextBatch.push(['removeAll', `peer/${con.name}/links`, link]);
  }

  error(con, errorType, msg) {
    con.addCmd(['error', errorType, msg]);
    return false;
  }

  sendTree(con, path, cmd) { // add values for path and all of its children to msg and send to con
    return this.handleStorageCommand(con, cmd, function() {
      return con.addCmd(cmd);
    });
  }

  // delegation
  delegate(con, cmd, cont?) {
    var key, match, num, peer, x;
    [x, key] = cmd;
    if (match = key.match(/^peer\/([^\/]+)\//)) {
      this.verbose(`DELEGATING: ${JSON.stringify(cmd)}`);
      peer = this.peers[match[1]];
      num = this.pendingRequestNum++;
      peer.requests[num] = true;
      this.pendingRequests[num] = [peer, con];
      peer.addCmd(['request', con.name, num, cmd]);
      cont && cont()
    } else {
      return this.error(con, error_bad_peer_request, `Bad request: ${cmd}`);
    }
  }

  get(key) {
    return this.varStorage.values[key];
  }

  setName(con, name) {
    if (name == null) {
      return this.primDisconnect(con, error_bad_message, "No name given in name message");
    } else if (this.peers[name]) {
      return this.primDisconnect(con, error_duplicate_peer_name, `Duplicate peer name: ${name}`);
    } else {
      delete this.peers[con.name];
      this.renamePeerKeys(con, con.name, name);
      this.setConName(con, name);
      return con.addCmd(['set', 'this/name', name]);
    }
  }

  setMaster(con, value) {
    if ((this.master != null) && this.master !== con) {
      return this.primDisconnect(con, error_bad_master, "Xus cannot serve two masters");
    } else {
      this.master = value ? con : null;
      return con.addCmd(['set', 'this/master', value]);
    }
  }

  // Commands
  value(con, cmd, cont) { // cookie, courtesy of Shlomi
    var key, x;
    [x, key] = cmd;
    if (this.isPeerVar(key)) {
      return this.delegate(con, [cmd], cont);
    } else {
      return this.handleStorageCommand(con, cmd, function() {
        con.addCmd(cmd);
        return cont();
      });
    }
  }

  dump(con, cmd, cont) {
    if (this.diag) {
      return this.handleStorageCommand(con, cmd, function(newCmd) {
        con.addCmd(newCmd);
        return cont();
      });
    } else {
      return this.error(con, error_bad_peer_request, "Diag mode not turned on");
    }
  }

  store(con, key, value) {
    throw 'UNIMPLEMENTED'
  }
  remove(con, key) {
    throw 'UNIMPLEMENTED'
  }

  set(con, cmd, cont) {
    let oldInfo;
    let [x, key, value, storageMode] = cmd;

    if (storageMode && storageModes.indexOf(storageMode) === -1) {
      return this.error(con, error_bad_storage_mode, `${storageMode} is not a valid storage mode`);
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
  }

  put(con, cmd, cont) {
    return this.handleStorageCommand(con, cmd, cont);
  }

  splice(con, cmd, cont) {
    return this.handleStorageCommand(con, cmd, cont);
  }

  removeFirst(con, cmd, cont) {
    var key, value, x;
    [x, key, value] = cmd;
    if (!this.varStorage.canRemove(key)) {
      return this.primDisconnect(con, error_variable_not_array, `Can't insert into ${key} because it does not support splice and indexOf`);
    } else {
      return this.handleStorageCommand(con, cmd, cont);
    }
  }

  removeAll(con, cmd, cont) {
    return this.handleStorageCommand(con, cmd, cont);
  }

  removeTree(con, cmd, cont) {
    return this.handleStorageCommand(con, cmd, cont);
  }

  response(con, rcmd, cont) {
    var arg, c, cmd, cmdName, id, j, key, len, peer, receiver, ref, x;
    [x, id, cmd] = rcmd;
    [peer, receiver] = this.pendingRequests[id];
    delete this.pendingRequests[id];
    if (peer !== con) {
      return this.primDisconnect(peer, error_bad_peer_request, "Attempt to responsd to an invalid request");
    } else {
      delete peer.requests[id];
      if (cmd != null) {
        [cmdName, key, arg] = cmd;
        if (cmdName === 'error' && key === error_bad_peer_request) {
          this.primDisconnect(receiver, key, arg);
        } else if (cmdName === 'error' || cmdName === 'value') {
          receiver.addCmd(cmd);
        } else {
          ref = this.relevantConnections(prefixes(key));
          for (j = 0, len = ref.length; j < len; j++) {
            c = ref[j];
            c.addCmd(cmd);
          }
        }
      }
      return cont();
    }
  }

  handleStorageCommand(con, cmd, cont) {
    return this.varStorage.handle(cmd, ((type, msg) => {
      return this.primDisconnect(con, type, msg);
    }), cont);
  }
}

class VarStorage {
  owner: any
  keys: string[]
  values: any
  handlers: any
  keyInfo: any
  newKeys = false

  constructor(owner: any) {
    this.owner = owner
    this.keys = []
    this.values = {}
    this.handlers = {}
    this.keyInfo = {}
  }

  toString() {
    return "A VarStorage";
  }

  verbose(...args) {
    return this.owner.verbose(...args);
  }

  handle(cmd, errBlock, cont) {
    var args, key, name;
    [name, key, ...args] = cmd;
    return this.handlerFor(key)[name](cmd, errBlock, cont);
  }

  handlerFor(key) {
    var handler, k;
    k = prefixes(key).find(p => this.handlers[p])
    handler = k ? this.handlers[k] : this;
    return handler;
  }

  addKey(key, info) {
    if (!this.keyInfo[key]) {
      this.newKeys = true;
      this.keyInfo[key] = info;
      this.keys.push(key);
    }
    //else
    //console.log "KEY #{key} ALREADY PRESENT"
    return info;
  }

  sortKeys() {
    if (this.newKeys) {
      this.keys.sort();
      //console.log "SORTED KEYS: #{@keys}"
      return this.newKeys = false;
    }
  }

  setKey(key, value, info?) {
    var obj;
    if (typeof value === 'function') {
      obj = this.addHandler(key, {
        put: function([x, ...args], errBlock, cont) {
          var err, result;
          try {
            result = value(...args);
          } catch (error) {
            err = error;
            return errBlock(error_bad_peer_request, `Error in computed value: ${err.stack ? err.stack.join('\n') : err}`);
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
  }

  removeKey(key) {
    var newKeys;
    this.verbose(`REMOVING KEY: ${key}`);
    delete this.keyInfo[key];
    delete this.values[key];
    //@sortKeys()
    newKeys = true;
    let idx = this.keys.findIndex((v: string)=> v.localeCompare(key) > -1)
    if (idx > -1) {
      this.verbose(`REMOVED ${key} (${this.keys[idx]})`);
      return this.keys.splice(idx, 1);
    }
  }

  isObject(key) {
    return typeof this.values[key] === 'object';
  }

  canSplice(key) {
    return !this.values[key] || ((this.values[key].splice != null) && (this.values[key].length != null));
  }

  canRemove(key) {
    return this.canSplice(key) && (this.values[key].indexOf != null);
  }

  contains(key) {
    return this.values[key] != null;
  }

  keysForPrefix(pref) {
    return keysForPrefix(this.keys, this.values, pref);
  }

  addHandler(path, obj) {
    obj.__proto__ = this;
    obj.toString = function() {
      return `A Handler for ${path}`;
    };
    this.handlers[path] = obj;
    this.addKey(path, 'handler');
    return obj;
  }

  // handler methods
  dump(con, cmd, cont) {
    var j, key, len, newCmd, ref;
    newCmd = ['value', '', null, true, 'keys', this.keys];
    ref = this.keys;
    for (j = 0, len = ref.length; j < len; j++) {
      key = ref[j];
      newCmd.push(key, this.values[key]);
    }
    return cont(newCmd);
  }

  value(cmd, errBlock, cont) {
    var blk, cookie, counter, j, key, keys, len, path, tree, x;
    [x, path, cookie, tree] = cmd;
    if (tree) {
      console.log(`KEYS: ${this.keys}`);
      keys = this.keysForPrefix(path);
      console.log(`GETTING VALUES FOR PATH: ${path} KEYS: ${JSON.stringify(keys)}, ALL KEYS: ${this.keys}`);
      counter = keys.length;
      blk = function(...args) {
        counter = 0;
        return errBlock(...args);
      };
      if (counter) {
        for (j = 0, len = keys.length; j < len; j++) {
          key = keys[j];
          this.handle(['get', key], blk, function(v) {
            //console.log "VALUE FOR #{key} IS #{v}"
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
  }

  get([x, key], errBlock, cont) {
    return cont(this.values[key]);
  }

  set(cmd, errBlock, cont) {
    var info, key, oldInfo, storageMode, value, x;
    [x, key, value, info] = cmd;
    if (storageMode && storageModes.indexOf(storageMode) === -1) {
      return errBlock(error_bad_storage_mode, `${storageMode} is not a valid storage mode`);
    } else {
      oldInfo = this.keyInfo[key];
      //@keyInfo[key] = storageMode = storageMode || @keyInfo[key] || storage_memory
      storageMode = storageMode || this.keyInfo[key] || storage_memory;
      cmd[2] = value;
      if (storageMode !== storage_transient) {
        return cont(this.setKey(key, value, info));
      }
    }
  }

  put([x, key, value, index], errBlock, cont) {
    if (!this.values[key]) {
      this.values[key] = {};
    }
    if (typeof this.values[key] !== 'object' || this.values[key] instanceof Array) {
      return errBlock(error_variable_not_object, `${key} is not an object`);
    } else {
      if (value === null) {
        delete this.values[key][index];
      } else {
        this.values[key][index] = value;
      }
      if (!Object.keys(this.values[key]).length) {
        this.removeKey(key);
      }
      if (!this.keyInfo[key]) {
        this.addKey(key, storage_memory);
      }
      return cont(value);
    }
  }

  splice([x, key, ...args], errBlock, cont) {
    this.verbose(`SPLICING: ${JSON.stringify([x, key, ...args])}`);
    if (!this.values[key]) {
      this.values[key] = [];
    }
    if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
      return errBlock(error_variable_not_array, `${key} is not an array`);
    } else {
      if (args[0] < 0) {
        args[0] = this.values[key].length + args[0] + 1;
      }
      this.values[key].splice(...args);
      if (!this.keyInfo[key]) {
        this.addKey(key, storage_memory);
      }
      return cont(this.values[key]);
    }
  }

  removeFirst([x, key, value], errBlock, cont) {
    var idx, val;
    if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
      return errBlock(error_variable_not_array, `${key} is not an array`);
    } else {
      val = this.values[key];
      idx = val.indexOf(value);
      if (idx > -1) {
        val.splice(idx, 1);
      }
      return cont(val);
    }
  }

  removeAll([x, key, value], errBlock, cont) {
    var idx, val;
    if (typeof this.values[key] !== 'object' || !(this.values[key] instanceof Array)) {
      return errBlock(error_variable_not_array, `${key} is not an array`);
    } else {
      val = this.values[key];
      while ((idx = val.indexOf(value)) > -1) {
        val.splice(idx, 1);
      }
      return cont(val);
    }
  }

  removeTree([x, key], errBlock, cont) {
    var j, len, ref;
    ref = this.keysForPrefix(key);
    for (j = 0, len = ref.length; j < len; j++) {
      key = ref[j];
      this.removeKey(key);
    }
    return cont();
  }

}

function renameVars(keys, values, handlers, oldName, newName) {
  var j, k, key, len, newKey, newPrefix, oldPrefix, oldPrefixPat, ref, trans;
  oldPrefix = `peer/${oldName}`;
  newPrefix = `peer/${newName}`;
  oldPrefixPat = new RegExp(`^${oldPrefix}(?=/|$)`);
  trans = {};
  ref = keysForPrefix(keys, values, oldPrefix);
  for (j = 0, len = ref.length; j < len; j++) {
    key = ref[j];
    newKey = key.replace(oldPrefixPat, newPrefix);
    values[newKey] = values[key];
    handlers[newKey] = handlers[key];
    trans[key] = newKey;
    delete values[key];
    delete handlers[key];
  }
  keys = (function() {
    var results;
    results = [];
    for (k in values) {
      results.push(k);
    }
    return results;
  })();
  keys.sort();
  return [keys, trans];
}

function keysForPrefix(keys, values, prefix) {
  var initialPattern, prefixPattern, ref, ref1, result;
  initialPattern = `^${prefix}(/|$)`;
  result = [];
  const results = [];
  for (var j = 0, ref = keys.length; 0 <= ref ? j < ref : j > ref; 0 <= ref ? j++ : j--) {
    results.push(j)
  }
  let idx = results.find(i=> keys[i].match(initialPattern))
  if (idx > -1) {
    console.log(`FOUND KEY: ${keys[idx]} AT INDEX: ${idx}`);
    //prefixPattern = "^#{prefix}/"
    prefixPattern = initialPattern;
    //if values[prefix]? then result.push prefix
    idx--;
    while ((ref1 = keys[++idx]) != null ? ref1.match(prefixPattern) : void 0) {
      (values[keys[idx]] != null ? result.push(keys[idx]) : void 0);
    }
  } else {
    console.log(`NO KEYS FOR PREFIX: ${prefix}, KEYS: ${keys}`);
  }
  return result;
}

function caresAbout(con, keyPrefixes: string[]) {
  return keyPrefixes.find(p=> con.listening[p])
}

function prefixes(key: string) {
  const result = []
  const splitKey = key.split('/').filter(x => x !== '')

  while (splitKey.length) {
    result.push(splitKey.join('/'))
    splitKey.pop()
  }
  return result
}