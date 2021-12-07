Base.exit_on_sigint(false)

try
    using Base.Filesystem
    using JSON3
    using Match
    using HTTP

    import Base.@kwdef

    include("types.jl")
    include("protocol.jl")
    include("vars.jl")
    include("commands.jl")
    include("Xus.jl")

    exec(serve, ARGS)
catch err
    !(err isa InterruptException) && println(err)
    exit(1)
end
