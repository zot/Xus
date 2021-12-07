module Commands

using ..Types
using ..Vars

export client_cmds

"""
    client_set

send a set command to the server
"""
function client_set(cfg::Config, path, value)
    send(cfg, ["set", findvar(cfg.env, path), value])
end

"""
    client_cmds

Commands that forward to the server
"""
const client_cmds = (
    set = client_set,
)

end
