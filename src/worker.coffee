connections = []

addEventListener 'connect', (w)->
  port = w.ports[0]
  port.addEventListener 'message', (e)->