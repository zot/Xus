using .Types
using .Commands
using .Protocol

verbose = false

strdefault(str, default) = str == "" ? default : str

function usage()
    print("""Usage: xus NAMESPACE [-v] [-s ADDR | -c ADDR [-x SECRET]] [CMD [ARGS...]]

NAMESPACE is this xus instance's namespace

-s ADDR     run server on ADDR if ADDR starts with '/', use a UNIX domain socket
-c ADDR     connect to ADDR using NAMESPACE
-x SECRET   use secret to prove ownership of NAMESPACE. If NAMESPACE does not yet exist,
            the server creates it and associates SECRET with it.
-v          verbose

COMMANDS
set [[-c] [-m NAME VALUE]... PATH VALUE]...
  Set a variable, optionally creating it if it does not exist. NAMESPACE defaults to the
  current namespace.

  -c        create the variable
  -m        set the named meta value.

  The format of PATH is
    NAME ['.' NAME]...
    or
    NAMESPACE '/' ID ['.' NAME]...

  NAMESPACE '/' ID defaults to ROOT/0.
  The name '?' means to create a new numbered child of NAMESPACE '/' ID.
  The name '?N' refers to the Nth variable created in this command (to help create trees)

  Set returns a JSON list of ids and names of any variables that were created:
    [id1, name1, id2, name2, ...]

  COMMON META VALUE NAMES
  app       create an instance of the named application's object model
  path      the path from the parent's value to the corresponding value in the object model
  monitor   whether to monitor the value in the object model value is yes/no/true/false/on/off

delete ID
  Remove a variable and all of its children

observe ID...
    receive updates whenever variables or their children change. Update format is ID VALUE.
  Newlines in VALUE are escaped.
""")
    exit(1)
end

log(args...) = @info join(args)

function shutdown(config)
    println("SHUTTING DOWN")
end

function parseAddr(config::Config, s)
    m = match(r"^(([^:]+):)?([^:]+)$", s)
    config.host == m[2] ? "127.0.0.1" : m[2]
    config.port = parse(UInt16, m[3])
end

function abort(msg...)
    println(Base.stderr, msg...)
    exit(1)
end

out(ws; args...) = out(ws, JSON3.write(args))
function out(ws, cmd)
    write(ws, JSON3.write(cmd))
    flush(ws)
end

in(ws) = JSON3.read(readavailable(ws))

function parseid(cmd::XusCmd, create, vars, path)
    components = split(path, '.')
    var = nothing
    for (i, v) in enumerate(components)
        if v == "?"
            i < length(components) && throw("'?' in the middle of $(path)")
            var = addvar(config, "", 
        else
            m = match(r"^\?([0-9]+)$")
            if m != nothing
            var = vars[parse(Int, m[1])]
            elseif 
                *******
            end
        end
    end
end

function command(cmd::XusCmd{:set})
    vars = []
    create = false
    metadata = Dict()
    function init()
        create = false
        metadata = Dict()
    end
    println("SET (FRED 5): $(cmd.args)")
    pos = 1
    while pos <= length(cmd.args)
        @match cmd.args[pos] begin
            "-c" => (create = true)
            "-m" => begin
                metadata[cmd.args[pos + 1]] = metadata[pos + 2]
                pos += 2
            end
            unknown => begin
                id = parseid(cmd, create, vars, args[pos])
                value = args[pos += 1]
            end
        end
        pos += 1
    end
    out(cmd.ws, ["set", metadata, cmd.args...])
    flush(cmd.ws)
end

function serve(config::Config, ws)
    (; namespace, secret) = in(ws)
    if haskey(config.namespaces, namespace)
        if config.namespaces[namespace] !== secret
            println("Bad attempt to connect for $(namespace)")
            out(ws, error = "Wrong secret for $(namespace)")
            return
        end
    end
    println("Connection for $(namespace)")
    while !eof(ws)
        try
            !isopen(ws) && break
            string = readavailable(ws)
            isempty(string) && continue
            cmd = JSON3.read(string, Vector)
            command(XusCmd(config, ws, namespace, cmd))
        catch err
            !isa(err, ArgumentError) && println(err)
            println(err)
            break
        end
    end
    close(ws)
    println("CLIENT CLOSED:", namespace)
end

function server(config::Config)
    println("SERVER ON $(config.host):$(config.port)")
    HTTP.WebSockets.listen(config.host, config.port) do ws
        config.serverfunc(config, ws)
    end
    println("HTTP SERVER FINISHED")
end

function client(config::Config)
    if config.secret === "" abort("Secret required") end
    println("CLIENT $(config.namespace) connecting to ws//$(config.host):$(config.port)")
    HTTP.WebSockets.open("ws://$(config.host):$(config.port)") do ws
        out(ws, (namespace = config.namespace, secret = config.secret))
        out(ws, config.args)
        result = JSON3.read(readavailable(ws)) # read one message
        println("RESULT: $(result)")
    end
end

function exec(serverfunc, args::Vector{String}; config = Config())
    if length(args) === 0 || match(r"^-.*$", args[1]) !== nothing
        usage()
    end # name required -- only one instance per name allowed
    config.serverfunc = serverfunc
    config.namespace = args[1]
    requirements = []
    i = 2
    while i <= length(args)
        @match args[i] begin
            "-v" => (config.verbose = log)
            "-x" => (config.secret = args[i += 1])
            "-c" => begin
                parseAddr(config, args[i += 1])
            end
            "-s" => begin
                parseAddr(config, args[i += 1])
                config.server = true
            end
            unknown => begin
                println("MATCHED DEFAULT: $(args[i:end])")
                push!(config.args, args[i:end]...)
                i = length(args)
                break
            end
        end
        i += 1
    end
    atexit(()-> shutdown(config))
    (config.server ? server : client)(config)
    config
end
