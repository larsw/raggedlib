"""Shared query sets for the search evals (see README.md).

QUERIES: (query, regex over book titles marking the books that should answer it). Book-level relevance is
approximate (a relevant book can still return an off-topic passage) but reproducible without a judge.
"""

QUERIES = [
    ("return oriented programming gadgets", r"hack|exploit|revers|binary|ctf|rootkit|privilege|shellcode|malware|assembly|black hat|security engineering"),
    ("how does kubernetes schedule pods onto nodes", r"kubernetes|openshift|rancher|helm|istio|cloud native|knative|container"),
    ("what is a monad", r"haskell|category theory|functional|scala|purescript|cats|lenses|clojure|erlang|elixir|kotlin|programming languages"),
    ("TLS handshake certificate verification", r"ssl|tls|cryptograph|security|keycloak|api security|serious crypto"),
    ("rust ownership and borrowing rules", r"rust"),
    ("kafka consumer group rebalancing", r"kafka|event|stream|data mesh"),
    ("elliptic curve point multiplication", r"ellip|cryptograph|number theory|crypto|coding theory|algebra"),
    ("how to pick a pin tumbler lock", r"lock pick|lock picking"),
    ("prolog cut operator and backtracking", r"prolog|logic|constraint|lisp"),
    ("SQL injection in web applications", r"hack|bug hunting|web|api|ctf|security|black hat|ghost"),
    ("erlang supervisor trees let it crash", r"erlang|elixir|proper"),
    ("choosing a learning rate for gradient descent", r"machine learning|deep learning|neural|tensorflow|scikit|pytorch|reinforcement|grokking (deep|artificial)|ai and machine|artificial intelligence|information theory|unsupervised"),
    ("aggregates and bounded contexts in domain driven design", r"domain|microservice|architect|monolith|data mesh|software design"),
    ("linux privilege escalation via SUID binaries", r"privilege|hack|ctf|ghost|linux|black hat|security"),
    ("kernel of a group homomorphism", r"algebra|group|rings|fields|category|topology|pure mathematics"),
    ("spring boot auto-configuration", r"spring|quarkus|java"),
    ("attaching eBPF programs to kernel tracepoints", r"ebpf|kubernetes security|linux|container security"),
    ("CUDA shared memory and thread synchronization", r"cuda|gpu|concurren|parallel|high performance"),
    ("react useEffect hook cleanup", r"react|front|javascript|micro.?front"),
    ("structured concurrency with kotlin coroutines", r"kotlin|reactive|concurren"),
    ("debugging the windows kernel with windbg", r"windows|debug|rootkit|revers|malware"),
    ("paxos consensus in distributed systems", r"lamport|distributed|database internals|tla|concurren|security engineering|reliable|scale"),
    ("heap spraying exploit", r"hack|exploit|revers|binary|ctf|rootkit|malware|black hat|security|javascript ninja|browser"),
    ("why are tests that depend on time flaky", r"test|refactor|clean|code|software|legacy|pragmatic"),
    # identifier / exact-term style (where full-text should earn its keep)
    ("MAP_FIXED mmap flag", r"linux|unix|system|hack|exploit|kernel|rust|operating|binary|shellcoder"),
    ("kubectl cordon and drain a node", r"kubernetes|openshift|rancher|cloud native|cka"),
    ("setuid(0) shellcode", r"hack|exploit|shellcode|malware|rootkit|ctf|privilege|binary|black hat|revers"),
    ("vim visual block mode ctrl-v", r"\bvi\b|vim"),
    ("useState vs useReducer", r"react|front"),
    ("SELECT ... FOR UPDATE SKIP LOCKED", r"database|sql|postgres|persistence|spring|data|internals"),
    ("@Transactional propagation REQUIRES_NEW", r"spring|java|persistence|quarkus|microservices"),
    ("git rebase --onto", r"\bgit\b"),
    ("sigaction SA_RESTART", r"linux|unix|system|programming interface|operating|advanced programming"),
    ("LD_PRELOAD hooking", r"hack|linux|malware|rootkit|binary|revers|security|black hat|exploit"),
    ("tf.GradientTape custom training loop", r"tensorflow|machine learning|deep learning|keras|ai and machine"),
    ("ownership transfer with std::move", r"c\+\+|cpp|rust|primer"),
]

# misspelled queries and the corrected query the spelling correction should search for (None: any correction)
TYPOS = [
    ("return orientd programming gadgest", "return oriented programming gadgets"),
    ("how does kubernetes shedule pods onto nodes", "how does kubernetes schedule pods onto nodes"),
    ("what is a mondad", "what is a monad"),
    ("TLS handshake certificat verification", "TLS handshake certificate verification"),
    ("rust ownership and borowing rules", "rust ownership and borrowing rules"),
    ("elliptic curve point multiplicaton", "elliptic curve point multiplication"),
    ("prolog cut operator and backtraking", "prolog cut operator and backtracking"),
    ("erlang supervisor trees let it crahs", "erlang supervisor trees let it crash"),
    ("choosing a lerning rate for gradient descnet", "choosing a learning rate for gradient descent"),
    ("agregates and bounded contexts in domain driven design", "aggregates and bounded contexts in domain driven design"),
    ("linux privilege escalaton via SUID binaries", "linux privilege escalation via SUID binaries"),
    ("paxos concensus in distributed systems", "paxos consensus in distributed systems"),
    ("heap sprayng exploit", "heap spraying exploit"),
    ("asyncronous programming in javascript", "asynchronous programming in javascript"),
    ("Kuberentes networking", "Kubernetes networking"),
    ("Dijsktra shortest path", "Dijkstra shortest path"),
    ("CUDA shared memmory and thread synchronisation", None),
    # classic misspellings that books contain too
    ("recieve", "receive"), ("seperate", "separate"), ("untill", "until"), ("enviroment", "environment"),
    ("retreival", "retrieval"), ("adress", "address"), ("lenght", "length"), ("comit", "commit"),
    ("transfomer", "transformer"), ("netwrok", "network"), ("acess", "access"), ("databse", "database"),
    # two edits in long words, possessives, quotes, same-stem typos
    ("tarnsaciton isolation levels", "transaction isolation levels"),
    ("javscirpt closures", "javascript closures"),
    ("typscirpt generics", "typescript generics"),
    ("Pyhton's GIL", "Python's GIL"),
    ("'kubernets' pods", "'kubernetes' pods"),
    ("debuging", "debugging"),
    ("kubernets", "kubernetes"),
]

# correctly spelled queries (besides QUERIES) that must come back unchanged: names, jargon, rare real words
MUST_NOT_CHANGE = [
    "who won the 1998 world cup", "Maradona 1986 world cup", "useState vs useReducer", "monads in haskell",
    "ostree atomic upgrades", "Lamport clocks", "Zig comptime", "Gleam language", "eBPF verifier",
    "Hono", "Guix", "Niri", "Ghostty", "Tailscale", "Catherina", "volter", "ministering", "militated", "minced",
    "metros", "millstones", "microfilmed", "maimed", "midget", "comptime", "sigaction SA_RESTART",
]
