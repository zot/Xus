module Types

import Base.@kwdef

using JSON3: StructTypes
using HTTP

export Config, State, UnknownVariable, ID, Var, XusCmd

struct UnknownVariable <:Exception
    name
end

@kwdef mutable struct State
    servers::Dict{String, @NamedTuple{host::String,port::UInt16,pid::Int32}} = Dict()
end

StructTypes.StructType(::Type{State}) = StructTypes.Mutable()

const ID = Tuple{String, UInt}

"""
    Var

A variable:
    id: the variable's unique ID, used in the protocol
    name: the variable's human-readable name, used in UIs and diagnostics
"""
@kwdef mutable struct Var
    id::ID = root # root is just the default value and will most likely be changed
    name::AbstractString = ""
    value::Any = nothing
    metadata::Dict{String} = Dict()
    children::Dict{String, Var} = Dict()
    numberedChildren::Vector{Var} = []
end

"""
    Config

Singleton for this program's state.
    namespace: this program's unique namespace, assigned by the server
    nextId: the next available id in this namespace
    vars: all known variables
"""
@kwdef mutable struct Config
    namespace = ""
    nextid = UInt(0)
    vars::Dict{ID, Var} = Dict()
    host = "0.0.0.0"
    port = UInt16(8181)
    server = false
    diag = false
    proxy = false
    verbose = (args...)-> nothing
    cmd = ""
    args = String[]
    client = ""
    pending::Dict{Int, Function} = Dict()
    nextmsg = 0
    namespaces = Dict{String, String}() # namespaces and their secrets
    secret = ""
    serverfunc = (cfg, ws)-> nothing
end

@kwdef struct XusCmd{NAME}
    config::Config
    ws::HTTP.WebSockets.WebSocket
    namespace::AbstractString
    args::Vector
    XusCmd(cfg, ws, ns, args::Vector) = new{Symbol(lowercase(args[1]))}(cfg, ws, ns, args[2:end])
end

end
