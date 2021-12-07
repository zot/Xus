using Revise

Base.exit_on_sigint(false)

try
    using Base.Filesystem
    using JSON3
    using Match
    using HTTP

    import Base.@kwdef

    includet("types.jl")
    includet("protocol.jl")
    includet("vars.jl")
    includet("commands.jl")
    includet("Xus.jl")

    exec(ARGS) do config, ws
        Revise.revise()
        Base.invokelatest(serve, config, ws)
    end
catch err
    !(err isa InterruptException) && println(err)
    exit(1)
end
