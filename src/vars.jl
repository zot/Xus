module Vars

import Base.@kwdef

using ..Types

export Var, ROOT

const ROOT = ("ROOT", UInt(0))::ID

function addvar(cfg::Config, parent, nm::Union{String, Int}, id::ID, val, metadata::Dict{String})
    v = Var(; id, nm, val, metadata)
    cfg.vars[v.id] = v
    if parent != nothing
        if nm != ""
            haskey(parent.children, nm) && throw("Duplicate child name: $(nm)")
            parent.children[nm] = v
        else #create numbered child
            push!(parent.numberedChildren, v)
        end
    end
end

function addvar(config::Config, name::String, id::ID, value, metadata::Dict{String})
    v = Var(; id, name, value, metadata)
    config.vars[v.id] = v
end

function setvar(config::Config, name::String, id::ID, value, metadata = Dict())
    var = config.vars.get(id, ()-> addvar(config, name, id, value, metadata))
    config.vars[id] = value
end

function findvar(config::Config, path::String)
    cur = config.vars[ROOT]
    names = split(path, ".")
    for name in names
        if !haskey(cur.children, name)
            throw(UnknownVariable(name))
        end
        cur = cur.children[name]
    end
    cur.id
end

end
