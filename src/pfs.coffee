q = require 'q'
fs = require 'fs-ext'

ex = module.exports

ex.flock = (fd, flags)->
  if typeof fd is 'number' then basicFlock(fd, flags)
  else fd.then (realFd)-> basicFlock(realFd, flags)

ex.open = (path, flags, mode)-> q.ncall(fs.open, fs, path, flags, mode)

ex.close = (fd)-> q.ncall(fs.close, fs, fd)

ex.truncate = (fd, len)-> q.ncall(fs.close, fs, fd, len)

ex.mkdir = (path, mode)-> q.ncall(fs.mkdir, fs, path, mode)

ex.stat = (path)-> q.ncall(fs.stat, fs, path)

ex.createReadStream = (path, options)-> q.ncall(fs.createReadStream, fs, path, options)

ex.readFile = (fd)->
  done = q.defer()
  readSome done, fd, new Buffer(4096), ''
  done.promise

ex.writeFile = (fd, str)->
  done = q.defer()
  writeSome done, fd, new Buffer(str), 0
  done.promise

basicFlock = (fd, flags)-> q.ncall(fs.flock, fs, fd, flags)

readSome = (d, fd, buf, str)->
  try
    fs.read fd, buf, 0, buf.length, null, (err, bytesRead, buffer)->
      if err then d.reject err
      else if bytesRead is 0 then d.resolve str
      else readSome d, fd, buf, str + buffer.toString(null, 0, bytesRead)
  catch err
    d.reject err

writeSome = (d, fd, buf, bufPos)->
  try
    fs.write fd, buf, bufPos, buf.length - bufPos, null, (err, written, buf)->
      if err then d.reject err
      else if written is 0 then d.resolve true
      else writeSome d, fd, buf, bufPos + written
  catch err
    d.reject err
